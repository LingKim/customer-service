"""租户级机器人设置、意图和模型配置。"""

from __future__ import annotations

from datetime import datetime
from decimal import Decimal
import secrets
from typing import Any

from fastapi import APIRouter, Depends, Header, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from ..config import get_settings
from ..core.db import connect
from .kb import require_kb_secret, require_tenant_header
from ..services import brain as brain_service

router = APIRouter(prefix="/ai/v1/bot", tags=["bot"])

DEFAULT_INTENTS = (
    ("IT0001", "查询物流", "我的快递到哪了\n帮我查一下订单物流"),
    ("IT0002", "退款售后", "我要退款\n怎么申请售后"),
    ("IT0003", "价格优惠", "现在有什么优惠\n有没有优惠券"),
    ("IT0004", "人工客服", "转人工\n找人工客服"),
    ("IT0005", "开票服务", "怎么开发票\n发票抬头怎么填"),
)
DEFAULT_MODELS = (
    ("qwen-plus", "千问 Plus", "qwen", 1, 0.35),
    ("deepseek-flash", "DeepSeek-V4.1-Flash", "deepseek", 1, 0.30),
    ("deepseek-chat", "DeepSeek Chat", "deepseek", 1, 0.30),
    ("qwen-vl-plus", "千问 VL", "qwen", 2, 0.20),
)


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class IntentCreate(ApiModel):
    name: str = Field(min_length=1, max_length=64)
    samples: str | None = None
    escalate: bool = False


class IntentUpdate(ApiModel):
    name: str | None = Field(default=None, min_length=1, max_length=64)
    samples: str | None = None
    status: int | None = Field(default=None, ge=1, le=2)
    escalate: bool | None = None


class SettingUpdate(ApiModel):
    bot_name: str = Field(min_length=1, max_length=64)
    welcome_message: str | None = Field(default=None, max_length=255)
    fallback_message: str | None = Field(default=None, max_length=255)
    transfer_prompt: str | None = Field(default=None, max_length=255)
    transfer_message: str | None = Field(default=None, max_length=255)
    is_enabled: bool = True
    reception_enabled: bool = True
    transfer_on_anger: bool = True
    transfer_after_unresolved: int = Field(default=2, ge=0, le=10)
    transfer_keywords: str | None = Field(default=None, max_length=255)


class ModelUpdate(ApiModel):
    model_key: str = Field(min_length=1, max_length=64)
    temperature: float = Field(ge=0, le=2)


def require_bot_tenant(
    authorization: str | None = Header(default=None),
    x_tenant_code: str | None = Header(default=None, alias="X-Tenant-Code"),
) -> str:
    import jwt

    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="缺少登录令牌")
    try:
        claims = jwt.decode(
            authorization[7:].strip(), get_settings().jwt_secret,
            algorithms=["HS256", "HS384", "HS512"], options={"require": ["exp", "sub"]},
        )
    except jwt.InvalidTokenError as exc:
        raise HTTPException(status_code=401, detail="登录令牌无效") from exc
    tenant = claims.get("tnt")
    if claims.get("type") != 2 or not tenant or tenant == "PLATFORM" or tenant != x_tenant_code:
        raise HTTPException(status_code=403, detail="租户身份不匹配")
    return str(tenant)


def ok(data: Any = None) -> dict[str, Any]:
    return {"code": 0, "message": "成功", "data": data}


def new_id() -> int:
    return secrets.randbits(63) or 1


def clean(row: dict[str, Any] | None) -> dict[str, Any] | None:
    if row is None:
        return None
    result: dict[str, Any] = {}
    for key, value in row.items():
        output_key = to_camel(key)
        if key == "id" and value is not None:
            result[output_key] = str(value)
        elif isinstance(value, Decimal):
            result[output_key] = float(value)
        elif isinstance(value, datetime):
            result[output_key] = value.isoformat()
        else:
            result[output_key] = value
    return result


def ensure_setting(conn: Any, tenant: str) -> None:
    conn.execute(
        """INSERT INTO bot_setting
             (id, tenant_code, bot_name, welcome_message, fallback_message,
              transfer_prompt, transfer_message, transfer_keywords,
              is_enabled, model_key, temperature)
           VALUES (%s, %s, '小云', %s, %s, %s, %s, %s, TRUE, 'qwen-plus', 0.35)
           ON CONFLICT (tenant_code) DO NOTHING""",
        (
            new_id(), tenant,
            "您好，我是云梯智能客服小云，很高兴为您服务。",
            "抱歉，我暂时无法准确回答您的问题，请尝试联系人工客服。",
            "如需人工客服，请回复“转人工”。",
            "好的，正在为您转接人工客服，请稍候。",
            "转人工,人工客服,找人工,要人工,人工",
        ),
    )


