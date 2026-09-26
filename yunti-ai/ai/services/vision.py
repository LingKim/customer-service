"""图片识别 / OCR / 截图判读：把客户发来的图看懂，并给出一句话结论。

客服场景里客户爱发图，而且图里的信息往往比文字更关键：

| 客户发什么 | 需要模型给出什么 |
| --- | --- |
| 报错截图 | 报错原文（OCR）+ 这是哪类问题 |
| 订单/账单截图 | 订单号、金额、时间（结构化抽出来） |
| 商品破损照 | 一句话描述（不用 OCR） |
| 聊天记录截图 | 对话原文（OCR） |

所以这一层要求模型**同时给两样东西**：`ocrText`（把图里的字逐条抄出来）和 `summary`
（一句话判读），外加几个能直接进业务的结构化字段（订单号 / 金额 / 报错关键字）。

两个原则和第 21、22 篇一致：
    1. **没配密钥不假装能识别**：直接返回 `available=false` 并说明原因，绝不编一段"我看到图上写着…"；
    2. **模型的输出要能核对**：JSON 解析失败就退回纯文本（把正文当 summary），不让业务拿到半截数据。
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any

from ..config import get_settings
from ..core.llm_vision import call_vision, message_content, vision_provider
from ..core.trace import get_trace_id

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """你是企业在线客服的图片识别助手。请阅读客户发来的图片，输出一个 JSON 对象。

要求：
1. `ocrText`：把图片里的文字**逐字抄下来**（保持原有换行，OCR 做不到别猜，抄不到就留空字符串）；
2. `summary`：一句话说明"这是什么图、客户想解决什么"，用中文，40 字以内；
3. `orderNo`：图里出现的订单号 / 单号（没有就空字符串）；
4. `amount`：图里出现的金额（形如 "199.00"，没有就空字符串）；
5. `errorText`：如果这是报错截图，抄下**报错原文**（没有就空字符串）；
6. `needHuman`：图片涉及退款失败、支付异常、账号被盗、投诉等敏感情形时为 true；
7. 只输出 JSON，不要用代码块包裹，不要解释推理过程。

格式：
{"ocrText": "...", "summary": "...", "orderNo": "", "amount": "", "errorText": "", "needHuman": false}"""


async def recognize(images: list[str], question: str = "", tenant_code: str = "") -> dict[str, Any]:
    """识别一组图片。

    @param images    data URL 列表（Java 侧压好 JPEG 再 base64 送来）
    @param question  客户随图说的一句话（可能为空）
    @return 统一结构：available / summary / ocrText / orderNo / amount / errorText / needHuman / provider / model
    """
    result: dict[str, Any] = {
        "available": False,
        "summary": "",
        "ocr_text": "",
        "order_no": "",
        "amount": "",
        "error_text": "",
        "need_human": False,
        "provider": "",
        "model": "",
        "hint": "",
    }
    if not images:
        result["hint"] = "没有可识别的图片"
        return result

    chosen, desc = vision_provider(tenant_code)
    if chosen is None:
        # 没有密钥就如实说，绝不编识别结果——编出来的"图上写着 XXX"比不识别更害人
        logger.warning("图片识别不可用 tenant=%s 原因=%s", tenant_code or "-", desc)
        result["hint"] = desc + "；配好 YUNTI_AI_QWEN_API_KEY 后即可识别图片"
        return result

    prompt = SYSTEM_PROMPT
    if question and question.strip():
        prompt += f"\n\n客户随图说的一句是：{question.strip()[:200]}"

    logger.info("图片识别请求 trace=%s tenant=%s 张数=%d 单张≈%dKB 模型=%s",
                get_trace_id() or "-", tenant_code or "-", len(images),
                len(images[0]) // 1024 if images else 0, desc)
    data = await call_vision(chosen, prompt, images, get_settings().llm_timeout, scene="图片识别")
    content = message_content(data)
    parsed = _parse_json(content)

    result.update({
        "available": True,
        "provider": chosen["provider"],
        "model": chosen["model"],
        "summary": _text(_pick(parsed, "summary")) or _fallback_summary(content),
        "ocr_text": _text(_pick(parsed, "ocrText", "ocr_text")),
        "order_no": _text(_pick(parsed, "orderNo", "order_no")),
        "amount": _text(_pick(parsed, "amount")),
        "error_text": _text(_pick(parsed, "errorText", "error_text")),
        "need_human": bool(_pick(parsed, "needHuman", "need_human") or False),
    })
    logger.info("图片识别完成 trace=%s tenant=%s 模型=%s OCR字符数=%d 建议人工=%s",
                get_trace_id() or "-", tenant_code or "-", desc,
                len(result["ocr_text"]), result["need_human"])
    return result


def _indent(text: str, limit: int = 800) -> str:
    """把 OCR 全文按行缩进打进日志（超长截断），方便直接和客户发的图对照"""
    if not text:
        return "    （没识别到文字）"
    lines = [line.strip() for line in text.splitlines() if line.strip()][:20]
    body = "\n".join("    " + line for line in lines)
    if len(body) > limit:
        body = body[:limit] + "\n    …（已截断）"
    return body


def _inline(text: str, limit: int) -> str:
    return re.sub(r"\s+", " ", (text or "")).strip()[:limit]


def _pick(parsed: dict[str, Any] | None, *keys: str) -> Any:
    """从解析结果里按多个候选键名取值（模型有时写驼峰、有时写下划线）。"""
    if not parsed:
        return None
    for key in keys:
        if key in parsed and parsed[key] not in (None, ""):
            return parsed[key]
    return None


def _text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (list, dict)):
        return json.dumps(value, ensure_ascii=False)
    return str(value).strip()


def _parse_json(text: str) -> dict[str, Any] | None:
    """从模型输出里抠 JSON：先整体解析，不行再取第一对花括号。"""
    if not text:
        return None
    candidate = text.strip()
    if candidate.startswith("```"):
        candidate = re.sub(r"^```[a-zA-Z]*\s*", "", candidate)
        candidate = re.sub(r"\s*```$", "", candidate)
    try:
        parsed = json.loads(candidate)
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        pass
    start = candidate.find("{")
    end = candidate.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(candidate[start:end + 1])
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        return None


def _fallback_summary(content: str) -> str:
    """模型没按 JSON 输出时，把正文当摘要（截断），总比丢掉强。"""
    text = (content or "").strip().replace("\n", " ")
    return text[:120]
