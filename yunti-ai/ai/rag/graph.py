"""知识问答的编排：检索 → 判断 → （不够就改写重检）→ 生成带引用的回答 → 校验引用。

**为什么用 LangGraph**：这条链路不是"一步接一步"那么简单，中间有分支——
召回结果够不够？不够要不要换个问法再查一次？回答里的引用编号有没有越界？
用 LangGraph 的 StateGraph 把这几步和分支画出来，比写一长串 if/else 清楚得多，
每一步的输入输出也都能记录下来（前端能展示"这次是怎么查的"）。

流程图：

    retrieve ──▶ grade ──┬─(够)──▶ answer ──▶ verify ──▶ END
                ▲        └─(不够)─▶ rewrite ──┘
                └────────────────────┘（最多改写一次，防死循环）

没装 langgraph 时退化成同样步骤的线性实现（`_linear_flow`），
差别只是"没有显式的图"，流程和结果一致——服务不会因为少一个包就不可用。
"""

from __future__ import annotations

import logging
import re
from typing import Any, TypedDict

from . import retriever
from .store import query_terms

logger = logging.getLogger(__name__)

# 召回质量阈值：两种检索口径的分数不在一个量纲上，要分开设
#   vector  → 余弦相似度，0.5 以上算"确实相关"
#   keyword → 命中片段占比，0.15 以上算"沾边"
VECTOR_MIN_SCORE = 0.5
KEYWORD_MIN_SCORE = 0.15

# 最多改写重检几次（防死循环）
MAX_REWRITE_ROUNDS = 1


class RagState(TypedDict, total=False):
    """图里流转的状态。每个节点返回要更新的字段即可。"""

    question: str
    tenant_code: str
    top_k: int
    doc_ids: list[int]
    query: str            # 当前这一轮实际用的检索问法
    hits: list[dict]      # 召回的切片
    mode: str             # 检索口径
    enough: bool          # 召回够不够
    rounds: int           # 已经改写了几次
    answer: str
    citations: list[dict]
    steps: list[dict]     # 每一步的执行记录，前端展示"编排过程"用


def _step(state: RagState, node: str, detail: str, **extra: Any) -> list[dict]:
    """记一条执行轨迹（累加，不覆盖）。"""
    steps = list(state.get("steps") or [])
    steps.append({"node": node, "detail": detail, **extra})
    return steps


# --------------------------------------------------------------------------- 节点


def node_retrieve(state: RagState) -> dict:
    """检索：拿当前问法去知识库捞最相关的切片。"""
    query = state.get("query") or state["question"]
    result = retriever.retrieve_with_meta(
        query=query,
        tenant_code=state["tenant_code"],
        top_k=state.get("top_k", 5),
        doc_ids=state.get("doc_ids") or None,
    )
    hits = result.get("results") or []
    mode = result.get("mode") or "vector"
    return {
        "query": query,
        "hits": hits,
        "mode": mode,
        "steps": _step(state, "retrieve",
                       f"用「{query}」检索，命中 {len(hits)} 条（{mode}）",
                       hitCount=len(hits), mode=mode),
    }


def node_grade(state: RagState) -> dict:
    """判断召回够不够：有没有结果 + 最高分有没有过阈值。"""
    hits = state.get("hits") or []
    keyword_mode = "keyword" in (state.get("mode") or "")
    threshold = KEYWORD_MIN_SCORE if keyword_mode else VECTOR_MIN_SCORE
    top = max((float(hit.get("score") or 0) for hit in hits), default=0.0)
    enough = bool(hits) and top >= threshold
    return {
        "enough": enough,
        "steps": _step(state, "grade",
                       f"最高分 {top:.3f}（阈值 {threshold}）→ "
                       + ("资料够用，直接回答" if enough else "资料不够，换个问法再查"),
                       topScore=round(top, 4), threshold=threshold, enough=enough),
    }


def node_rewrite(state: RagState) -> dict:
    """改写问法：把长问题拆成关键词再查一次。

    这里**故意不用大模型改写**：一次改写就多一次模型调用、多一份 token 成本，
    而"把'退货多久能到账'拆成'退货 到账'"这件事规则完全够用（复用的是关键词检索
    那套切片段逻辑）。需要更聪明的改写时，把这个节点换成调模型即可。
    """
    terms = query_terms(state["question"], max_terms=6)
    rewritten = " ".join(terms) if terms else state["question"]
    return {
        "query": rewritten,
        "rounds": int(state.get("rounds") or 0) + 1,
        "steps": _step(state, "rewrite", f"改写问法为「{rewritten}」", rewritten=rewritten),
    }


