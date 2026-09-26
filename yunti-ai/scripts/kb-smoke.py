"""知识库自检：不连数据库、不起服务，先把"解析 → 切块 → 向量化"跑一遍。

用途：装完依赖（``pip install -r requirements-ai.txt``）后先跑它，
确认用的是 LlamaIndex 还是内置实现、切片切得对不对。

跑法：
    cd yunti-ai
    .venv/bin/python scripts/kb-smoke.py
"""

from __future__ import annotations

import io
import os
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from ai.rag import embedding, llama_engine, parser, pipeline, splitter  # noqa: E402

SAMPLE = """退款政策说明

一、适用范围
本政策适用于在本平台完成支付的所有订单，包含实物商品与虚拟服务。

二、退款条件
订单支付后 7 天内可以申请无理由退款；超过 7 天需要提供质量问题凭证。

三、到账时间
审核通过后 1-3 个工作日退回原支付渠道；信用卡支付最长不超过 15 个工作日。
"""


def build_docx(text: str) -> bytes:
    paragraphs = "".join(f"<w:p><w:r><w:t>{line}</w:t></w:r></w:p>"
                         for line in text.split("\n") if line.strip())
    xml = ('<?xml version="1.0" encoding="UTF-8"?>'
           '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
           f'<w:body>{paragraphs}</w:body></w:document>')
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("[Content_Types].xml", "<Types/>")
        archive.writestr("word/document.xml", xml)
    return buffer.getvalue()


def main() -> None:
    print(f"1) 当前实现：{pipeline.engine_name()}"
          f"（装了 llama-index 就是 llama-index，否则是内置实现）")
    if llama_engine.available():
        print("   LlamaIndex 依赖检查：通过")
    else:
        print("   提示：想用 LlamaIndex 就执行 pip install -r requirements-ai.txt")

    text = parser.normalize(parser.parse("退款政策.docx", build_docx(SAMPLE)))
    print(f"2) docx 解析：{len(text)} 字")
    for line in text.split("\n")[:3]:
        print(f"   | {line}")

    chunks = splitter.split_text(text, chunk_size=120, chunk_overlap=20)
    print(f"3) 切块：{len(chunks)} 块")
    for chunk in chunks:
        print(f"   | 第{chunk.index}块 {chunk.char_count}字 约{chunk.token_count}tok："
              f"{chunk.content[:36].replace(chr(10), ' ')}")

    vectors, source = embedding.embed_texts([chunk.content for chunk in chunks])
    print(f"4) 向量化：{len(vectors)} 条 × {len(vectors[0])} 维，来源 {source}")
    if source == "local-hash":
        print("   提示：没配 embedding 密钥，当前是本地兜底向量（只能字面匹配）；"
              "配 YUNTI_AI_EMBEDDING_API_KEY 后走真实语义向量")
    print("\n自检通过：解析、切块、向量化三步都正常。")


if __name__ == "__main__":
    main()