@router.get("/intents")
def list_intents(tenant: str = Depends(require_bot_tenant)) -> dict[str, Any]:
    with connect() as conn:
        for code, name, samples in DEFAULT_INTENTS:
            conn.execute(
                """INSERT INTO bot_intent (id, tenant_code, intent_code, name, samples, escalate)
                   VALUES (%s, %s, %s, %s, %s, %s)
                   ON CONFLICT (tenant_code, intent_code) DO NOTHING""",
                (new_id(), tenant, code, name, samples, name == "人工客服"),
            )
        rows = conn.execute(
            """SELECT id, intent_code, name, status, confidence, hit_count, samples, escalate, update_time
                 FROM bot_intent WHERE tenant_code = %s AND is_deleted = FALSE
                ORDER BY create_time DESC, id DESC""",
            (tenant,),
        ).fetchall()
    return ok([clean(row) for row in rows])


@router.post("/intents")
def create_intent(body: IntentCreate, tenant: str = Depends(require_bot_tenant)) -> dict[str, Any]:
    intent_code = "IT" + secrets.token_hex(12).upper()
    with connect() as conn:
        row = conn.execute(
            """INSERT INTO bot_intent (id, tenant_code, intent_code, name, samples, escalate)
               VALUES (%s, %s, %s, %s, %s, %s)
               RETURNING id, intent_code, name, status, confidence, hit_count, samples, escalate, update_time""",
            (new_id(), tenant, intent_code, body.name.strip(), body.samples, body.escalate),
        ).fetchone()
    return ok(clean(row))


@router.put("/intents/{intent_id}")
def update_intent(intent_id: int, body: IntentUpdate, tenant: str = Depends(require_bot_tenant)) -> dict[str, Any]:
    changes: list[str] = []
    values: list[Any] = []
    if body.name is not None:
        changes.append("name = %s")
        values.append(body.name.strip())
    if body.samples is not None:
        changes.append("samples = %s")
        values.append(body.samples)
    if body.status is not None:
        changes.append("status = %s")
        values.append(body.status)
    if body.escalate is not None:
        changes.append("escalate = %s")
        values.append(body.escalate)
    if not changes:
        return ok()
    values.extend((intent_id, tenant))
    with connect() as conn:
        row = conn.execute(
            f"UPDATE bot_intent SET {', '.join(changes)}, update_time = CURRENT_TIMESTAMP "
            "WHERE id = %s AND tenant_code = %s AND is_deleted = FALSE RETURNING id",
            values,
        ).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail="意图不存在")
    return ok()


@router.get("/settings")
def get_setting(tenant: str = Depends(require_bot_tenant)) -> dict[str, Any]:
    with connect() as conn:
        ensure_setting(conn, tenant)
        row = conn.execute(
            """SELECT id, bot_name, welcome_message, fallback_message, transfer_prompt,
                      transfer_message, transfer_keywords, reception_enabled,
                      transfer_on_anger, transfer_after_unresolved,
                      is_enabled, model_key, temperature, update_time
                 FROM bot_setting WHERE tenant_code = %s AND is_deleted = FALSE""",
            (tenant,),
        ).fetchone()
    return ok(clean(row))


@router.put("/settings")
def update_setting(body: SettingUpdate, tenant: str = Depends(require_bot_tenant)) -> dict[str, Any]:
    with connect() as conn:
        ensure_setting(conn, tenant)
        row = conn.execute(
            """UPDATE bot_setting
                  SET bot_name = %s, welcome_message = %s, fallback_message = %s,
                      transfer_prompt = %s, transfer_message = %s, transfer_keywords = %s,
                      reception_enabled = %s, transfer_on_anger = %s,
                      transfer_after_unresolved = %s, is_enabled = %s,
                      update_time = CURRENT_TIMESTAMP
                WHERE tenant_code = %s AND is_deleted = FALSE
                RETURNING id, bot_name, welcome_message, fallback_message,
                          transfer_prompt, transfer_message, transfer_keywords,
                          reception_enabled, transfer_on_anger, transfer_after_unresolved,
                          is_enabled, model_key, temperature, update_time""",
            (body.bot_name.strip(), body.welcome_message, body.fallback_message,
             body.transfer_prompt, body.transfer_message, body.transfer_keywords,
             body.reception_enabled, body.transfer_on_anger,
             body.transfer_after_unresolved, body.is_enabled, tenant),
        ).fetchone()
    return ok(clean(row))


