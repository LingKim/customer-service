"""向量存取：切片写进 pgvector、按相似度检索出来。

为什么用 pgvector 而不是另起一个向量库：知识库本身就在 PostgreSQL 里（kb_document），
把切片和向量放在同一个库、同一个 tenant_code 维度下，租户隔离和备份策略都不用再想一套。
数据量真上来了（千万级切片）再换 Milvus 之类的专用库也不迟，接口就下面这几个函数。
"""

from __future__ import annotations

import logging
import re
import secrets

import psycopg
from psycopg.rows import dict_row

from ..config import get_settings
from .embedding import to_pgvector

logger = logging.getLogger(__name__)

def next_id() -> int:
    """生成正数 BIGINT ID；随机 63 位避免多实例使用同一毫秒序号时冲突。"""
    return secrets.randbelow((1 << 63) - 1) + 1


def connect():
    """连知识库所在的那个库（默认就是 customer_db）。"""
    settings = get_settings()
    dsn = settings.kb_database_url or _derive_kb_dsn(settings.database_url)
    return psycopg.connect(dsn, row_factory=dict_row)


def _derive_kb_dsn(ai_dsn: str) -> str:
    """没单独配 kb_database_url 时，从 ai_db 的连接串推出 customer_db 的连接串。"""
    return ai_dsn.replace("/ai_db", "/customer_db")


def replace_chunks(
    tenant_code: str,
    doc_id: int,
    chunks: list,
    vectors: list[list[float]],
    embedding_model: str,
) -> int:
    """重建一份文档的全部切片：先删旧的，再批量写新的。

    做成"先删后写"是为了重复索引时不出重复数据——重新上传同一份文档，
    索引结果应当覆盖上一次，而不是叠一份。
    """
    if len(chunks) != len(vectors):
        raise ValueError(f"切片数与向量数不一致：{len(chunks)} vs {len(vectors)}")
    with connect() as conn, conn.cursor() as cur:
        cur.execute("DELETE FROM kb_chunk WHERE tenant_code = %s AND doc_id = %s", (tenant_code, doc_id))
        if not chunks:
            return 0
        rows = [
            (
                next_id(),
                tenant_code,
                doc_id,
                chunk.index,
                chunk.content,
                chunk.char_count,
                chunk.token_count,
                to_pgvector(vector) if vector else None,
                embedding_model,
            )
            for chunk, vector in zip(chunks, vectors)
        ]
        cur.executemany(
            """
            INSERT INTO kb_chunk
                (id, tenant_code, doc_id, chunk_no, content, char_count, token_count, embedding, embedding_model)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s::vector, %s)
            """,
            rows,
        )
        return len(rows)


def delete_chunks(tenant_code: str, doc_id: int) -> int:
    with connect() as conn, conn.cursor() as cur:
        cur.execute("DELETE FROM kb_chunk WHERE tenant_code = %s AND doc_id = %s", (tenant_code, doc_id))
        return cur.rowcount


def list_chunks(tenant_code: str, doc_id: int, limit: int = 200) -> list[dict]:
    with connect() as conn, conn.cursor() as cur:
        cur.execute(
            """
            SELECT id, doc_id, chunk_no, content, char_count, token_count, embedding_model, create_time
              FROM kb_chunk
             WHERE tenant_code = %s AND doc_id = %s
             ORDER BY chunk_no
             LIMIT %s
            """,
            (tenant_code, doc_id, limit),
        )
        return cur.fetchall()


