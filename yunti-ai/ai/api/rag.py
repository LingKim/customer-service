"""知识问答接口：让 AI 会查资料，并把出处标出来。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/ai/v1/rag/ask | 问一句，返回回答 + 引用来源 + 编排轨迹 |

调用方是 customer-service（浏览器不直连 AI 服务）。
"""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from ..core.trace import require_trace_id
from ..rag import graph
from .kb import require_kb_secret, require_tenant_header

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai/v1/rag", tags=["rag"], dependencies=[Depends(require_kb_secret)])


class AskRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    tenant_code: str = Field(min_length=1, max_length=32)
    question: str = Field(min_length=1, max_length=500)
    top_k: int = Field(default=5, ge=1, le=20)
    doc_ids: list[int] = Field(default_factory=list)


@router.post("/ask")
async def ask(request: Request, body: AskRequest, trace_id: str = Depends(require_trace_id)) -> dict:
    require_tenant_header(request, body.tenant_code)
    started = time.perf_counter()
    logger.info("收到知识问答请求 trace=%s tenant=%s question=%s",
                trace_id, body.tenant_code, body.question[:60])
    try:
        result = await graph.ask(
            question=body.question.strip(),
            tenant_code=body.tenant_code,
            top_k=body.top_k,
            doc_ids=body.doc_ids or None,
        )
    except Exception as exc:  # noqa: BLE001
        logger.exception("知识问答失败 trace=%s：%s", trace_id, exc)
        raise HTTPException(status_code=500, detail=f"知识问答失败：{exc}") from exc
    logger.info("知识问答完成 trace=%s 编排=%s 引用=%d cost=%dms",
                trace_id, result.get("engine"), len(result.get("citations") or []),
                int((time.perf_counter() - started) * 1000))
    return result


@router.get("/health")
def rag_health() -> dict:
    """编排层探活 + **配置体检**。

    只回答"有没有配"，绝不回显密钥本身——排障时最常问的就是
    "我明明配了密钥，为什么还在用兜底向量/兜底回答"。
    """
    from ..config import get_settings

    settings = get_settings()
    llm_key = settings.qwen_api_key or settings.deepseek_api_key
    llm_provider = "qwen" if settings.qwen_api_key else ("deepseek" if settings.deepseek_api_key else "none")
    embedding_key = settings.embedding_key()
    return {
        "status": "UP",
        "engine": graph.engine_name(),
        # 回答用的模型：没配密钥时会退化成"只给检索原文"
        "llmProvider": llm_provider,
        "llmKeyConfigured": bool(llm_key),
        "llmModel": settings.qwen_model if settings.qwen_api_key else settings.deepseek_model,
        # 向量化用的模型：没配密钥时会退化成本地兜底向量（只能字面匹配）
        "embeddingKeyConfigured": bool(embedding_key),
        "embeddingModel": settings.embedding_model,
        "embeddingKeyFrom": "embedding_api_key" if settings.embedding_api_key else (
            "qwen_api_key（复用）" if embedding_key else "未配置"),
        "hint": "" if (llm_key and embedding_key) else
                "有密钥没配：缺 LLM 密钥→回答只给原文；缺 embedding 密钥→检索只做字面匹配。"
                "两条都配齐才是真正的语义检索 + AI 生成回答。",
    }
