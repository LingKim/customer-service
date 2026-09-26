"""LlamaIndex 版实现：装了 ``llama-index`` 就走这条（本篇的主实现）。

用到的 LlamaIndex 组件与职责：

| 环节 | LlamaIndex 组件 | 说明 |
| --- | --- | --- |
| 读文件 | ``SimpleDirectoryReader`` | 交给它按后缀选 Reader（docx / pdf / md 都认） |
| 切块 | ``SentenceSplitter`` | 语义化分块 + 重叠，和内置实现的思路一致 |
| 向量化 | ``OpenAIEmbedding`` | 千问的 OpenAI 兼容接口直接复用，省一套客户端 |

**存储这一环故意不走** ``llama_index.vector_stores.postgres.PGVectorStore``：
它会在库里建自己的表结构（data JSONB + embedding），而本项目的 ``kb_chunk``
是定制的（切片号、字数、token 数、原文件名…），Java 侧的"切片预览"和检索结果
都直接读这张表。所以这里用 LlamaIndex 出**节点与向量**，再写进既有表——
这也是把 LlamaIndex 接进已有系统的常见做法：能用它的解析/切块/检索能力，
但不必把数据模型交给它托管。

装依赖：``pip install -r requirements-ai.txt``（里面有 llama-index；
docx/pdf 解析还需要 llama-index-readers-file，embeddings 需要
llama-index-embeddings-openai，见该文件的注释）。
"""

from __future__ import annotations

import logging
import os
import tempfile

from ..config import get_settings
from . import splitter as splitter_module
from . import store

logger = logging.getLogger(__name__)

# 这些包没装时，整条链路自动退回内置实现（服务照常可用）
_REQUIRED = ("llama_index.core", "llama_index.embeddings.openai")


def available() -> bool:
    """llama-index 装了没：装了就用它，没装就走内置实现。

    ``find_spec`` 在"顶层包都不存在"时会抛 ModuleNotFoundError（不是返回 None），
    所以这里要兜住——服务不能在导入阶段因为少个依赖就起不来。
    """
    import importlib.util

    for name in _REQUIRED:
        try:
            if importlib.util.find_spec(name) is None:
                return False
        except (ImportError, ModuleNotFoundError, ValueError):
            return False
    return True


def describe() -> str:
    """给健康检查/日志用：说清楚当前用的是哪套实现。"""
    return "llama-index" if available() else "builtin"


# LlamaIndex 的 OpenAIEmbedding 会用枚举校验模型名，只认 OpenAI 官方这几个
# （见 OpenAIEmbeddingModelType）。千问的 text-embedding-v3 不在里面，硬塞会直接抛
# ValueError: 'text-embedding-v3' is not a valid OpenAIEmbeddingModelType。
OPENAI_OFFICIAL_MODELS = {
    "text-embedding-ada-002", "text-embedding-3-small", "text-embedding-3-large",
    "davinci", "curie", "babbage", "ada",
}


def _build_embed_model():
    """能交给 LlamaIndex 就交给它，交不了就返回 None（调用方改用本项目自己的客户端）。

    判断依据只有一个：**模型名是不是 OpenAI 官方的**。
    - OpenAI 官方模型 → 用 LlamaIndex 的 ``OpenAIEmbedding``；
    - 千问 / 自建 OpenAI 兼容服务（模型名不在上述枚举里）→ 返回 None，
      由 ``embedding.embed_texts`` 走同一个 ``/embeddings`` 协议发请求。
      它支持任意模型名，还带本地兜底，是这条链路上更合适的选择。
    """
    settings = get_settings()
    if settings.embedding_model not in OPENAI_OFFICIAL_MODELS:
        return None
    from llama_index.embeddings.openai import OpenAIEmbedding

    return OpenAIEmbedding(
        model=settings.embedding_model,
        api_base=settings.embedding_base_url,
        # 同样要走 embedding_key()：专用密钥没配时复用千问的 key，
        # 否则这里会拿着空密钥去请求，401 之后又静默降级成本地向量
        api_key=settings.embedding_key() or "not-configured",
        dimensions=1024,
        embed_batch_size=10,
    )


