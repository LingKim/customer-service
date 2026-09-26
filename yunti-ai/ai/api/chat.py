"""实时对话接口：POST /api/ai/v1/agent/chat。

机器人回复不再是 mock：走"查知识库 → 带出处回答"（见 agents/graph.py）。
查不到资料时返回 need_human=true，由业务侧决定是否转人工。
后续演进为 SSE 流式（LangGraph 输出逐字返回）。
"""

from __future__ import annotations

import logging
import time
from typing import List

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from ..agents.graph import chat_reply
from ..core.tenant import get_tenant_code, require_tenant_code

router = APIRouter()
logger = logging.getLogger(__name__)

class Message(BaseModel):
    role: str
    content: str

class ChatRequest(BaseModel):
    session_id: str = Field(default="")
    messages: List[Message] = Field(default_factory=list)
    stream: bool = False

@router.post("/ai/v1/agent/chat")
async def chat(
    payload: ChatRequest,
    _tenant: str = Depends(require_tenant_code),
) -> dict[str, object]:
    started = time.perf_counter()
    messages = [m.model_dump() for m in payload.messages]
    result = await chat_reply(messages, tenant_code=get_tenant_code(), session_id=payload.session_id)
    reply = result.get("reply") or ""
    logger.info(
        "智能客服对话请求完成 tenant=%s session=%s latencyMs=%d replyChars=%d 引用=%d 需要转人工=%s 编排=%s",
        get_tenant_code(), payload.session_id,
        int((time.perf_counter() - started) * 1000), len(reply),
        len(result.get("citations") or []), result.get("needHuman"), result.get("engine"),
    )
    return {
        "code": 0,
        "message": "成功",
        "data": {
            "session_id": payload.session_id,
            "tenant_code": get_tenant_code(),
            "reply": reply,
            # 回答的依据（前端可以展示"这条回复来自哪份文档"）
            "citations": result.get("citations") or [],
            # 知识库里查不到时建议转人工——下一篇的自动转人工会用到这个信号
            "need_human": result.get("needHuman"),
            "engine": result.get("engine"),
            "stream": payload.stream,
        },
    }
