"""企业知识库接口：索引（解析 / 切块 / 向量化）与检索。

接口约定（都是给 customer-service 调的，不直接暴露给浏览器）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/ai/v1/kb/index?tenant_code=&doc_id=&file_name= | 请求体是文件原始字节，解析+切块+向量化+落库 |
| POST | /api/ai/v1/kb/index-text | 手工正文的文档：直接切块索引 |
| POST | /api/ai/v1/kb/search | 检索测试 / RAG 召回 |
| GET | /api/ai/v1/kb/documents/{doc_id}/chunks | 切片预览 |
| DELETE | /api/ai/v1/kb/documents/{doc_id} | 删除某文档的全部切片（文档下线/删除时调用） |

文件走**原始字节**而不是 multipart：这样 Python 侧不用装 python-multipart，
Java 侧用 RestClient 直接发 body 就行，少一层依赖。
"""

from __future__ import annotations

import logging
import hmac
import time

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from ..core.trace import require_trace_id
from ..config import get_settings
from ..rag import pipeline, store

logger = logging.getLogger(__name__)

def require_kb_secret(request: Request) -> None:
    configured = get_settings().internal_shared_secret
    provided = request.headers.get("X-Yunti-Internal-Secret", "")
    if not configured or not provided or not hmac.compare_digest(configured, provided):
        raise HTTPException(status_code=403, detail="知识库内部接口未授权")


def require_tenant_header(request: Request, tenant_code: str) -> None:
    if not tenant_code or tenant_code == "PLATFORM" or request.headers.get("X-Tenant-Code") != tenant_code:
        raise HTTPException(status_code=403, detail="租户身份不匹配")


router = APIRouter(prefix="/ai/v1/kb", tags=["kb"], dependencies=[Depends(require_kb_secret)])


class IndexTextRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    doc_id: int
    content: str = Field(min_length=1)
    chunk_size: int | None = None
    chunk_overlap: int | None = None


class SearchRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    tenant_code: str = Field(min_length=1, max_length=32)
    query: str = Field(min_length=1, max_length=500)
    top_k: int = Field(default=5, ge=1, le=20)
    doc_ids: list[int] = Field(default_factory=list)


def _fail(exc: Exception) -> HTTPException:
    """把解析类错误变成 400（用户看得懂的提示），其它错误变成 500。"""
    from ..rag.parser import ParseError

    if isinstance(exc, ParseError):
        return HTTPException(status_code=400, detail=str(exc))
    logger.exception("知识库接口失败：%s", exc)
    return HTTPException(status_code=500, detail=f"知识库处理失败：{exc}")


@router.post("/index")
async def index_file(
    request: Request,
    tenant_code: str = Query(alias="tenant_code", min_length=1),
    doc_id: int = Query(alias="doc_id"),
    file_name: str = Query(alias="file_name", min_length=1, max_length=255),
    chunk_size: int | None = Query(default=None, alias="chunk_size"),
    chunk_overlap: int | None = Query(default=None, alias="chunk_overlap"),
    trace_id: str = Depends(require_trace_id),
) -> dict:
    """接收文件原始字节 → 解析 → 切块 → 向量化 → 落库。"""
    require_tenant_header(request, tenant_code)
    payload = await request.body()
    started = time.perf_counter()
    logger.info("收到知识库索引请求 trace=%s tenant=%s docId=%s file=%s bytes=%d",
                trace_id, tenant_code, doc_id, file_name, len(payload))
    if not payload:
        # 请求体是空的：多半是调用方发文件时丢了 body（例如手写 Content-Type 丢了 boundary）
        raise HTTPException(
            status_code=400,
            detail="请求体是空的：没有收到文件内容。请检查调用方是否把文件字节放进了请求体"
                   "（上传 FormData 不要手写 Content-Type，会丢 boundary）",
        )
    try:
        result = pipeline.index_document(
            tenant_code=tenant_code,
            doc_id=doc_id,
            file_name=file_name,
            payload=payload,
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
        )
    except Exception as exc:  # noqa: BLE001
        raise _fail(exc) from exc
    logger.info("知识库索引返回 trace=%s tenant=%s docId=%s 切片=%d cost=%dms",
                trace_id, tenant_code, doc_id, result.chunk_count,
                int((time.perf_counter() - started) * 1000))
    return _result_payload(result)


@router.post("/index-text")
async def index_text(
    request: Request,
    body: IndexTextRequest,
    tenant_code: str = Query(alias="tenant_code", min_length=1),
    trace_id: str = Depends(require_trace_id),
) -> dict:
    """手工录入的正文：不用解析文件，直接切块索引。"""
    require_tenant_header(request, tenant_code)
    logger.info("收到知识库索引请求（正文） trace=%s tenant=%s docId=%s chars=%d",
                trace_id, tenant_code, body.doc_id, len(body.content))
    try:
        result = pipeline.index_text(
            tenant_code=tenant_code,
            doc_id=body.doc_id,
            content=body.content,
            chunk_size=body.chunk_size,
            chunk_overlap=body.chunk_overlap,
        )
    except Exception as exc:  # noqa: BLE001
        raise _fail(exc) from exc
    return _result_payload(result)


