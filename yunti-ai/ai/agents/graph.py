"""对话 Agent 骨架：状态定义 + 节点编排。

未安装 langgraph 时返回 mock 回复；安装 requirements-ai.txt 后，
替换 build_graph 实现即可（意图识别 -> 知识检索 -> 生成 -> 情绪/转人工）。
"""

from __future__ import annotations

from typing import Any

from ..core.llm_gateway import gateway

def _try_build_graph() -> Any | None:
    try:
        from langgraph.graph import StateGraph  # noqa: F401
    except Exception:
        return None
    # TODO: 定义 ChatState + 节点（intent / retrieve / generate / escalate）
    return StateGraph(dict)

def chat_reply(messages: list[dict[str, str]]) -> str:
    graph = _try_build_graph()
    if graph is not None:
        # TODO: graph.invoke(...)
        pass
    prompt = messages[-1].get("content", "") if messages else ""
    return gateway.generate(prompt)