def _splitter():
    """LlamaIndex 的切块器。

    注意 ``chunk_size`` 的单位是 **token**（不是字符）：同样是 600，
    内置实现是"600 个字符"，LlamaIndex 是"600 个 token"。中文大致 1 token ≈ 1~1.5 个字，
    所以走 LlamaIndex 时块会更大一些、块数更少。想让两边块大小接近，
    把 ``YUNTI_AI_KB_CHUNK_SIZE`` 调小（比如 400）即可。
    """
    from llama_index.core.node_parser import SentenceSplitter

    settings = get_settings()
    return SentenceSplitter(
        chunk_size=settings.kb_chunk_size,
        chunk_overlap=settings.kb_chunk_overlap,
        # 段落优先按空行切；句子边界交给它内置的分句规则
        paragraph_separator="\n\n",
    )


def _read_file(file_name: str, payload: bytes):
    """用 SimpleDirectoryReader 解析文件：它按后缀挑 Reader（docx/pdf/md/txt 都支持）。

    LlamaIndex 的 Reader 自己还要装小依赖（读 docx 要 ``docx2txt``、读 pdf 要 ``pypdf``）。
    真遇到缺依赖时不能整篇文档索引失败——退回内置解析器把文字读出来，
    并打一行日志说清原因，否则用户只看到一句"索引失败"，不知道为什么要装包。
    """
    from llama_index.core import Document, SimpleDirectoryReader

    suffix = os.path.splitext(file_name)[1] or ".txt"
    try:
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "upload" + suffix)
            with open(path, "wb") as handle:
                handle.write(payload)
            documents = SimpleDirectoryReader(input_files=[path]).load_data()
        if not documents:
            raise ValueError(f"解析出来是空的：{file_name}")
        for document in documents:
            document.metadata["file_name"] = file_name
        return documents
    except Exception as exc:  # noqa: BLE001
        from . import parser as parser_module

        logger.warning("LlamaIndex 读取 %s 失败（%s: %s），改用内置解析器",
                       file_name, type(exc).__name__, exc)
        text = parser_module.normalize(parser_module.parse(file_name, payload))
        if not text.strip():
            raise
        return [Document(text=text, metadata={"file_name": file_name})]


def _embed(chunks, tenant_code: str, what: str):
    """把切片向量化：能走 LlamaIndex 就走它，否则用本项目自己的客户端。"""
    return _embed_texts([chunk.content for chunk in chunks], tenant_code, what)


def _embed_texts(texts: list[str], tenant_code: str, what: str = "查询"):
    from . import embedding as embedding_module

    settings = get_settings()
    embed_model = _build_embed_model()
    if embed_model is None:
        # 千问这类模型名不在 LlamaIndex 枚举里的，走本项目自己的 OpenAI 兼容客户端
        logger.info("向量化走内置客户端（模型 %s 不是 OpenAI 官方模型）", settings.embedding_model)
        return embedding_module.embed_texts(texts, tenant_code=tenant_code)
    source = f"llama-index:{settings.embedding_model}"
    try:
        vectors = embed_model.get_text_embedding_batch(texts)
        return vectors, source
    except Exception as exc:  # noqa: BLE001
        # 没配密钥时接口会 401：退回客户端（它自带本地兜底），保证整条链路不中断
        logger.warning("%s 向量化失败，改用内置客户端：%s: %s", what, type(exc).__name__, exc)
        return embedding_module.embed_texts(texts, tenant_code=tenant_code)


def _nodes_to_chunks(nodes) -> list:
    """把 LlamaIndex 的节点转成 store 认的切片对象。"""
    chunks = []
    for index, node in enumerate(nodes, start=1):
        text = (node.get_content() or "").strip()
        if not text:
            continue
        chunks.append(splitter_module.Chunk(
            index=index,
            content=text,
            char_count=len(text),
            token_count=splitter_module.estimate_tokens(text),
        ))
    return chunks


