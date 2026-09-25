"""AI 质检服务：调用千问 / DeepSeek 对客服会话进行结构化初检。

接入方式参考企业知识库 Python 后端：Qwen/DeepSeek 都走 OpenAI 兼容
``/chat/completions`` 接口；未配置密钥时降级为规则引擎 mock 结果，
保证本地骨架始终可运行。
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any

import httpx

from ..config import get_settings
from ..core.db import connect
from ..core.trace import get_trace_id

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """你是企业智能客服的资深质检专家。
请根据下方质检规则与客服会话记录，完成结构化初检。

评分规则：
1. aiScore：0~100 的整数，90 以上优秀、80~89 良好、60~79 及格、60 以下需改进；
2. riskLevel：1=低风险，2=中风险，3=高风险；
3. rules：仅返回命中的规则名称，没有命中则返回空数组；
4. comment：用一句中文总结问题与改进建议。

必须严格按下面的 JSON 结构输出，字段名一个字母都不能改，不要输出 Markdown 代码块或其他文字：
{"aiScore": 85, "riskLevel": 1, "rules": ["命中的规则名称"], "comment": "中文结论"}"""

# 模型偶尔会写错字段名（例如把 riskLevel 写成 rel），这里做别名兼容
_SCORE_KEYS = ("aiScore", "ai_score", "score", "totalScore")
_RISK_KEYS = ("riskLevel", "risk_level", "risk", "rel", "level")
_RULE_KEYS = ("rules", "ruleNames", "rule_names", "hitRules")
_COMMENT_KEYS = ("comment", "comments", "summary", "conclusion")


def _fallback_result(
    session_name: str,
    agent_name: str,
    transcript: str,
    rule_names: list[str],
    reason: str,
    *,
    tenant_code: str = "",
    provider: str = "",
    model: str = "",
) -> dict[str, Any]:
    """未配置密钥或调用失败时的确定性兜底，保证质检流程可继续。"""
    logger.warning(
        "AI 质检降级 trace=%s tenant=%s session=%s provider=%s model=%s reason=%s",
        get_trace_id() or "-", tenant_code, session_name, provider or "-", model or "-", reason,
    )
    hit_rules = [name for name in rule_names if _keyword_hit(transcript, name)]
    score = 88 if not hit_rules else max(60, 88 - len(hit_rules) * 8)
    risk = 1 if not hit_rules else (2 if len(hit_rules) == 1 else 3)
    return {
        "aiScore": score,
        "riskLevel": risk,
        "rules": hit_rules,
        "comment": "AI 服务未返回结果，当前使用本地规则兜底初检。",
        "source": "fallback-rule",
    }


def _keyword_hit(text: str, rule_name: str) -> bool:
    keywords = {
        "敏感词与禁语": ["烦死了", "随便你", "自己看", "不知道"],
        "服务承诺规范": ["尽快", "马上", "放心", "一定"],
        "必答项完整": ["订单号", "发票", "地址", "电话"],
        "情绪安抚": ["不满意", "投诉", "差评", "生气", "失望"],
    }
    return any(word in text for word in keywords.get(rule_name, []))


def _provider_conf(name: str, api_key: str, base_url: str, model: str) -> dict[str, str]:
    return {
        "provider": name,
        "api_key": api_key,
        "base_url": (base_url or "").rstrip("/"),
        "model": model,
    }


async def _call_chat(chosen: dict[str, str], payload: dict[str, Any], timeout: int) -> dict[str, Any]:
    """调用 OpenAI 兼容的 /chat/completions 接口，并打印真实请求体。"""
    if get_settings().llm_log_payload:
        logger.info("AI 质检模型入参 trace=%s provider=%s model=%s payload=%s",
                    get_trace_id() or "-", chosen["provider"], chosen["model"],
                    json.dumps(payload, ensure_ascii=False))
    headers = {"Authorization": f"Bearer {chosen['api_key']}", "Content-Type": "application/json"}
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(
            f"{chosen['base_url']}/chat/completions",
            headers=headers,
            json=payload,
        )
        response.raise_for_status()
        return response.json()


def _choose_provider(provider: str | None, model: str | None):
    """挑选真实可用的模型供应商。

    优先级：显式指定 > 全局默认配置 > 自动选择已配置密钥的厂商（千问优先）。
    只有所有厂商都没有密钥时才返回 None，此时质检走本地规则兜底。
    这样即使 provider 仍是骨架默认的 mock，只要配了密钥就会用真实模型。
    """
    settings = get_settings()
    requested = (provider or settings.llm_default_provider or "").lower()
    if "deepseek" in requested:
        return _provider_conf(
            "deepseek", settings.deepseek_api_key, settings.deepseek_base_url,
            model or settings.deepseek_model,
        )
    if "qwen" in requested or "dashscope" in requested:
        return _provider_conf(
            "qwen", settings.qwen_api_key, settings.qwen_base_url,
            model or settings.qwen_model,
        )
    # provider=mock / 未配置：只要配了厂商密钥就用真实模型
    if settings.qwen_api_key:
        logger.info("未指定真实模型供应商（provider=%s），自动使用千问 model=%s",
                    requested or "空", model or settings.qwen_model)
        return _provider_conf(
            "qwen", settings.qwen_api_key, settings.qwen_base_url,
            model or settings.qwen_model,
        )
    if settings.deepseek_api_key:
        logger.info("未指定真实模型供应商（provider=%s），自动使用 DeepSeek model=%s",
                    requested or "空", model or settings.deepseek_model)
        return _provider_conf(
            "deepseek", settings.deepseek_api_key, settings.deepseek_base_url,
            model or settings.deepseek_model,
        )
    return None


# 租户模型配置缓存：批量质检时避免每个任务都查一次库
_TENANT_MODEL_TTL = 60.0
_tenant_model_cache: dict[str, tuple[float, tuple[str | None, str | None]]] = {}


def _tenant_model(tenant_code: str) -> tuple[str | None, str | None]:
    """读取租户在「智能机器人 → 模型选择」里配置的模型。

    读不到（未配置 / 数据库不可用）时返回 ``(None, None)``，不影响质检主流程。
    """
    if not tenant_code:
        return None, None
    now = time.monotonic()
    cached = _tenant_model_cache.get(tenant_code)
    if cached and now - cached[0] < _TENANT_MODEL_TTL:
        return cached[1]

    resolved: tuple[str | None, str | None] = (None, None)
    try:
        with connect() as conn:
            row = conn.execute(
                """SELECT m.provider, m.model_key
                     FROM bot_setting s
                     JOIN bot_model m
                       ON m.tenant_code = s.tenant_code AND m.model_key = s.model_key
                    WHERE s.tenant_code = %s AND s.is_deleted = FALSE
                    LIMIT 1""",
                (tenant_code,),
            ).fetchone()
        if row:
            provider = (row.get("provider") or "").lower()
            model_key = (row.get("model_key") or "").lower()
            # provider 列可能被写成平台自定义值（如 yunti），此时按模型名兜底识别
            if "deepseek" in provider or "deepseek" in model_key:
                resolved = ("deepseek", row.get("model_key"))
            elif "qwen" in provider or "dashscope" in provider or "qwen" in model_key:
                resolved = ("qwen", row.get("model_key"))
    except Exception as exc:  # noqa: BLE001
        logger.warning("读取租户模型配置失败 tenant=%s error=%s: %s",
                       tenant_code, type(exc).__name__, exc)

    _tenant_model_cache[tenant_code] = (now, resolved)
    return resolved


async def evaluate(
    tenant_code: str,
    session_name: str,
    agent_name: str,
    transcript: str,
    rule_names: list[str],
    provider: str | None = None,
    model: str | None = None,
    rules: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """对会话执行 LLM 初检，返回 aiScore/riskLevel/rules/comment。"""
    settings = get_settings()
    requested_provider = provider
    requested_model = model
    # 数据库里配了模型就优先用（来自「智能机器人 → 模型选择」）
    if not requested_provider:
        tenant_provider, tenant_model = _tenant_model(tenant_code)
        if tenant_provider:
            requested_provider = tenant_provider
            requested_model = requested_model or tenant_model

    chosen = _choose_provider(requested_provider, requested_model)
    # 选中的厂商没配密钥时，退回到其它已配置密钥的厂商，避免白白走规则兜底
    if chosen is not None and not chosen["api_key"]:
        logger.warning("provider=%s 未配置 API Key，尝试改用其它已配置密钥的厂商", chosen["provider"])
        chosen = _choose_provider(None, None)
    if chosen is None or not chosen["api_key"]:
        return _fallback_result(
            session_name, agent_name, transcript, rule_names,
            "未配置可用的模型 API Key（YUNTI_AI_QWEN_API_KEY / YUNTI_AI_DEEPSEEK_API_KEY）",
            tenant_code=tenant_code,
            provider=requested_provider or settings.llm_default_provider or "-",
            model=requested_model or settings.llm_default_model or "-",
        )

    rule_count = len(rules) if rules else len(rule_names)
    started = time.perf_counter()
    logger.info(
        "AI 质检调用开始 trace=%s tenant=%s session=%s agent=%s provider=%s model=%s rules=%d transcriptChars=%d",
        get_trace_id() or "-", tenant_code, session_name, agent_name,
        chosen["provider"], chosen["model"], rule_count, len(transcript or ""),
    )

    if rules:
        # 带上规则的具体检查内容，AI 才能按企业自己的规则判分
        rule_text = "\n".join(
            f"- {item.get('name')}：{item.get('content') or '（未填写检查内容）'}"
            for item in rules
        )
    else:
        rule_text = "\n".join(f"- {name}" for name in rule_names)
    rule_text = rule_text or "-（无规则约束，请按客服服务质量通用标准）"
    user_content = f"""企业租户：{tenant_code}