async def node_answer(state: RagState) -> dict:
    """生成回答：把召回切片编号后交给模型，要求它按 [1][2] 标注出处。"""
    from ..services.rag import compose_answer

    answer, citations = await compose_answer(
        question=state["question"],
        hits=(state.get("hits") or []) if state.get("enough") else [],
        tenant_code=state.get("tenant_code") or "",
    )
    return {
        "answer": answer,
        "citations": citations,
        "steps": _step(state, "answer",
                       f"基于 {len(citations)} 条资料生成回答",
                       citationCount=len(citations)),
    }


def node_verify(state: RagState) -> dict:
    """校验引用：回答里出现的 [n] 必须在召回结果范围内，越界的剔掉。

    模型偶尔会"顺手编一个出处"，比如只给了 3 条资料却写 [5]。
    这一步把越界的引用去掉并记进轨迹——宁可少一个出处，也不能给错出处。
    """
    from ..services.rag import verify_citations

    kept, dropped = verify_citations(state.get("answer") or "", state.get("citations") or [])
    answer = state.get("answer") or ""
    if dropped:
        invalid = {str(index) for index in dropped}
        answer = re.sub(r"\[(\d{1,2})\]", lambda match: "" if match.group(1) in invalid else match.group(0), answer)
    return {
        "answer": answer,
        "citations": kept,
        "steps": _step(state, "verify",
                       f"校验引用：保留 {len(kept)} 条" + (f"，剔除越界 {len(dropped)} 条" if dropped else ""),
                       kept=len(kept), dropped=dropped),
    }


def _route_after_grade(state: RagState) -> str:
    if state.get("enough"):
        return "answer"
    if int(state.get("rounds") or 0) < MAX_REWRITE_ROUNDS:
        return "rewrite"
    return "answer"  # 改写过了还是不够：也让模型基于现有资料回答（回答里会说明依据不足）


# --------------------------------------------------------------------------- 图


def langgraph_available() -> bool:
    import importlib.util

    try:
        return importlib.util.find_spec("langgraph.graph") is not None
    except (ImportError, ModuleNotFoundError, ValueError):
        return False


def engine_name() -> str:
    return "langgraph" if langgraph_available() else "linear"


def _build_graph():
    from langgraph.graph import END, StateGraph

    builder = StateGraph(RagState)
    builder.add_node("retrieve", node_retrieve)
    builder.add_node("grade", node_grade)
    builder.add_node("rewrite", node_rewrite)
    builder.add_node("answer", node_answer)
    builder.add_node("verify", node_verify)

    builder.set_entry_point("retrieve")
    builder.add_edge("retrieve", "grade")
    builder.add_conditional_edges("grade", _route_after_grade,
                                  {"answer": "answer", "rewrite": "rewrite"})
    builder.add_edge("rewrite", "retrieve")
    builder.add_edge("answer", "verify")
    builder.add_edge("verify", END)
    return builder.compile()


_graph = None


async def ask(question: str, tenant_code: str, top_k: int = 5,
              doc_ids: list[int] | None = None) -> dict:
    """知识问答主入口：编排一条完整的"查资料 → 回答 → 标出处"链路。"""
    global _graph
    state: RagState = {
        "question": question,
        "tenant_code": tenant_code,
        "top_k": top_k,
        "doc_ids": doc_ids or [],
        "query": question,
        "rounds": 0,
        "steps": [],
    }
    if langgraph_available():
        if _graph is None:
            _graph = _build_graph()
            logger.info("知识问答编排已就绪：LangGraph StateGraph")
        final: RagState = await _graph.ainvoke(state)
    else:
        logger.info("知识问答编排：线性实现（未装 langgraph，建议 pip install langgraph）")
        final = await _linear_flow(state)

    return {
        "question": question,
        "answer": final.get("answer") or "",
        "citations": final.get("citations") or [],
        "retrieved": final.get("hits") or [],
        "mode": final.get("mode"),
        "engine": engine_name(),
        "enough": bool(final.get("enough")),
        "steps": final.get("steps") or [],
    }


async def _linear_flow(state: RagState) -> RagState:
    """没装 langgraph 时的等价实现：步骤、分支判断完全一致，只是写成顺序代码。"""
    state.update(node_retrieve(state))
    state.update(node_grade(state))
    while not state.get("enough") and int(state.get("rounds") or 0) < MAX_REWRITE_ROUNDS:
        state.update(node_rewrite(state))
        state.update(node_retrieve(state))
        state.update(node_grade(state))
    state.update(await node_answer(state))
    state.update(node_verify(state))
    return state