def index_document(tenant_code: str, doc_id: int, file_name: str, payload: bytes,
                   *, chunk_size: int | None = None, chunk_overlap: int | None = None):
    """LlamaIndex 版索引：SimpleDirectoryReader 读 → SentenceSplitter 切 → OpenAIEmbedding 向量化。"""
    from .pipeline import IndexResult  # 延迟导入，避免循环

    documents = _read_file(file_name, payload)
    parser = _splitter()
    if chunk_size:
        parser.chunk_size = chunk_size
    if chunk_overlap is not None:
        parser.chunk_overlap = chunk_overlap
    nodes = parser.get_nodes_from_documents(documents)
    chunks = _nodes_to_chunks(nodes)
    if not chunks:
        raise ValueError("切块结果为空，请检查文档内容")

    vectors, source = _embed(chunks, tenant_code, file_name)

    saved = store.replace_chunks(tenant_code, doc_id, chunks, vectors, source)
    logger.info("LlamaIndex 索引完成 tenant=%s docId=%s file=%s 切片=%d 向量来源=%s",
                tenant_code, doc_id, file_name, saved, source)
    return IndexResult(
        doc_id=doc_id,
        file_name=file_name,
        char_count=sum(chunk.char_count for chunk in chunks),
        chunk_count=saved,
        token_count=sum(chunk.token_count for chunk in chunks),
        embedding_model=source,
        embedding_source=source,
        preview=[{"chunk_no": c.index, "char_count": c.char_count,
                  "token_count": c.token_count, "content": c.content} for c in chunks[:5]],
    )


def index_text(tenant_code: str, doc_id: int, content: str,
               *, chunk_size: int | None = None, chunk_overlap: int | None = None):
    """手工正文：包成 Document 后走同一套切块 + 向量化。"""
    from llama_index.core import Document
    from .pipeline import IndexResult

    document = Document(text=content, metadata={"file_name": ""})
    parser = _splitter()
    if chunk_size:
        parser.chunk_size = chunk_size
    if chunk_overlap is not None:
        parser.chunk_overlap = chunk_overlap
    chunks = _nodes_to_chunks(parser.get_nodes_from_documents([document]))
    if not chunks:
        raise ValueError("正文为空，没有可索引的内容")

    vectors, source = _embed(chunks, tenant_code, "手工正文")

    saved = store.replace_chunks(tenant_code, doc_id, chunks, vectors, source)
    return IndexResult(
        doc_id=doc_id,
        file_name="",
        char_count=sum(chunk.char_count for chunk in chunks),
        chunk_count=saved,
        token_count=sum(chunk.token_count for chunk in chunks),
        embedding_model=source,
        embedding_source=source,
        preview=[{"chunk_no": c.index, "char_count": c.char_count,
                  "token_count": c.token_count, "content": c.content} for c in chunks[:5]],
    )


def search(tenant_code: str, query: str, top_k: int = 5, doc_ids: list[int] | None = None) -> dict:
    """检索：LlamaIndex 算查询向量，再交给 pgvector 做余弦检索。"""
    vectors, source = _embed_texts([query], tenant_code)
    vector = vectors[0] if vectors else []

    local = source == "local-hash"
    results: list[dict] = []
    mode = "vector"
    if local:
        # 向量化没配密钥（退回本地兜底向量）时不做余弦检索：
        # 那种向量没有语义，检出来的结果反而误导人
        results = store.keyword_search(tenant_code, query, top_k=top_k)
        mode = "keyword-local-vector"
    elif vector:
        results = store.search(tenant_code, vector, top_k=top_k, doc_ids=doc_ids)
    if not results:
        results = store.keyword_search(tenant_code, query, top_k=top_k)
        mode = "keyword"
    # 局部导入：pipeline 顶层也 import 了本模块，放顶层会形成循环导入
    from . import pipeline as pipeline_module
    pipeline_module.warn_if_degraded(mode, source, query)
    logger.info("知识库检索（llama-index） tenant=%s query=%s 命中=%d 模式=%s 向量来源=%s",
                tenant_code, query[:40], len(results), mode, source)
    return {"query": query, "results": results, "vector_source": source, "mode": mode}
