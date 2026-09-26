"""AI 客服大脑接口：一句话进去，意图 / 情绪 / 回复 / 是否转人工一起出来。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/ai/v1/agent/brain | 机器人接待一轮：识别 + 回复 + 转人工决策 |
| GET | /api/ai/v1/agent/brain/health | 编排探活 + 规则/密钥体检 |

调用方是 customer-service（浏览器不直连 AI 服务）：
客户发一条消息 → Java 落库 → 调这里拿机器人的回复与转人工信号 → Java 落库并推送。

和第 21 篇的 /agent/chat 的区别：那个只回答"知识库怎么答"，这个还要回答
"客户想干什么、情绪怎么样、该不该转人工、这是第几轮了"——也就是"客服大脑"。
"""

from __future__ import annotations

import logging
import time
from typing import List

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field

from ..agents import brain as brain_agent
from ..core.schema_guard import describe as schema_state
from ..core.trace import require_trace_id
from ..services import brain as brain_service
from .kb import require_kb_secret, require_tenant_header

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai/v1/agent", tags=["agent"], dependencies=[Depends(require_kb_secret)])


class Message(BaseModel):
    role: str
    content: str


class BrainRequest(BaseModel):
    session_id: str = Field(default="", max_length=64)
    tenant_code: str = Field(min_length=1, max_length=32)
    messages: List[Message] = Field(default_factory=list)
    top_k: int = Field(default=5, ge=1, le=20)


@router.post("/brain")
async def brain(request: Request, body: BrainRequest, trace_id: str = Depends(require_trace_id)) -> dict:
    require_tenant_header(request, body.tenant_code)
    messages = [m.model_dump() for m in body.messages]
    question = ""
    for message in reversed(messages):
        if (message.get("role") or "user") == "user" and (message.get("content") or "").strip():
            question = (message.get("content") or "").strip()
            break
    if not question:
        raise HTTPException(status_code=400, detail="没有可处理的客户消息")

    started = time.perf_counter()
    logger.info("收到客服大脑请求 trace=%s tenant=%s session=%s 历史=%d 问题=%s",
                trace_id, body.tenant_code, body.session_id or "-", len(messages), question[:60])
    try:
        result = await brain_agent.think(
            question=question,
            tenant_code=body.tenant_code,
            session_no=body.session_id,
            history=messages[:-1] if messages else [],
            top_k=body.top_k,
        )
    except Exception as exc:  # noqa: BLE001
        logger.exception("客服大脑处理失败 trace=%s：%s", trace_id, exc)
        raise HTTPException(status_code=500, detail=f"客服大脑处理失败：{exc}") from exc
    logger.info("客服大脑完成 trace=%s 意图=%s 置信度=%.2f 情绪=%s 转人工=%s 原因=%s cost=%dms",
                trace_id, result.get("intent"), float(result.get("confidence") or 0),
                result.get("emotion"), result.get("need_human"),
                result.get("transfer_reason") or "-",
                int((time.perf_counter() - started) * 1000))
    return result


@router.get("/brain/health")
def brain_health(request: Request, tenant_code: str | None = None) -> dict:
    """编排探活 + 体检：规则引擎有没有就绪、模型密钥配没配。

    和知识问答的体检一个口径：只报"有没有"，绝不回显密钥本身。
    带上 `?tenant_code=T...` 时会额外回答"这个租户最终会调哪家的哪个模型"——
    "我明明选了 DeepSeek，怎么还报千问 404"这类问题看这一项就够了。
    """
    from ..config import get_settings
    from ..core.llm_chat import describe_effective

    if tenant_code:
        require_tenant_header(request, tenant_code)

    settings = get_settings()
    llm_key = settings.qwen_api_key or settings.deepseek_api_key
    result = {
        "status": "UP",
        "engine": brain_agent.engine_name(),
        # 规则能力是"保底"：没密钥也能识别意图/情绪，只是没有模型那么灵活
        "ruleIntent": True,
        "ruleEmotion": True,
        "emotionLabels": [brain_service.EMOTION_NEUTRAL, brain_service.EMOTION_ANXIOUS,
                          brain_service.EMOTION_UPSET, brain_service.EMOTION_ANGRY],
        "llmKeyConfigured": bool(llm_key),
        # 库结构自检：缺 bot_dialogue / 接待策略字段时这里会直接写出来要跑哪个脚本
        "dialogStateTable": schema_state(),
        "llmProvider": "qwen" if settings.qwen_api_key else (
            "deepseek" if settings.deepseek_api_key else "none"),
        "intentRuleMinScore": brain_service.INTENT_RULE_MIN_SCORE,
        "hint": "" if llm_key else
                "没配大模型密钥：意图/情绪仍由规则判断（够用但不如模型灵活），"
                "回复会退化成知识库原文。配 YUNTI_AI_QWEN_API_KEY 后体验完整。",
    }
    if tenant_code:
        # 租户级：库里选的模型 + 实际会用的模型 + 两者不一致时的原因
        result["effective"] = describe_effective(tenant_code)
    return result
