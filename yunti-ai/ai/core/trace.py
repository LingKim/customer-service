"""调用链追踪：透传 X-Request-Id，让 Java 调用方与 AI 服务日志能对上同一次调用。"""

from __future__ import annotations

import uuid
from contextvars import ContextVar

from fastapi import Header

TRACE_ID_HEADER = "X-Request-Id"

_trace_id: ContextVar[str] = ContextVar("trace_id", default="")


def set_trace_id(trace_id: str) -> None:
    _trace_id.set(trace_id)


def get_trace_id() -> str:
    return _trace_id.get()


def new_trace_id() -> str:
    """调用方没带请求号时自行生成，保证日志里始终有 trace 可查。"""
    return uuid.uuid4().hex[:16]


async def require_trace_id(
    x_request_id: str | None = Header(default=None, alias=TRACE_ID_HEADER),
) -> str:
    """依赖注入：解析调用方请求号（缺省自动生成）。"""
    trace_id = (x_request_id or "").strip() or new_trace_id()
    set_trace_id(trace_id)
    return trace_id
