"""实时对话接口：POST /api/ai/v1/agent/chat。

对接后端 customer-service；后续演进为 SSE 流式（LangGraph 输出逐字返回）。
"""

from __future__ import annotations

from typing import List

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from ..agents.graph import chat_reply
from ..core.tenant import get_tenant_code, require_tenant_code

router = APIRouter()

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
    messages = [m.model_dump() for m in payload.messages]
    reply = chat_reply(messages)
    return {
        "code": 0,
        "message": "成功",
        "data": {
            "session_id": payload.session_id,
            "tenant_code": get_tenant_code(),
            "reply": reply,
            "stream": payload.stream,
        },
    }
