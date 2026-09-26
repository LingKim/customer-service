"""Tenant scoped visual model selection and OpenAI compatible image requests."""

from __future__ import annotations

import logging
from typing import Any

import httpx

from ..config import get_settings
from .db import connect
from .llm_chat import message_content, provider_conf

logger = logging.getLogger(__name__)


def vision_provider(tenant_code: str = "") -> tuple[dict[str, str] | None, str]:
    settings = get_settings()
    if not settings.qwen_api_key:
        return None, "未配置千问密钥（YUNTI_AI_QWEN_API_KEY）"
    tenant_model = _tenant_vision_model(tenant_code)
    model = tenant_model or settings.qwen_vl_model
    return provider_conf("qwen", settings.qwen_api_key, settings.qwen_base_url, model), model


def _tenant_vision_model(tenant_code: str) -> str | None:
    if not tenant_code:
        return None
    try:
        with connect() as conn:
            row = conn.execute(
                """SELECT m.model_key FROM bot_setting s
                     JOIN bot_model m ON m.tenant_code = s.tenant_code
                    WHERE s.tenant_code = %s AND s.is_deleted = FALSE
                      AND m.model_key = s.model_key AND m.model_type = 2
                      AND m.is_enabled = TRUE AND m.is_deleted = FALSE
                    LIMIT 1""",
                (tenant_code,),
            ).fetchone()
        return str(row["model_key"]) if row else None
    except Exception as exc:  # noqa: BLE001
        logger.warning("读取租户视觉模型失败 tenant=%s reason=%s", tenant_code, exc)
        return None


async def call_vision(chosen: dict[str, str], prompt: str, images: list[str], timeout: int,
                      *, scene: str = "图片识别") -> dict[str, Any]:
    settings = get_settings()
    if not images or len(images) > settings.vision_max_images:
        raise ValueError(f"一次需上传 1 至 {settings.vision_max_images} 张图片")
    limit = settings.vision_max_image_mb * 1024 * 1024
    if any(len(image) > limit or not image.startswith("data:image/") for image in images):
        raise ValueError("图片格式不正确或超出大小限制")
    content: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
    content.extend({"type": "image_url", "image_url": {"url": image}} for image in images)
    payload = {"model": chosen["model"], "temperature": 0.1,
               "messages": [{"role": "user", "content": content}]}
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(
            f"{chosen['base_url']}/chat/completions",
            headers={"Authorization": f"Bearer {chosen['api_key']}"}, json=payload,
        )
        response.raise_for_status()
        return response.json()
