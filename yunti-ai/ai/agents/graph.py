"""客服机器人的对话入口：先查知识库，再照着资料回答（RAG）。

分工说明（很重要）：
    第 20 篇做了知识库（文档 → 切片 → 向量），第 21 篇做了"检索 + 引用溯源"，
    这个文件负责把两者**接到机器人主链路**上：客户问一句话 → 查知识库 → 带出处回答。

    意图识别、情绪识别、多轮状态、自动转人工属于下一篇《AI 客服大脑》，
    那时这里会从"单轮 RAG"升级成多节点的 LangGraph 状态机。现在先把
    "会查资料"这件事做扎实：**查不到资料就明说，不编**。
"""

from __future__ import annotations

import logging

from ..rag import graph as rag_graph

logger = logging.getLogger(__name__)


def _question_of(messages: list[dict[str, str]]) -> str:
    """取最后一条客户消息作为问题（历史消息暂时只用于日志）。"""
    for message in reversed(messages or []):
        if (message.get("role") or "user") == "user":
            return (message.get("content") or "").strip()
    return (messages[-1].get("content") if messages else "") or ""


async def chat_reply(messages: list[dict[str, str]], tenant_code: str = "",
                     session_id: str = "") -> dict:
    """生成机器人回复。

    @return {"reply": 回答正文, "citations": 引用来源, "needHuman": 是否建议转人工,
             "engine": 编排引擎, "enough": 资料是否够用}
    """
    question = _question_of(messages)
    if not question:
        return {"reply": "您好，请问有什么可以帮您？", "citations": [],
                "needHuman": False, "engine": rag_graph.engine_name(), "enough": True}

    if not tenant_code:
        # 没有租户就查不到知识库（知识是租户隔离的），明确说明而不是给个泛泛的回答
        logger.warning("机器人对话缺少租户编码 session=%s，无法查知识库", session_id or "-")
        return {"reply": "抱歉，暂时无法为您查询资料，正在为您转接人工客服。",
                "citations": [], "needHuman": True,
                "engine": rag_graph.engine_name(), "enough": False}

    result = await rag_graph.ask(question=question, tenant_code=tenant_code, top_k=5)
    citations = result.get("citations") or []
    enough = bool(result.get("enough")) and bool(citations)
    logger.info("机器人回复 session=%s 编排=%s 引用=%d 资料够用=%s",
                session_id or "-", result.get("engine"), len(citations), enough)
    return {
        "reply": result.get("answer") or "",
        "citations": citations,
        "needHuman": not enough,
        "engine": result.get("engine"),
        "enough": enough,
    }
