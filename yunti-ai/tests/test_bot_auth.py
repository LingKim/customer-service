"""机器人配置入口必须同时核对签名身份和租户头。"""

from datetime import datetime, timedelta, timezone

import jwt
from fastapi.testclient import TestClient

from ai.config import get_settings
from ai.main import app


def token_for(tenant: str, user_type: int = 2) -> str:
    return jwt.encode(
        {
            "sub": "123", "type": user_type, "tnt": tenant,
            "exp": datetime.now(timezone.utc) + timedelta(minutes=5),
        },
        get_settings().jwt_secret,
        algorithm="HS256",
    )


def test_bot_rejects_missing_bearer() -> None:
    response = TestClient(app).get("/api/ai/v1/bot/intents", headers={"X-Tenant-Code": "T123"})
    assert response.status_code == 401


def test_bot_rejects_cross_tenant_header() -> None:
    response = TestClient(app).get(
        "/api/ai/v1/bot/intents",
        headers={"Authorization": f"Bearer {token_for('T123')}", "X-Tenant-Code": "T456"},
    )
    assert response.status_code == 403


def test_bot_rejects_platform_identity() -> None:
    response = TestClient(app).get(
        "/api/ai/v1/bot/intents",
        headers={"Authorization": f"Bearer {token_for('PLATFORM', 1)}", "X-Tenant-Code": "PLATFORM"},
    )
    assert response.status_code == 403
