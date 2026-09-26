"""向量化：把文本变成定长向量。

两条路：
    1. **真实向量**：配了千问密钥就走 DashScope 的 OpenAI 兼容 ``/embeddings``
       接口（``text-embedding-v3``，默认 1024 维）。这是生产用法——语义相近的句子
       向量也相近，检索才准。
    2. **本地兜底**：没配密钥时用"分词 + 哈希"生成一个确定性的 1024 维向量。
       它只有字面匹配能力（同样的词才会靠近），**语义检索效果很差**，
       仅用于本地把"上传 → 解析 → 切块 → 索引 → 检索"整条链路跑通。
       返回结果里会带 ``source`` 字段，前端会明确标出"当前是本地兜底向量"。

维度固定 1024，和 ``kb_chunk.embedding vector(1024)`` 对齐。换模型（维度变了）
要重建索引，所以每块都记了 ``embedding_model``。
"""

from __future__ import annotations

import hashlib
import logging
import math
import re
import time

import httpx

from ..config import get_settings

logger = logging.getLogger(__name__)

EMBEDDING_DIM = 1024
# 一次最多送多少条：千问批量接口有条数上限，分批送更稳
BATCH_SIZE = 10
# 中文按字切、英文数字按词切；再用相邻字组成 bigram，弥补没有分词的短板
_TOKEN = re.compile(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]")


def embed_texts(texts: list[str], *, tenant_code: str = "", trace_id: str = "") -> tuple[list[list[float]], str]:
    """把一批文本向量化。

    @return (向量列表, 向量来源) —— 来源取值 ``qwen:text-embedding-v3`` 或 ``local-hash``
    """
    if not texts:
        return [], "none"
    settings = get_settings()
    # 没单独配向量密钥时复用千问的 key（同一个账号），避免"明明配了密钥却还在用兜底向量"
    if settings.embedding_key() and settings.embedding_provider != "local":
        try:
            vectors = _embed_remote(texts, settings, tenant_code=tenant_code, trace_id=trace_id)
            return vectors, f"{settings.embedding_provider}:{settings.embedding_model}"
        except Exception as exc:  # noqa: BLE001
            # 远程失败不能把整个上传卡死：退回本地向量，并把原因记下来
            logger.warning(
                "远程向量化失败，降级为本地向量 trace=%s error=%s: %s\n"
                "  → 本次写进去的向量没有语义，检索会退化成关键词匹配，效果明显变差。\n"
                "  → 修好密钥（YUNTI_AI_QWEN_API_KEY 或 YUNTI_AI_EMBEDDING_API_KEY）后，"
                "要把文档**重新索引**一遍——向量是索引那一刻算出来的。",
                trace_id or "-", type(exc).__name__, exc)
    return [_local_vector(text) for text in texts], "local-hash"


def _embed_remote(texts: list[str], settings, *, tenant_code: str, trace_id: str) -> list[list[float]]:
    url = settings.embedding_base_url.rstrip("/") + "/embeddings"
    # 关键：这里必须用 embedding_key()（专用密钥没配时复用千问的 key）。
    # 早先这里直接取 embedding_api_key，于是出现一个"静默失败"：
    #   embed_texts 判断"有密钥"（复用了千问的）→ 真发请求时 Authorization 却是空的
    #   → 401 → 被下面的兜底接住 → 悄悄换成 local-hash 向量。
    #   现象就是"知识库明明有数据，机器人却答不上来"（检索捞错了块）。
    api_key = settings.embedding_key()
    headers = {
        "Authorization": f"Bearer {api_key}",
        "Content-Type": "application/json",
    }
    vectors: list[list[float]] = []
    with httpx.Client(timeout=settings.embedding_timeout) as client:
        for start in range(0, len(texts), BATCH_SIZE):
            batch = texts[start:start + BATCH_SIZE]
            body = {"model": settings.embedding_model, "input": batch, "dimensions": EMBEDDING_DIM}
            started = time.perf_counter()
            logger.info("向量化请求 trace=%s tenant=%s model=%s texts=%d",
                        trace_id or "-", tenant_code or "-", settings.embedding_model, len(batch))
            response = client.post(url, headers=headers, json=body)
            response.raise_for_status()
            payload = response.json()
            items = sorted(payload.get("data", []), key=lambda item: item.get("index", 0))
            for item in items:
                vector = [float(value) for value in item.get("embedding", [])]
                if len(vector) != EMBEDDING_DIM:
                    raise ValueError(f"向量维度不符：期望 {EMBEDDING_DIM}，实际 {len(vector)}")
                vectors.append(vector)
            logger.info("向量化完成 trace=%s tenant=%s 本批=%d 累计=%d cost=%dms",
                        trace_id or "-", tenant_code or "-", len(batch), len(vectors),
                        int((time.perf_counter() - started) * 1000))
    if len(vectors) != len(texts):
        raise ValueError(f"向量条数不符：期望 {len(texts)}，实际 {len(vectors)}")
    return vectors


def _tokens(text: str) -> list[str]:
    """切词：中文单字 + 相邻二字组合（bigram），英文数字整词。"""
    units = _TOKEN.findall((text or "").lower())
    tokens = list(units)
    for i in range(len(units) - 1):
        left, right = units[i], units[i + 1]
        if len(left) == 1 and len(right) == 1:
            tokens.append(left + right)
    return tokens


def _local_vector(text: str) -> list[float]:
    """本地确定性向量：分词 → 哈希到固定维度 → 累加 → 归一化。

    同一段文字永远得到同一个向量，所以"同样的词"能互相匹配；
    但它不理解语义（"退款"和"退钱"不会靠近），仅作兜底。
    """
    vector = [0.0] * EMBEDDING_DIM
    tokens = _tokens(text)
    if not tokens:
        return vector
    for token in tokens:
        digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
        value = int.from_bytes(digest, "big")
        index = value % EMBEDDING_DIM
        sign = 1.0 if (value >> 63) & 1 == 0 else -1.0
        vector[index] += sign
    norm = math.sqrt(sum(value * value for value in vector))
    if norm > 0:
        vector = [value / norm for value in vector]
    return vector


def to_pgvector(vector: list[float]) -> str:
    """转成 pgvector 认的字面量：'[0.1,0.2,...]'。"""
    return "[" + ",".join(f"{value:.7f}" for value in vector) + "]"