质检会话：{session_name}
接待客服：{agent_name}

【质检规则】
{rule_text}

【客服会话记录】
{transcript[:3000]}"""

    payload = {
        "model": chosen["model"],
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_content},
        ],
        "temperature": 0.2,
        "max_tokens": 600,
    }
    # 要求模型按 JSON 输出，避免出现 rel / risk 这类字段名偏差
    json_payload = {**payload, "response_format": {"type": "json_object"}}
    try:
        try:
            data = await _call_chat(chosen, json_payload, settings.llm_timeout)
        except httpx.HTTPStatusError as exc:
            # 个别模型 / 兼容网关不支持 response_format，退回普通模式再试一次
            logger.warning("模型不支持 response_format=json_object（status=%s），改用普通模式重试",
                           exc.response.status_code)
            data = await _call_chat(chosen, payload, settings.llm_timeout)
        content = data["choices"][0]["message"]["content"]
        if settings.llm_log_payload:
            logger.info("AI 质检模型出参 trace=%s provider=%s model=%s content=%s",
                        get_trace_id() or "-", chosen["provider"], chosen["model"], content)
        result = _parse_llm_json(content)
        result["source"] = f"llm-{chosen['provider']}"
        usage = data.get("usage") or {}
        logger.info(
            "AI 质检调用成功 trace=%s tenant=%s session=%s provider=%s model=%s latencyMs=%d "
            "promptTokens=%s completionTokens=%s totalTokens=%s score=%s risk=%s hitRules=%d source=%s",
            get_trace_id() or "-", tenant_code, session_name, chosen["provider"], chosen["model"],
            int((time.perf_counter() - started) * 1000),
            usage.get("prompt_tokens", "-"), usage.get("completion_tokens", "-"),
            usage.get("total_tokens", "-"), result["aiScore"], result["riskLevel"],
            len(result["rules"]), result["source"],
        )
        return result
    except Exception as exc:  # noqa: BLE001
        logger.error(
            "AI 质检调用异常 trace=%s tenant=%s session=%s provider=%s model=%s latencyMs=%d error=%s: %s",
            get_trace_id() or "-", tenant_code, session_name, chosen["provider"], chosen["model"],
            int((time.perf_counter() - started) * 1000), type(exc).__name__, exc,
        )
        return _fallback_result(
            session_name, agent_name, transcript, rule_names, type(exc).__name__,
            tenant_code=tenant_code, provider=chosen["provider"], model=chosen["model"],
        )


def _parse_llm_json(content: str) -> dict[str, Any]:
    text = content.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.startswith("json"):
            text = text[4:]
    start, end = text.find("{"), text.rfind("}")
    if start >= 0 and end > start:
        text = text[start : end + 1]
    parsed = json.loads(text)

    score_raw = _pick(parsed, _SCORE_KEYS)
    if score_raw is None:
        # 拿不到评分说明模型没按约定输出，交给上层走失败降级，不能凭空编一个分数
        raise ValueError("模型返回缺少评分字段")
    score = int(float(score_raw))

    risk_raw = _pick(parsed, _RISK_KEYS)
    if risk_raw is None:
        # 缺 riskLevel 时按分数换算，避免一律默认成低风险
        risk = 1 if score >= 88 else (2 if score >= 75 else 3)
        logger.warning("模型返回缺少 riskLevel，按评分 %s 推断为 %s", score, risk)
    else:
        risk = int(float(risk_raw))

    rules = _pick(parsed, _RULE_KEYS) or []
    if not isinstance(rules, list):
        rules = [rules]
    return {
        "aiScore": max(0, min(100, score)),
        "riskLevel": max(1, min(3, risk)),
        "rules": [str(r) for r in rules][:10],
        "comment": str(_pick(parsed, _COMMENT_KEYS, "AI 质检完成，请人工抽样复核确认。"))[:500],
    }


def _pick(data: dict[str, Any], keys: tuple[str, ...], default: Any = None) -> Any:
    """按别名列表取值，忽略空值。"""
    for key in keys:
        value = data.get(key)
        if value is not None and value != "":
            return value
    return default


async def run_qa_task(tenant_code: str, session_id: str) -> dict[str, object]:
    """保留批处理入口签名，后续 Kafka worker 可直接复用。"""
    return await evaluate(tenant_code, f"会话-{session_id}", "客服", "", [], provider="mock")
