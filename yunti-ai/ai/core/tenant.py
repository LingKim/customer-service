"""租户上下文：请求头 X-Tenant-Code 透传与校验。"""

from __future__ import annotations

from contextvars import ContextVar

from fastapi import Header, HTTPException

TENANT_CODE_HEADER = "X-Tenant-Code"

_tenant_code: ContextVar[str] = ContextVar("tenant_code", default="")

def set_tenant_code(code: str) -> None:
    _tenant_code.set(code)

def get_tenant_code() -> str:
    return _tenant_code.get()

async def require_tenant_code(
    x_tenant_code: str | None = Header(default=None, alias=TENANT_CODE_HEADER),
) -> str:
    """依赖注入：解析并校验租户上下文（对接多租户隔离方案）。"""
    if not x_tenant_code:
        raise HTTPException(status_code=400, detail="缺少 X-Tenant-Code 请求头")
    set_tenant_code(x_tenant_code)
    return x_tenant_code
