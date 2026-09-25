"""质检入口需要租户身份，并明确标记规则兜底来源。"""

from datetime import datetime, timedelta, timezone

import jwt
from fastapi.testclient import TestClient

from ai.config import get_settings
from ai.main import app
from ai.services import qa


TENANT = "T000000000000001"
OTHER = "T000000000000002"
BODY = {
    "tenantCode": TENANT,
    "sessionName": "演示会话",
    "agentName": "客服",
    "transcript": "客服说随便你，客户很不满意",
    "ruleNames": ["敏感词与禁语"],
}


def bearer(tenant: str) -> str:
    token = jwt.encode(
        {"sub": "123", "type": 2, "tnt": tenant,
         "exp": datetime.now(timezone.utc) + timedelta(minutes=5)},
        get_settings().jwt_secret,
        algorithm="HS256",
    )
    return f"Bearer {token}"


def test_qa_requires_signed_tenant_identity() -> None:
    response = TestClient(app).post(
        "/api/ai/v1/qa/evaluate", json=BODY,
        headers={"X-Tenant-Code": TENANT},
    )
    assert response.status_code == 401


def test_qa_rejects_header_and_body_tenant_mismatch() -> None:
    client = TestClient(app)
    header_mismatch = client.post(
        "/api/ai/v1/qa/evaluate", json=BODY,
        headers={"Authorization": bearer(TENANT), "X-Tenant-Code": OTHER},
    )
    assert header_mismatch.status_code == 403
    body_mismatch = client.post(
        "/api/ai/v1/qa/evaluate", json={**BODY, "tenantCode": OTHER},
        headers={"Authorization": bearer(TENANT), "X-Tenant-Code": TENANT},
    )
    assert body_mismatch.status_code == 403


def test_qa_fallback_checks_transcript_and_marks_source(monkeypatch) -> None:
    monkeypatch.setattr(qa, "_tenant_model", lambda _tenant: (None, None))
    monkeypatch.setattr(qa, "_choose_provider", lambda _provider, _model: None)
    response = TestClient(app).post(
        "/api/ai/v1/qa/evaluate", json=BODY,
        headers={"Authorization": bearer(TENANT), "X-Tenant-Code": TENANT},
    )
    assert response.status_code == 200
    data = response.json()["data"]
    assert data["source"] == "fallback-rule"
    assert data["rules"] == ["敏感词与禁语"]
