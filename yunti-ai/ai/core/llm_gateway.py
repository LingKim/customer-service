"""LLM 网关：统一鉴权/路由/降级/计量。当前为 mock 实现，接入点已预留。"""

from __future__ import annotations

from typing import Protocol

from ..config import get_settings

class ChatModel(Protocol):
    def generate(self, prompt: str) -> str: ...

class MockModel:
    def generate(self, prompt: str) -> str:
        return f"[mock] 已收到你的消息：{prompt[:60]}（接入大模型后由 LLM 网关真实生成）"

class LLMGateway:
    """统一入口：provider 路由、超时降级、token 计量（后续实现）。"""

    def __init__(self) -> None:
        settings = get_settings()
        self.provider = settings.llm_default_provider
        self.model = settings.llm_default_model
        self._model: ChatModel = MockModel()
        if self.provider != "mock":
            # TODO: 按 provider 初始化 OpenAI/千问/DeepSeek 客户端
            pass

    def generate(self, prompt: str) -> str:
        # TODO: 流式（SSE）、成本计量、失败降级
        return self._model.generate(prompt)

gateway = LLMGateway()