@router.get("/models")
def list_models(tenant: str = Depends(require_bot_tenant)) -> dict[str, Any]:
    with connect() as conn:
        for key, name, provider, model_type, temperature in DEFAULT_MODELS:
            conn.execute(
                """INSERT INTO bot_model
                     (id, tenant_code, model_key, model_name, provider, model_type, temperature)
                   VALUES (%s, %s, %s, %s, %s, %s, %s)
                   ON CONFLICT (tenant_code, model_key) DO NOTHING""",
                (new_id(), tenant, key, name, provider, model_type, temperature),
            )
        rows = conn.execute(
            """SELECT id, model_key, model_name, provider, model_type, temperature, is_enabled
                 FROM bot_model WHERE tenant_code = %s AND is_deleted = FALSE
                ORDER BY model_type, model_key""",
            (tenant,),
        ).fetchall()
    return ok([clean(row) for row in rows])


@router.put("/model")
def update_model(body: ModelUpdate, tenant: str = Depends(require_bot_tenant)) -> dict[str, Any]:
    with connect() as conn:
        model = conn.execute(
            """SELECT id FROM bot_model
                WHERE tenant_code = %s AND model_key = %s AND is_enabled = TRUE AND is_deleted = FALSE""",
            (tenant, body.model_key),
        ).fetchone()
        if model is None:
            raise HTTPException(status_code=400, detail="模型不可用")
        ensure_setting(conn, tenant)
        conn.execute(
            """UPDATE bot_model SET temperature = %s, update_time = CURRENT_TIMESTAMP
                WHERE id = %s AND tenant_code = %s""",
            (body.temperature, model["id"], tenant),
        )
        conn.execute(
            """UPDATE bot_setting SET model_key = %s, temperature = %s,
                      update_time = CURRENT_TIMESTAMP
                WHERE tenant_code = %s AND is_deleted = FALSE""",
            (body.model_key, body.temperature, tenant),
        )
    return ok()


@router.get("/dialogues")
def list_dialogues(limit: int = 50, tenant: str = Depends(require_bot_tenant)) -> dict[str, Any]:
    return ok([clean(item) for item in brain_service.list_dialogues(tenant, limit)])


@router.get("/intent-stats")
def intent_stats(tenant: str = Depends(require_bot_tenant)) -> dict[str, Any]:
    with connect() as conn:
        total = conn.execute(
            """SELECT COALESCE(SUM(turn_count), 0) AS turns,
                      COUNT(1) AS sessions,
                      COALESCE(SUM(CASE WHEN transferred THEN 1 ELSE 0 END), 0) AS transferred
                 FROM bot_dialogue WHERE tenant_code = %s AND is_deleted = FALSE""",
            (tenant,),
        ).fetchone()
        by_intent = conn.execute(
            """SELECT COALESCE(last_intent, '其他') AS intent, COUNT(1) AS sessions,
                      COALESCE(SUM(CASE WHEN transferred THEN 1 ELSE 0 END), 0) AS transferred
                 FROM bot_dialogue WHERE tenant_code = %s AND is_deleted = FALSE
                GROUP BY COALESCE(last_intent, '其他') ORDER BY sessions DESC""",
            (tenant,),
        ).fetchall()
        by_emotion = conn.execute(
            """SELECT COALESCE(last_emotion, '中性') AS emotion, COUNT(1) AS sessions
                 FROM bot_dialogue WHERE tenant_code = %s AND is_deleted = FALSE
                GROUP BY COALESCE(last_emotion, '中性') ORDER BY sessions DESC""",
            (tenant,),
        ).fetchall()
    return ok({"totalTurns": int(total["turns"]), "totalSessions": int(total["sessions"]),
               "transferredSessions": int(total["transferred"]),
               "byIntent": [clean(item) for item in by_intent],
               "byEmotion": [clean(item) for item in by_emotion]})


@router.get("/internal/profile", dependencies=[Depends(require_kb_secret)])
def internal_profile(request: Request, tenant_code: str) -> dict[str, Any]:
    require_tenant_header(request, tenant_code)
    with connect() as conn:
        row = conn.execute(
            """SELECT bot_name, welcome_message, reception_enabled
                 FROM bot_setting WHERE tenant_code = %s AND is_deleted = FALSE""",
            (tenant_code,),
        ).fetchone()
    return ok(clean(row))