def search(tenant_code: str, vector: list[float], top_k: int = 5, doc_ids: list[int] | None = None) -> list[dict]:
    """按余弦相似度检索：走 HNSW 索引，返回相似度降序的切片。"""
    if not vector:
        return []
    literal = to_pgvector(vector)
    sql = """
        SELECT c.id, c.doc_id, c.chunk_no, c.content, c.embedding_model,
               d.title AS doc_title, d.status AS doc_status,
               1 - (c.embedding <=> %s::vector) AS score
          FROM kb_chunk c
          JOIN kb_document d ON d.id = c.doc_id AND d.tenant_code = c.tenant_code
         WHERE c.tenant_code = %s
           AND c.embedding IS NOT NULL
           AND d.is_deleted = FALSE
           AND d.status = 3
    """
    params: list = [literal, tenant_code]
    if doc_ids:
        sql += " AND c.doc_id = ANY(%s)"
        params.append(doc_ids)
    sql += " ORDER BY c.embedding <=> %s::vector LIMIT %s"
    params.extend([literal, max(1, min(top_k, 50))])
    with connect() as conn, conn.cursor() as cur:
        # HNSW 是"先按向量取 K 个、再按 WHERE 过滤"（后过滤）。
        # 多租户同库时，别的租户的切片会占掉候选名额，导致本租户召回不足。
        # 把 ef_search 调大能明显缓解；彻底解决要按租户分区或独立表（第 27 篇再展开）。
        cur.execute("SET LOCAL hnsw.ef_search = 200")
        cur.execute(sql, params)
        rows = cur.fetchall()
    for row in rows:
        row["score"] = round(float(row["score"]), 4)
        row["create_time"] = str(row.get("create_time") or "")
    return rows


# 中文没有分词器时的通用做法：把查询切成"相邻两字"的片段（bigram）逐个匹配。
# 例："退货多久能到账" → 退货 / 货多 / 多久 / 久能 / 能到 / 到账
# 只要文档里出现"到账"，这条查询就能把它捞出来——
# 比"拿整句话当子串去找"（ILIKE '%退货多久能到账%'，几乎必然 0 条）实用得多。
_CJK_RUN = re.compile(r"[\u4e00-\u9fff]+")
_ASCII_RUN = re.compile(r"[A-Za-z0-9]+")
MAX_QUERY_TERMS = 20


def query_terms(query: str, max_terms: int = MAX_QUERY_TERMS) -> list[str]:
    """把查询拆成可匹配的片段：中文取相邻两字，英文数字取整词，去重后最多 20 个。"""
    text = (query or "").strip()
    if not text:
        return []
    terms: list[str] = []
    for run in _ASCII_RUN.findall(text.lower()):
        if len(run) >= 2:
            terms.append(run)
    for run in _CJK_RUN.findall(text):
        for index in range(len(run) - 1):
            terms.append(run[index:index + 2])
    seen: set[str] = set()
    result: list[str] = []
    for term in terms:
        if term not in seen:
            seen.add(term)
            result.append(term)
    return result[:max_terms]


def keyword_search(tenant_code: str, query: str, top_k: int = 5) -> list[dict]:
    """关键词兜底检索：把查询拆成片段，命中越多排得越前。

    什么时候用：没配向量密钥（本地兜底向量没有语义），或者向量检索一条都没命中。
    返回的 score 是"命中片段数 / 查询片段数"，所以是 0~1 之间的匹配度，不是余弦相似度。
    """
    terms = query_terms(query)
    if not terms:
        return []
    hit_case = " + ".join(["CASE WHEN c.content ILIKE %s THEN 1 ELSE 0 END"] * len(terms))
    hit_where = " OR ".join(["c.content ILIKE %s"] * len(terms))
    patterns = [f"%{term}%" for term in terms]
    sql = f"""
        SELECT c.id, c.doc_id, c.chunk_no, c.content, c.embedding_model,
               d.title AS doc_title, d.status AS doc_status,
               ({hit_case})::float / %s AS score
          FROM kb_chunk c
          JOIN kb_document d ON d.id = c.doc_id AND d.tenant_code = c.tenant_code
         WHERE c.tenant_code = %s
           AND d.is_deleted = FALSE
           AND d.status = 3
           AND ({hit_where})
         ORDER BY score DESC, c.doc_id DESC, c.chunk_no
         LIMIT %s
    """
    params = patterns + [len(terms), tenant_code] + patterns + [max(1, min(top_k, 50))]
    with connect() as conn, conn.cursor() as cur:
        cur.execute(sql, params)
        rows = cur.fetchall()
    for row in rows:
        row["score"] = round(float(row["score"]), 4)
        row["create_time"] = str(row.get("create_time") or "")
    return rows


def ping() -> bool:
    """探活：给健康检查用。"""
    try:
        with connect() as conn, conn.cursor() as cur:
            cur.execute("SELECT 1 AS ok")
            return bool(cur.fetchone())
    except Exception as exc:  # noqa: BLE001
        logger.warning("知识库探活失败：%s", exc)
        return False
