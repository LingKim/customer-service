"""质检服务通过 OpenAI 兼容接口选择厂商并解析结构化回复。"""

import asyncio
from types import SimpleNamespace

import pytest

from ai.services import qa


@pytest.mark.parametrize("provider,expected", [("qwen", "llm-qwen"), ("deepseek", "llm-deepseek")])
def test_provider_selection_and_structured_result(monkeypatch, provider: str, expected: str) -> None:
    settings = SimpleNamespace(
        llm_default_provider="mock", llm_default_model="", llm_timeout=5,
        llm_log_payload=False,
        qwen_api_key="qwen-test-key", qwen_base_url="https://example.test/qwen", qwen_model="qwen-plus",
        deepseek_api_key="deepseek-test-key", deepseek_base_url="https://example.test/deepseek",
        deepseek_model="deepseek-chat",
    )
    monkeypatch.setattr(qa, "get_settings", lambda: settings)

    async def fake_chat(chosen, payload, timeout):
        assert chosen["provider"] == provider
        assert payload["messages"][1]["content"].find("真实对话") >= 0
        assert timeout == 5
        return {
            "choices": [{"message": {"content": '{"aiScore": 76, "rel": 2, "rules": ["必答项完整"], "comment": "需要补问"}'}}],
            "usage": {"total_tokens": 12},
        }

    monkeypatch.setattr(qa, "_call_chat", fake_chat)
    result = asyncio.run(qa.evaluate(
        tenant_code="T000000000000001", session_name="会话", agent_name="客服",
        transcript="真实对话", rule_names=["必答项完整"], provider=provider,
    ))
    assert result == {
        "aiScore": 76, "riskLevel": 2, "rules": ["必答项完整"],
        "comment": "需要补问", "source": expected,
    }
