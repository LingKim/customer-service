"""知识检索骨架：文档索引 / 向量检索。

未安装 llama-index 时返回空结果；安装 requirements-ai.txt 后实现
「切块 -> 向量化 -> 向量库（按 tenant 分区）-> 检索 -> 重排」。
"""

from __future__ import annotations

from typing import Any

def retrieve(query: str, tenant_code: str = "", top_k: int = 3) -> list[dict[str, Any]]:
    try:
        from llama_index.core import VectorStoreIndex  # noqa: F401
    except Exception:
        return []
    # TODO: 按 tenant_code 检索对应向量集合
    return []
