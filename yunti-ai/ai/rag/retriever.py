"""知识检索入口：给对话机器人做 RAG 召回用。

这一层薄薄包一下 pipeline.search，好处是**调用方不用知道底层换了什么**：
今天是 pgvector，明天换成 Milvus 或者加上重排（rerank），这里改一行就行。

用法：
    from ai.rag.retriever import retrieve
    hits = retrieve("退款多久到账", tenant_code="T2026...", top_k=3)
"""

from __future__ import annotations

import logging
from typing import Any

from . import pipeline

logger = logging.getLogger(__name__)


def retrieve(query: str, tenant_code: str = "", top_k: int = 3) -> list[dict[str, Any]]:
    """检索知识切片；没配租户或没建索引时返回空列表（调用方按"没查到"处理即可）。"""
    return retrieve_with_meta(query, tenant_code, top_k).get("results", [])


def retrieve_with_meta(query: str, tenant_code: str = "", top_k: int = 3,
                       doc_ids: list[int] | None = None) -> dict[str, Any]:
    """检索并带上"这次是怎么检的"（口径、向量来源）。

    知识问答的编排节点用的就是它——编排需要知道检索口径才能判断"召回够不够"
    （向量看相似度、关键词看命中占比，两者阈值不同）。
    检索失败不让调用方崩：返回空结果 + 原因，机器人退化成"没有知识可用"。
    """
    if not query or not tenant_code:
        return {"query": query, "results": [], "mode": "empty", "vector_source": "none"}
    try:
        return pipeline.search(tenant_code=tenant_code, query=query, top_k=top_k, doc_ids=doc_ids)
    except Exception as exc:  # noqa: BLE001
        logger.warning("知识检索失败 tenant=%s query=%s error=%s: %s",
                       tenant_code, query[:40], type(exc).__name__, exc)
        return {"query": query, "results": [], "mode": "error", "vector_source": "none",
                "error": str(exc)}
