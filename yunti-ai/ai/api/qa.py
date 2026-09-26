"""AI 质检评估接口。"""

from __future__ import annotations

import logging
import hmac
import time
from typing import Optional

from fastapi import APIRouter, Depends, Header, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from .bot import require_bot_tenant
from ..config import get_settings
from ..core.trace import require_trace_id
from ..services.qa import evaluate

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai/v1/qa", tags=["qa"])


def require_qa_tenant(
    authorization: str | None = Header(default=None),
    x_tenant_code: str | None = Header(default=None, alias="X-Tenant-Code"),
    x_internal_secret: str | None = Header(default=None, alias="X-Yunti-Internal-Secret"),
) -> str:
    configured = get_settings().internal_shared_secret
    if configured and x_internal_secret and hmac.compare_digest(configured, x_internal_secret):
        if not x_tenant_code or len(x_tenant_code) != 16 or x_tenant_code == "PLATFORM":
            raise HTTPException(status_code=403, detail="缺少有效租户编码")
        return x_tenant_code
    return require_bot_tenant(authorization, x_tenant_code)


class RuleSpec(BaseModel):
    """质检规则：名称 + 具体检查内容。"""

    name: str = Field(min_length=1, max_length=64)
    content: str = Field(default="", max_length=500)


class EvaluateRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    tenant_code: str = Field(min_length=16, max_length=16)
    session_name: str = Field(min_length=1, max_length=128)
    agent_name: str = Field(min_length=1, max_length=64)
    transcript: str = Field(min_length=1, max_length=20000)
    rule_names: list[str] = Field(default_factory=list, max_length=20)
    # 规则的完整信息（名称 + 检查内容），有值时优先用它构造提示词
    rules: list[RuleSpec] = Field(default_factory=list, max_length=20)
    provider: Optional[str] = None
    model: Optional[str] = None


@router.post("/evaluate")
async def evaluate_session(
    body: EvaluateRequest,
    tenant_code: str = Depends(require_qa_tenant),
    trace_id: str = Depends(require_trace_id),
) -> dict:
    started = time.perf_counter()
    if body.tenant_code != tenant_code:
        raise HTTPException(status_code=403, detail="请求租户与登录身份不一致")
    logger.info(
        "收到 AI 质检请求 trace=%s tenant=%s session=%s agent=%s rules=%d",
        trace_id, tenant_code, body.session_name, body.agent_name, len(body.rule_names),
    )
    result = await evaluate(
        tenant_code=tenant_code,
        session_name=body.session_name,
        agent_name=body.agent_name,
        transcript=body.transcript,
        rule_names=body.rule_names,
        provider=body.provider,
        model=body.model,
        rules=[item.model_dump() for item in body.rules],
    )
    logger.info(
        "AI 质检请求完成 trace=%s tenant=%s session=%s latencyMs=%d score=%s risk=%s source=%s",
        trace_id, tenant_code, body.session_name, int((time.perf_counter() - started) * 1000),
        result.get("aiScore"), result.get("riskLevel"), result.get("source"),
    )
    return {"code": 0, "message": "成功", "data": result}