@router.post("/search")
async def search(request: Request, body: SearchRequest, trace_id: str = Depends(require_trace_id)) -> dict:
    """检索：给"检索测试"用，后续机器人 RAG 也走它。"""
    require_tenant_header(request, body.tenant_code)
    try:
        return pipeline.search(
            tenant_code=body.tenant_code,
            query=body.query,
            top_k=body.top_k,
            doc_ids=body.doc_ids or None,
        )
    except Exception as exc:  # noqa: BLE001
        raise _fail(exc) from exc


@router.get("/documents/{doc_id}/chunks")
def list_chunks(
    request: Request,
    doc_id: int,
    tenant_code: str = Query(alias="tenant_code", min_length=1),
    limit: int = Query(default=200, ge=1, le=1000),
) -> dict:
    require_tenant_header(request, tenant_code)
    rows = store.list_chunks(tenant_code, doc_id, limit=limit)
    for row in rows:
        row["id"] = str(row["id"])
        row["doc_id"] = str(row["doc_id"])
        row["create_time"] = str(row.get("create_time") or "")
    return {"total": len(rows), "chunks": rows}


@router.delete("/documents/{doc_id}")
def delete_chunks(
    request: Request,
    doc_id: int,
    tenant_code: str = Query(alias="tenant_code", min_length=1),
) -> dict:
    require_tenant_header(request, tenant_code)
    removed = store.delete_chunks(tenant_code, doc_id)
    logger.info("删除知识库切片 tenant=%s docId=%s 删除=%d", tenant_code, doc_id, removed)
    return {"deleted": removed}


@router.get("/health")
def kb_health(request: Request, tenant_code: str | None = None) -> dict:
    """知识库探活：库连不上或没装 pgvector 时这里会是 DOWN。"""
    if tenant_code:
        require_tenant_header(request, tenant_code)
    ok = store.ping()
    result = {
        "status": "UP" if ok else "DOWN",
        "vectorStore": "pgvector",
        "table": "kb_chunk",
        # 说明当前用的是哪套实现：装了 llama-index 就是它，否则是内置兜底
        "engine": pipeline.engine_name(),
    }
    result.update(_vector_quality(tenant_code))
    return result


def _vector_quality(tenant_code: str | None = None) -> dict:
    """体检：库里那批切片到底是用什么向量建出来的。

    这是"知识库明明有数据，机器人却答不上来"最该先看的一项：
    向量化失败（密钥没配 / 401）会静默写成 local-hash 兜底向量，
    这种向量没有语义，检索只能按字面匹配。而向量是**索引那一刻**算的，
    所以密钥修好之后必须重新索引，光重启没用。
    """
    from ..config import get_settings

    settings = get_settings()
    fallback = ["local-hash"]
    try:
        with store.connect() as conn:
            rows = conn.execute(
                """
                SELECT COALESCE(embedding_model, 'null') AS model, COUNT(1) AS chunks
                  FROM kb_chunk
                 WHERE (%s IS NULL OR tenant_code = %s)
                 GROUP BY COALESCE(embedding_model, 'null')
                 ORDER BY chunks DESC
                """, (tenant_code, tenant_code)
            ).fetchall()
    except Exception as exc:  # noqa: BLE001
        return {"chunkVectorModels": [], "vectorQualityHint": f"读取切片向量来源失败：{exc}"}
    models = [{"model": row["model"], "chunks": int(row["chunks"])} for row in rows]
    degraded = [item for item in models if item["model"] in fallback]
    # 具体是哪些文档用了兜底向量：客户服务侧据此只重建这几份，不用整库重跑
    fallback_docs: list[str] = []
    if degraded:
        try:
            with store.connect() as conn:
                fallback_docs = [
                    str(row["doc_id"])
                    for row in conn.execute(
                        """
                        SELECT DISTINCT doc_id
                          FROM kb_chunk
                         WHERE embedding_model = 'local-hash'
                           AND (%s IS NULL OR tenant_code = %s)
                         ORDER BY doc_id
                        """, (tenant_code, tenant_code)
                    ).fetchall()
                ]
        except Exception as exc:  # noqa: BLE001
            logger.warning("统计兜底向量文档失败：%s", exc)
    if degraded:
        hint = (f"有 {sum(item['chunks'] for item in degraded)} 个切片用的是本地兜底向量"
                "（没有语义，检索会退化成关键词匹配）。"
                "修好向量密钥（YUNTI_AI_QWEN_API_KEY 或 YUNTI_AI_EMBEDDING_API_KEY）后，"
                "到「知识库」把这些文档重新索引一遍。")
    elif models:
        hint = "切片都是真实向量，语义检索可用"
    else:
        hint = "知识库里还没有切片，先上传文档"
    return {
        "chunkVectorModels": models,
        "fallbackDocIds": fallback_docs,
        "embeddingKeyConfigured": bool(settings.embedding_key()),
        "embeddingModel": settings.embedding_model,
        "vectorQualityHint": hint,
    }


def _result_payload(result: pipeline.IndexResult) -> dict:
    """统一出参：id 一律转字符串，避免前端 JS 精度丢失。"""
    return {
        "docId": str(result.doc_id),
        "fileName": result.file_name,
        "charCount": result.char_count,
        "chunkCount": result.chunk_count,
        "tokenCount": result.token_count,
        "embeddingModel": result.embedding_model,
        "embeddingSource": result.embedding_source,
        "preview": result.preview,
    }
