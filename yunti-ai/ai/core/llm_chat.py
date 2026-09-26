"""大模型对话的统一入口：挑供应商 → 调 /chat/completions → 租户级模型配置。

为什么单独抽一层：AI 质检（第 14 篇）和知识问答（第 21 篇）都要"按租户配置的模型
调一次对话接口"。这套逻辑（选厂商、拼请求、记日志、读租户配置）跟业务无关，
放在业务里各写一份，迟早会出现"质检支持千问、问答忘了加"这种不一致。
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any

import httpx

from ..config import get_settings
from .db import connect
from .trace import get_trace_id

logger = logging.getLogger(__name__)


def provider_conf(name: str, api_key: str, base_url: str, model: str) -> dict[str, str]:
    return {
        "provider": name,
        "api_key": api_key,
        "base_url": (base_url or "").rstrip("/"),
        "model": model,
    }


def choose_provider(provider: str | None, model: str | None) -> dict[str, str] | None:
    """挑选真实可用的模型供应商。

    优先级：显式指定 > 全局默认配置 > 自动选择已配置密钥的厂商（千问优先）。
    全部没配密钥时返回 None，调用方走各自的兜底（质检用规则、问答给检索原文）。
    """
    settings = get_settings()
    requested = (provider or settings.llm_default_provider or "").lower()
    if "deepseek" in requested:
        return provider_conf("deepseek", settings.deepseek_api_key, settings.deepseek_base_url,
                             model or settings.deepseek_model)
    if "qwen" in requested or "dashscope" in requested:
        return provider_conf("qwen", settings.qwen_api_key, settings.qwen_base_url,
                             model or settings.qwen_model)
    if settings.qwen_api_key:
        logger.info("未指定真实模型供应商（provider=%s），自动使用千问 model=%s",
                    requested or "空", model or settings.qwen_model)
        return provider_conf("qwen", settings.qwen_api_key, settings.qwen_base_url,
                             model or settings.qwen_model)
    if settings.deepseek_api_key:
        logger.info("未指定真实模型供应商（provider=%s），自动使用 DeepSeek model=%s",
                    requested or "空", model or settings.deepseek_model)
        return provider_conf("deepseek", settings.deepseek_api_key, settings.deepseek_base_url,
                             model or settings.deepseek_model)
    return None


async def call_chat(chosen: dict[str, str], payload: dict[str, Any], timeout: int,
                    *, scene: str = "对话") -> dict[str, Any]:
    """调用 OpenAI 兼容的 /chat/completions；入参出参按需打印（排障用）。"""
    trace = get_trace_id() or "-"
    if get_settings().llm_log_payload:
        logger.info("%s模型入参 trace=%s provider=%s model=%s payload=%s",
                    scene, trace, chosen["provider"], chosen["model"],
                    json.dumps(payload, ensure_ascii=False))
    headers = {"Authorization": f"Bearer {chosen['api_key']}", "Content-Type": "application/json"}
    started = time.perf_counter()
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(f"{chosen['base_url']}/chat/completions",
                                     headers=headers, json=payload)
        response.raise_for_status()
        data = response.json()
    if get_settings().llm_log_payload:
        logger.info("%s模型出参 trace=%s provider=%s cost=%dms body=%s",
                    scene, trace, chosen["provider"],
                    int((time.perf_counter() - started) * 1000),
                    json.dumps(data, ensure_ascii=False)[:2000])
    return data


def message_content(data: dict[str, Any]) -> str:
    """从响应里取正文（兼容个别厂商把字段名写错的情况）。"""
    choices = data.get("choices") or []
    if not choices:
        return ""
    message = choices[0].get("message") or {}
    return str(message.get("content") or "").strip()


# 租户模型配置缓存：同一租户短时间内不重复查库
_TENANT_MODEL_TTL = 60.0
_tenant_model_cache: dict[str, tuple[float, dict[str, str] | None]] = {}


def tenant_model(tenant_code: str) -> dict[str, str] | None:
    """读取租户在「智能机器人 → 模型选择」里配置的模型。

    读取失败（未配置 / 库不可用）返回 None，由调用方决定用全局默认还是走兜底。
    """
    if not tenant_code:
        return None
    now = time.time()
    cached = _tenant_model_cache.get(tenant_code)
    if cached and now - cached[0] < _TENANT_MODEL_TTL:
        return cached[1]
    result: dict[str, str] | None = None
    try:
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT m.provider, m.model_key
                  FROM bot_setting s
                  JOIN bot_model m ON m.tenant_code = s.tenant_code
                                  AND m.model_key = s.model_key
                 WHERE s.tenant_code = %s AND s.is_deleted = FALSE
                   AND m.is_deleted = FALSE AND m.is_enabled = TRUE
                 LIMIT 1
                """,
                (tenant_code,),
            )
            row = cur.fetchone()
            if row and (row.get("provider") or row.get("model_key")):
                result = {
                    "provider": str(row.get("provider") or ""),
                    "model": str(row.get("model_key") or ""),
                }
    except Exception as exc:  # noqa: BLE001
        logger.debug("读取租户模型配置失败 tenant=%s：%s", tenant_code, exc)
    _tenant_model_cache[tenant_code] = (now, result)
    return result


def resolve_provider(tenant_code: str, provider: str | None = None,
                     model: str | None = None) -> tuple[dict[str, str] | None, str]:
    """综合"租户配置 + 显式指定 + 全局默认"挑一个可用供应商。

    @return (供应商配置, 说明) —— 说明用于日志和接口返回，讲清这次用的是谁
    """
    tenant = tenant_model(tenant_code) or {}
    chosen = choose_provider(provider or tenant.get("provider"),
                             model or tenant.get("model"))
    if chosen is None or not chosen.get("api_key"):
        return None, "未配置任何大模型密钥"
    return chosen, f"{chosen['provider']}:{chosen['model']}"
