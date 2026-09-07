from fastapi.testclient import TestClient

from ai.main import app

client = TestClient(app)

def test_health() -> None:
    resp = client.get("/api/ai/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"
