"""文档切块：把长文档切成适合检索和送进大模型的片段。

做法和 LlamaIndex 的 ``SentenceSplitter`` 一致：
    1. 先按"最自然的边界"切——段落 > 换行 > 句号/问号/感叹号 > 分号 > 逗号 > 空格 > 单字；
    2. 切出来的碎片再按顺序拼回接近 ``chunk_size`` 的块；
    3. 相邻块之间留一段重叠（``chunk_overlap``），避免一句话正好被切断、两边都检索不到。

为什么不按固定字数硬切：中文一句话里断在中间，检索时"这半句"和"那半句"都不完整，
召回质量会明显下降。
"""

from __future__ import annotations

import re
from dataclasses import dataclass

# 从"最想切"到"最不想切"排列：能按段落切就别按句号切，能按句号切就别按字切
DEFAULT_SEPARATORS = ["\n\n", "\n", "。", "！", "？", "；", ". ", "! ", "? ", "; ", "，", ", ", " ", ""]

# 一个汉字大约 1 个 token，英文按 4 个字符 1 个 token 粗估
_ASCII_RUN = re.compile(r"[A-Za-z0-9]+")


@dataclass
class Chunk:
    """一个切片：正文 + 序号 + 统计信息。"""

    index: int
    content: str
    char_count: int
    token_count: int


def estimate_tokens(text: str) -> int:
    """粗估 token 数：汉字按 1 个算，连续英文/数字按 4 个字符 1 个算。"""
    if not text:
        return 0
    ascii_chars = sum(len(match.group(0)) for match in _ASCII_RUN.finditer(text))
    cjk_chars = len(text) - ascii_chars
    return cjk_chars + max(1, ascii_chars // 4) if ascii_chars else cjk_chars


def normalize_text(text: str) -> str:
    """切块前统一空行：连续空行压成一个，避免切出一堆空块。"""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def split_text(
    text: str,
    *,
    chunk_size: int = 600,
    chunk_overlap: int = 80,
    separators: list[str] | None = None,
) -> list[Chunk]:
    """把文本切成若干块。``chunk_size`` 与 ``chunk_overlap`` 都是字符数。"""
    if chunk_size < 100:
        chunk_size = 100
    if chunk_overlap < 0:
        chunk_overlap = 0
    if chunk_overlap >= chunk_size:
        chunk_overlap = chunk_size // 4

    cleaned = normalize_text(text)
    if not cleaned:
        return []

    seps = separators or DEFAULT_SEPARATORS
    pieces = _split_recursive(cleaned, seps, chunk_size)
    merged = _merge(pieces, chunk_size, chunk_overlap)
    return [
        Chunk(index=i + 1, content=part, char_count=len(part), token_count=estimate_tokens(part))
        for i, part in enumerate(merged)
        if part.strip()
    ]


def _split_recursive(text: str, separators: list[str], chunk_size: int) -> list[str]:
    """递归切：先试第一个分隔符；切出来的碎片还太大，就换下一个分隔符继续切。"""
    if len(text) <= chunk_size or not separators:
        return [text]

    separator, rest = separators[0], separators[1:]
    if separator == "":
        # 最后兜底：真遇到没有任何边界的长串（比如一长串 base64），按字数硬切
        return [text[i:i + chunk_size] for i in range(0, len(text), chunk_size)]

    parts = text.split(separator)
    if len(parts) == 1:
        return _split_recursive(text, rest, chunk_size)

    result: list[str] = []
    for index, part in enumerate(parts):
        # 把分隔符补回去，切出来的块要保留原文标点
        piece = part + separator if index < len(parts) - 1 else part
        if len(piece) <= chunk_size:
            result.append(piece)
        else:
            result.extend(_split_recursive(piece, rest, chunk_size))
    return result


def _merge(pieces: list[str], chunk_size: int, chunk_overlap: int) -> list[str]:
    """把小碎片拼成接近 chunk_size 的块，并在块之间留重叠。"""
    chunks: list[str] = []
    buffer = ""
    for piece in pieces:
        if not piece:
            continue
        if len(buffer) + len(piece) <= chunk_size:
            buffer += piece
            continue
        if buffer.strip():
            chunks.append(buffer.strip())
        # 新块用上一块的尾巴做重叠：一句话跨块时，两边都能检索到
        overlap = _tail(buffer, chunk_overlap) if chunk_overlap else ""
        buffer = (overlap + piece) if len(overlap) + len(piece) <= chunk_size else piece
        # 单个碎片本身就超长（前面硬切过），直接自成一块
        while len(buffer) > chunk_size:
            chunks.append(buffer[:chunk_size].strip())
            buffer = _tail(buffer[:chunk_size], chunk_overlap) + buffer[chunk_size:]
    if buffer.strip():
        chunks.append(buffer.strip())
    return chunks


def _tail(text: str, size: int) -> str:
    """取尾部 size 个字符，尽量从句子边界开始，读起来不会突然断半句。"""
    if size <= 0 or not text:
        return ""
    tail = text[-size:]
    for mark in ("。", "！", "？", "；", "\n", "，"):
        position = tail.find(mark)
        if 0 <= position < len(tail) // 2:
            return tail[position + 1:]
    return tail
