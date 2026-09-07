"""全量 AI 质检骨架（批处理任务消费入口）。"""

from __future__ import annotations

def run_qa_task(tenant_code: str, session_id: str) -> dict[str, object]:
    """TODO: 规则引擎 -> LLM 初检 -> 结果回调 customer-service。"""
    return {
        "tenant_code": tenant_code,
        "session_id": session_id,
        "status": "pending",
        "note": "接入 LangGraph 质检流水线后返回真实评分",
    }
