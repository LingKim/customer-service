"""知识库流水线（调度层）：解析 → 切块 → 向量化 → 落库（索引）；以及检索。

**两套实现，自动择一**：

| 实现 | 什么时候用 | 在哪 |
| --- | --- | --- |
| LlamaIndex | 装了 ``llama-index``（``pip install -r requirements-ai.txt``） | ``llama_engine.py`` |
| 内置实现 | 没装依赖时兜底，保证服务照常可用 | 本文件下半部分 + ``parser/splitter/embedding/store`` |

对外只暴露 ``index_document`` / ``index_text`` / ``search`` 三个函数，
换实现不改调用方——这也是把"用哪个库"这件事收口到一层的好处。

这个文件是"上传一份文档之后到底发生了什么"的完整答案，也是本篇的主线：

    文件字节
      ↓ parser.parse        按后缀解析成纯文本（docx 解 zip、pdf 抽文本流、txt 直接读）
      ↓ parser.normalize    清洗：去控制字符、压空行
      ↓ splitter.split_text 按段落/句号递归切块，块之间留重叠
      ↓ embedding.embed_texts  向量化（有密钥走千问，没有走本地兜底）
      ↓ store.replace_chunks   写进 pgvector（先删旧切片，避免重复索引叠数据）

检索同理：问题 → 向量 → pgvector 余弦检索 → 带出原文与相似度。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

from ..config import get_settings
from ..core.trace import get_trace_id
from . import embedding as embedding_module
from . import llama_engine
from . import parser as parser_module
from . import splitter as splitter_module
from . import store

logger = logging.getLogger(__name__)


@dataclass
class IndexResult:
    """一次索引的结果，接口直接把它返回给前端。"""

    doc_id: int
    file_name: str
    char_count: int
    chunk_count: int
    token_count: int
    embedding_model: str
    embedding_source: str
    preview: list[dict] = field(default_factory=list)


def engine_name() -> str:
    """当前生效的实现：``llama-index`` 或 ``builtin``（健康检查与日志会带上）。"""
    return llama_engine.describe()


def index_document(
    tenant_code: str,
    doc_id: int,
    file_name: str,
    payload: bytes,
    *,
    chunk_size: int | None = None,
    chunk_overlap: int | None = None,
) -> IndexResult:
    """把一份文件变成可检索的切片：装了 llama-index 走它，否则走内置实现。"""
    if llama_engine.available():
        return llama_engine.index_document(tenant_code, doc_id, file_name, payload,
                                           chunk_size=chunk_size, chunk_overlap=chunk_overlap)
    return _builtin_index_document(tenant_code, doc_id, file_name, payload,
                                   chunk_size=chunk_size, chunk_overlap=chunk_overlap)


def _builtin_index_document(
    tenant_code: str,
    doc_id: int,
    file_name: str,
    payload: bytes,
    *,
    chunk_size: int | None = None,
    chunk_overlap: int | None = None,
) -> IndexResult:
    """内置实现（不依赖任何三方库）：标准库解析 + 递归切块 + 本地/远程向量。"""
    settings = get_settings()
    trace_id = get_trace_id() or "-"
    limit = settings.kb_max_file_mb * 1024 * 1024
    if len(payload) > limit:
        raise parser_module.ParseError(f"文件超过 {settings.kb_max_file_mb} MB，请拆分后再上传")

    text = parser_module.normalize(parser_module.parse(file_name, payload))
    if not text.strip():
        raise parser_module.ParseError("解析出来是空的：文件里没有可用的文字内容")

    chunks = splitter_module.split_text(
        text,
        chunk_size=chunk_size or settings.kb_chunk_size,
        chunk_overlap=chunk_overlap if chunk_overlap is not None else settings.kb_chunk_overlap,
    )
    if not chunks:
        raise parser_module.ParseError("切块结果为空，请检查文档内容")

    vectors, source = embedding_module.embed_texts(
        [chunk.content for chunk in chunks],
        tenant_code=tenant_code,
        trace_id=trace_id,
    )
    saved = store.replace_chunks(tenant_code, doc_id, chunks, vectors, source)

    logger.info(
        "知识库索引完成 trace=%s tenant=%s docId=%s file=%s 字符=%d 切片=%d 向量来源=%s",
        trace_id, tenant_code, doc_id, file_name, len(text), saved, source,
    )
    return IndexResult(
        doc_id=doc_id,
        file_name=file_name,
        char_count=len(text),
        chunk_count=saved,
        token_count=sum(chunk.token_count for chunk in chunks),
        embedding_model=source,
        embedding_source=source,
        preview=[_chunk_to_dict(chunk) for chunk in chunks[:5]],
    )


def index_text(
    tenant_code: str,
    doc_id: int,
    content: str,
    *,
    chunk_size: int | None = None,
    chunk_overlap: int | None = None,
) -> IndexResult:
    """手工录入 / 编辑正文的文档：同样是"装了 LlamaIndex 就优先用它"。"""
    if llama_engine.available():
        return llama_engine.index_text(tenant_code, doc_id, content,
                                       chunk_size=chunk_size, chunk_overlap=chunk_overlap)
    return _builtin_index_text(tenant_code, doc_id, content,
                               chunk_size=chunk_size, chunk_overlap=chunk_overlap)


def _builtin_index_text(
    tenant_code: str,
    doc_id: int,
    content: str,
    *,
    chunk_size: int | None = None,
    chunk_overlap: int | None = None,
) -> IndexResult:
    """内置实现：手工正文直接切块索引。"""
    settings = get_settings()
    text = parser_module.normalize(content or "")
    if not text.strip():
        raise parser_module.ParseError("正文为空，没有可索引的内容")
    chunks = splitter_module.split_text(
        text,
        chunk_size=chunk_size or settings.kb_chunk_size,
        chunk_overlap=chunk_overlap if chunk_overlap is not None else settings.kb_chunk_overlap,
    )
    vectors, source = embedding_module.embed_texts(
        [chunk.content for chunk in chunks],
        tenant_code=tenant_code,
        trace_id=get_trace_id() or "-",
    )
    saved = store.replace_chunks(tenant_code, doc_id, chunks, vectors, source)
    logger.info("知识库索引完成（手工正文） tenant=%s docId=%s 字符=%d 切片=%d 向量来源=%s",
                tenant_code, doc_id, len(text), saved, source)
    return IndexResult(
        doc_id=doc_id,
        file_name="",
        char_count=len(text),
        chunk_count=saved,
        token_count=sum(chunk.token_count for chunk in chunks),
        embedding_model=source,
        embedding_source=source,
        preview=[_chunk_to_dict(chunk) for chunk in chunks[:5]],
    )


def search(tenant_code: str, query: str, top_k: int = 5, doc_ids: list[int] | None = None) -> dict:
    """检索：装了 llama-index 用它算查询向量，否则用内置向量；都命中不到时退回关键词。"""
    if llama_engine.available():
        return llama_engine.search(tenant_code, query, top_k=top_k, doc_ids=doc_ids)
    return _builtin_search(tenant_code, query, top_k=top_k, doc_ids=doc_ids)


def _builtin_search(tenant_code: str, query: str, top_k: int = 5,
                    doc_ids: list[int] | None = None) -> dict:
    """内置实现：先用向量找语义相近的，没配密钥（向量不靠谱）就退回关键词匹配。"""
    query = (query or "").strip()
    if not query:
        return {"query": query, "results": [], "vector_source": "none", "mode": "empty"}

    vectors, source = embedding_module.embed_texts([query], tenant_code=tenant_code,
                                                   trace_id=get_trace_id() or "-")
    results: list[dict] = []
    mode = "vector"
    # 本地兜底向量只有字面匹配能力：拿它做余弦检索会返回一堆"看着像但没关系"的切片，
    # 比"没结果"更误导人。所以这种情况直接走关键词检索，并在返回里标出来。
    if source == "local-hash":
        results = store.keyword_search(tenant_code, query, top_k=top_k)
        mode = "keyword-local-vector"
    elif vectors and vectors[0] and any(value != 0 for value in vectors[0]):
        results = store.search(tenant_code, vectors[0], top_k=top_k, doc_ids=doc_ids)

    if not results:
        results = store.keyword_search(tenant_code, query, top_k=top_k)
        mode = "keyword"

    warn_if_degraded(mode, source, query)
    logger.info("知识库检索 trace=%s tenant=%s query=%s 命中=%d 模式=%s 向量来源=%s",
                get_trace_id() or "-", tenant_code, query[:40], len(results), mode, source)
    return {
        "query": query,
        "results": results,
        "vector_source": source,
        "mode": mode,
    }


def warn_if_degraded(mode: str, source: str, query: str) -> None:
    """检索退化到关键词匹配时，明说一句。

    "知识库明明有数据，机器人却答不上来"最常见的原因就是这个：
    向量密钥没配好 → 索引时写进去的是本地兜底向量（没有语义）→ 检索只能按字面匹配，
    于是"退货多久能到账"捞回来的可能是"退货开票怎么处理"这种沾边但没用的块，
    模型照着这些块自然回答不了。日志里说清楚，比让人对着答案猜强得多。
    """
    if not mode.startswith("keyword"):
        return
    logger.warning(
        "检索已退化为关键词匹配 query=%s 向量来源=%s\n"
        "  → 关键词匹配只看字面（相邻两字），容易捞回沾边但答不上问题的切片。\n"
        "  → 配好向量密钥后请到「知识库」把文档**重新索引**，再用 /api/ai/v1/rag/health 复查。",
        query[:40], source)


def _chunk_to_dict(chunk: splitter_module.Chunk) -> dict:
    return {
        "chunk_no": chunk.index,
        "char_count": chunk.char_count,
        "token_count": chunk.token_count,
        "content": chunk.content,
    }
