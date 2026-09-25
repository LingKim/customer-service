---
title: "20 企业知识库：文档解析、切块与向量索引（LlamaIndex 实战）(一)"
source: "https://articles.zsxq.com/id_2lhublkrb4sl.html"
author:
  - "[[苏三]]"
published:
created: 2026-09-20
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

## 一、项目概述

### 1.1 功能范围

前面十几篇把"人怎么服务人"做完了：渠道接进来、坐席派得准、消息必达、说错话当场拦。

但客户问的很多问题其实**公司早就写过答案**——退款政策、物流时效、发票怎么开、价保怎么算。

这些资料现在躺在共享盘和 Word 里，客服只能靠翻文档或者问老同事。

这一篇把企业资料变成**机器人能查的知识库**，也是后面 RAG 问答（第 21 篇）的地基：

1.  **文档解析**：txt / md / csv / html 直读，docx 解 zip+XML，PDF 走 pypdf——一份资料传上来就是纯文本；

2.  **切块**：按段落 → 换行 → 句号层层递归切分，块之间留重叠，避免一句话被切断两边都检索不到；

3.  **向量索引**：切块 → 向量化 → 写进 PostgreSQL 的 pgvector，建 HNSW 余弦索引；

4.  **LlamaIndex 实战**：读文件用 `SimpleDirectoryReader`、切块用 `SentenceSplitter`、向量化用 `OpenAIEmbedding`；没装依赖时自动退回内置实现，服务不会因为少一个包起不来；

5.  **一个库两种视角**：`kb_document` 是"人看的"（标题、分类、状态、原文件），`kb_chunk` 是"检索用的"（切片 + 向量）；

6.  **看得见才有用**：切片预览能看到每块切成了什么，检索测试能问一句看命中哪块、相似度多少。

### 1.2 协议与数据模型变化

HTTP（新增，都是给 customer-service 调的内部接口）：

|        |                                     |                                                   |
|--------|-------------------------------------|---------------------------------------------------|
| 方法   | 路径                                | 说明                                              |
| POST   | /api/ai/v1/kb/index                 | 请求体是文件原始字节，解析 + 切块 + 向量化 + 落库 |
| POST   | /api/ai/v1/kb/index-text            | 手工正文：不用解析文件，直接切块索引              |
| POST   | /api/ai/v1/kb/search                | 检索：返回命中的切片与相似度                      |
| GET    | /api/ai/v1/kb/documents/{id}/chunks | 切片列表                                          |
| DELETE | /api/ai/v1/kb/documents/{id}        | 删除某文档的全部切片                              |
| GET    | /api/ai/v1/kb/health                | 探活：库通不通、用的是哪套实现                    |

HTTP（对浏览器暴露，走网关）：

|      |                                         |                                  |
|------|-----------------------------------------|----------------------------------|
| 方法 | 路径                                    | 说明                             |
| GET  | /api/customer/kb/documents              | 文档列表（分类 / 状态 / 关键词） |
| POST | /api/customer/kb/documents/upload       | 上传文件：存对象存储 + 建索引    |
| POST | /api/customer/kb/documents/{id}/reindex | 重新索引                         |
| GET  | /api/customer/kb/documents/{id}/chunks  | 切片预览                         |
| POST | /api/customer/kb/search                 | 检索测试                         |

数据模型：

|              |           |                                                                             |
|--------------|-----------|-----------------------------------------------------------------------------|
| 表           | 变化      | 作用                                                                        |
| kb\_chunk    | 新表      | 切片正文 + `vector(1024)` 向量 + 切片号 / 字数 / token 数                   |
| kb\_document | 新增 5 列 | 切片数、索引状态（未索引/索引中/已索引/失败）、失败原因、原文件名、文件大小 |

索引：`kb_chunk` 建 HNSW 余弦索引（`vector_cosine_ops`），检索走它。

### 1.3 本篇怎么读（增量教程）

这一篇**接着第 19 篇写**，改动前的版本就是第 19 篇验证过的那一份（备份项目）。规则和前两篇一致：

-   **完整文件**：全新文件给完整内容直接新建；

-   **改动文件**：只给「原来 → 改成」两段，把文件里对应的那段换成新的即可；

-   **复制**：`resources/schema/customer_db_kb.sql` 是根目录同名脚本的副本，直接拷过去；

-   **片段**：`schema/customer_db.sql` 只核对几行——老库跑增量脚本就行，新建库才需要同步这几行。

## 二、环境准备

``` code-block-container
java -version    # 21
mvn -v           # 3.8+
psql --version   # PostgreSQL 17（要带 pgvector 扩展）
cd yunti-ai && .venv/bin/python -V
```

**先确认 pgvector 装上了**（向量索引就靠它）：

``` code-block-container
psql -h 127.0.0.1 -U mac -d customer_db -c "select extversion from pg_extension where extname = 'vector';"
# 没装的话：schema/customer_db_kb.sql 里第一句就是 CREATE EXTENSION IF NOT EXISTS vector;
# 需要数据库超级用户执行（Postgres.app 的 mac 用户就是）
```

**Python 依赖**：知识库主实现走 LlamaIndex，装一次就行（不装也能跑，会自动退回内置实现）。清单里几个包各自的用途：`llama-index` 本体、`llama-index-readers-file` 读 docx/pdf、`docx2txt`（DocxReader 的依赖，\*\*缺了传 Word 会报 \*\*`docx2txt is required`）、`pypdf`（中文 PDF 是 CID 编码字体，只能靠它读）、`llama-index-embeddings-openai`（OpenAI 系模型的向量化）：

``` code-block-container
cd yunti-ai
pip install -r requirements-ai.txt

# 装完先自检一下：会打印用的是哪套实现、解析/切块/向量化是否正常
.venv/bin/python scripts/kb-smoke.py
```

**数据库增量脚本**：

``` code-block-container
cd /Users/mac/Documents/ai-test/cc/customer-service
psql -h 127.0.0.1 -U mac -d customer_db -f schema/customer_db_kb.sql
# 或者一条命令补齐所有增量脚本：bash scripts/migrate-customer-db.sh
```

## 三、数据库改动

三件事：打开 **pgvector**、建 **kb\_chunk**（切片 + 向量）、给 **kb\_document** 补上"切了多少块、索引成功了没"。

### 文件：schema/customer\_db\_kb.sql

新增文件：知识库的增量脚本（可重复执行）。

``` code-block-container
-- 企业知识库：文档解析、切块与向量索引（可重复执行）
--   1. 打开 pgvector 扩展；
--   2. 新增 kb_chunk：一个文档切出来的每一块，带向量；
--   3. 补两个索引：按文档取切片、按向量做相似度检索（HNSW 余弦）。
--
-- 为什么切片单独一张表：文档（kb_document）是"人看的"，切片（kb_chunk）是"检索用的"。
-- 一份文档切几十上百块很正常，放一张表里查询会互相拖累；分开之后，
-- 文档列表只查文档表，检索只打切片表 + 向量索引。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS "kb_chunk" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "chunk_no" INT NOT NULL,
  "content" TEXT NOT NULL,
  "char_count" INT NOT NULL DEFAULT 0,
  "token_count" INT NOT NULL DEFAULT 0,
  "embedding" vector(1024),
  "embedding_model" VARCHAR(64) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "kb_chunk" IS '知识文档切片表（检索的最小单位）';
COMMENT ON COLUMN "kb_chunk"."doc_id" IS '所属文档ID';
COMMENT ON COLUMN "kb_chunk"."chunk_no" IS '文档内切片序号（从 1 开始）';
COMMENT ON COLUMN "kb_chunk"."content" IS '切片正文';
COMMENT ON COLUMN "kb_chunk"."char_count" IS '字符数（切块时统计，用于展示）';
COMMENT ON COLUMN "kb_chunk"."token_count" IS '估算 token 数（按 1 汉字≈1 token 粗估）';
COMMENT ON COLUMN "kb_chunk"."embedding" IS '向量（1024 维，千问 text-embedding-v3 默认维度）';
COMMENT ON COLUMN "kb_chunk"."embedding_model" IS '生成这个向量的模型（换模型要重建索引）';

-- 按文档取切片：切片预览、重建索引都要用
CREATE INDEX IF NOT EXISTS "idx_kb_chunk_tenant_doc"
    ON "kb_chunk" ("tenant_code", "doc_id", "chunk_no");

-- 向量检索：HNSW + 余弦距离（pgvector 0.5+ 才支持 HNSW）
CREATE INDEX IF NOT EXISTS "idx_kb_chunk_embedding"
    ON "kb_chunk" USING hnsw ("embedding" vector_cosine_ops)
    WHERE "embedding" IS NOT NULL;

-- 文档表补两列：切了多少块、最后索引进度（前端列表直接展示，不用再 count）
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "chunk_count" INT NOT NULL DEFAULT 0;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "index_status" SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "index_message" VARCHAR(255) DEFAULT NULL;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "file_name" VARCHAR(255) DEFAULT NULL;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "file_size" BIGINT DEFAULT NULL;

COMMENT ON COLUMN "kb_document"."chunk_count" IS '切片数（向量化完成后回填）';
COMMENT ON COLUMN "kb_document"."index_status" IS '索引状态：1-未索引、2-索引中、3-已索引、4-索引失败';
COMMENT ON COLUMN "kb_document"."index_message" IS '索引失败原因 / 最近一次索引说明';
COMMENT ON COLUMN "kb_document"."file_name" IS '原始文件名（导入的文档）';
COMMENT ON COLUMN "kb_document"."file_size" IS '文件大小（字节）';

-- 知识库上传暴露的老问题：file_meta.mime_type 只有 varchar(64)，
-- 而 Office 文档的标准 MIME 比它长（docx 71、xlsx 65、pptx 73），
-- 一上传 Word 就报 "value too long for type character varying(64)"。
ALTER TABLE "file_meta" ALTER COLUMN "mime_type" TYPE VARCHAR(128);
COMMENT ON COLUMN "file_meta"."mime_type" IS '文件 MIME 类型（Office 文档的 MIME 比较长，留到 128）';
```

### 片段：schema/customer\_db.sql

这个文件是建库用的全量脚本：**老库不用管它**（跑上面的增量脚本就行）；如果你是按它新建库，把下面几行同步加进去即可（用 `...` 省略的是原有内容）：

``` code-block-container
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE "kb_chunk" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "chunk_no" INT NOT NULL,
  "content" TEXT NOT NULL,
  "char_count" INT NOT NULL DEFAULT 0,
  "token_count" INT NOT NULL DEFAULT 0,
  "embedding" vector(1024),
  "embedding_model" VARCHAR(64) DEFAULT NULL,
  ...
);
CREATE INDEX "idx_kb_chunk_tenant_doc" ON "kb_chunk" ("tenant_code", "doc_id", "chunk_no");
CREATE INDEX "idx_kb_chunk_embedding" ON "kb_chunk" USING hnsw ("embedding" vector_cosine_ops)
    WHERE "embedding" IS NOT NULL;
```

## 四、yunti-ai：文档解析、切块与向量索引

### 4.1 文档解析

企业的资料什么格式都有，先把它们统一成纯文本：

|                              |                                  |                                                             |
|------------------------------|----------------------------------|-------------------------------------------------------------|
| 格式                         | 怎么读                           | 说明                                                        |
| txt / md / csv / json / html | 直接解码（UTF-8 优先，退回 GBK） | html 额外去标签                                             |
| docx                         | **zip + XML**（标准库）          | docx 本质是压缩包，正文在 `word/document.xml` 的 `<w:t>` 里 |
| pdf                          | **pypdf**                        | 中文 PDF 多是 CID 编码字体，手写解析读不出来                |

不支持的格式（xlsx / png / doc）不给"解析失败"这种废话，直接告诉用户该怎么办。

### 文件：yunti-ai/ai/rag/parser.py

新增文件：文档解析。

``` code-block-container
"""文档解析：把上传的文件变成纯文本。

支持的范围（够覆盖企业知识库最常见的几类资料）：
    - 纯文本类：.txt / .md / .csv / .json / .log
    - 网页：.html / .htm（去掉标签）
    - Word：.docx（本质是 zip + XML，用标准库就能读）
    - PDF：尽力而为（只抽取文本，不解析排版；扫描件 PDF 抽不出内容，会明确报错）

生产上更省事的做法是装 ``pypdf`` / ``python-docx`` / ``unstructured``，这里为了
"不装额外依赖也能跑通"，全部用标准库实现。换库只要替换本文件的几个函数即可，
上层（切块、向量化、检索）完全不用动。
"""

from __future__ import annotations

import html
import io
import re
import zipfile
import zlib

# 纯文本类后缀
TEXT_SUFFIXES = {".txt", ".md", ".markdown", ".csv", ".json", ".log", ".sql", ".yaml", ".yml"}
HTML_SUFFIXES = {".html", ".htm"}

# 这些文件类型抽不出文字，直接给出可照做的提示
UNSUPPORTED_HINT = {
    ".xlsx": "Excel 请先另存为 CSV 再上传",
    ".xls": "Excel 请先另存为 CSV 再上传",
    ".doc": "老版 .doc 请另存为 .docx 再上传",
    ".ppt": "PPT 请导出为 PDF 或纯文本再上传",
    ".pptx": "PPT 请导出为 PDF 或纯文本再上传",
    ".png": "图片请先 OCR 成文字再上传",
    ".jpg": "图片请先 OCR 成文字再上传",
}


class ParseError(ValueError):
    """解析失败：原因要说人话，直接展示给上传的人看。"""


def suffix_of(file_name: str) -> str:
    name = (file_name or "").strip().lower()
    index = name.rfind(".")
    return name[index:] if index >= 0 else ""


def parse(file_name: str, payload: bytes) -> str:
    """按文件后缀选择解析方式，返回纯文本。"""
    suffix = suffix_of(file_name)
    if not payload:
        raise ParseError("文件是空的，没有可解析的内容")

    if suffix in TEXT_SUFFIXES:
        return _decode_text(payload)
    if suffix in HTML_SUFFIXES:
        return _strip_html(_decode_text(payload))
    if suffix == ".docx":
        return _read_docx(payload)
    if suffix == ".pdf":
        return _read_pdf(payload)

    hint = UNSUPPORTED_HINT.get(suffix)
    if hint:
        raise ParseError(f"暂不支持 {suffix} 文件：{hint}")
    # 不认识的后缀：按纯文本试一试，能解出字就收
    text = _decode_text(payload, strict=False)
    if text.strip():
        return text
    raise ParseError(f"暂不支持 {suffix or '这种'} 文件，请上传 txt / md / docx / pdf")


def _decode_text(payload: bytes, *, strict: bool = True) -> str:
    """文本解码：先试 UTF-8，再试 GBK（国内很多资料是 GBK 编码的）。"""
    for encoding in ("utf-8", "utf-8-sig", "gb18030", "gbk"):
        try:
            return payload.decode(encoding)
        except UnicodeDecodeError:
            continue
    if strict:
        raise ParseError("文本编码无法识别，请另存为 UTF-8 后重试")
    return payload.decode("utf-8", errors="ignore")


_TAG = re.compile(r"<(script|style)[^>]*>.*?</\1>", re.S | re.I)
_ANY_TAG = re.compile(r"<[^>]+>")


def _strip_html(text: str) -> str:
    """去标签：脚本和样式整段丢掉，其余标签换成空格，最后解 HTML 实体。"""
    text = _TAG.sub(" ", text)
    text = _ANY_TAG.sub(" ", text)
    text = html.unescape(text)
    return re.sub(r"[ \t\u00a0]+", " ", text)


def _read_docx(payload: bytes) -> str:
    """docx = zip 包，正文在 word/document.xml 里；<w:p> 是段落、<w:t> 是文字。"""
    try:
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            names = [name for name in archive.namelist() if name.startswith("word/") and name.endswith(".xml")]
            if "word/document.xml" not in archive.namelist():
                raise ParseError("这个 docx 里找不到正文（word/document.xml）")
            xml = archive.read("word/document.xml").decode("utf-8", errors="ignore")
            # 附件、批注等也一起收进来，避免漏掉正文之外的信息
            for name in names:
                if name in ("word/document.xml", "word/footnotes.xml", "word/endnotes.xml"):
                    continue
                if name.startswith(("word/header", "word/footer")):
                    xml += archive.read(name).decode("utf-8", errors="ignore")
    except zipfile.BadZipFile as exc:
        raise ParseError("docx 文件已损坏，请重新导出后再上传") from exc

    # 先把"结构标记"换成文本分隔符：段落换行、单元格空格、行结束换行
    xml = re.sub(r"</w:p>", "\n", xml)
    xml = re.sub(r"</w:tc>", " ", xml)
    xml = re.sub(r"</w:tr>", "\n", xml)
    xml = re.sub(r"<w:tab[^>]*/>", "\t", xml)
    xml = re.sub(r"<w:br[^>]*/>", "\n", xml)
    # 注意这个正则：必须是 <w:t> 或 <w:t 属性>，
    # 不能写成 <w:t[^>]*>——那样会把 <w:tbl>、<w:tblPr>、<w:tr> 这些也当成文字标签，
    # 结果表格的 XML 属性（<w:tblStyle w:val=...>）会被当成正文读进来。
    text_pattern = re.compile(r"<w:t(?:\s[^>]*)?>(.*?)</w:t>", re.S)
    paragraphs = []
    for chunk in xml.split("\n"):
        pieces = text_pattern.findall(chunk)
        if pieces:
            line = html.unescape("".join(pieces)).strip()
            line = re.sub(r"[ \t\u00a0]{2,}", " ", line)
            if line:
                paragraphs.append(line)
    if paragraphs:
        return "\n".join(paragraphs)
    return html.unescape("".join(text_pattern.findall(xml)))


_PDF_STREAM = re.compile(rb"stream\r?\n(.*?)\r?\nendstream", re.S)
_PDF_TEXT_ARRAY = re.compile(rb"\[(.*?)\]\s*TJ", re.S)
_PDF_TEXT_SHOW = re.compile(rb"\((?:[^()\\]|\\.)*\)\s*Tj", re.S)
_PDF_STRING = re.compile(rb"\((?:[^()\\]|\\.)*\)", re.S)
_PDF_ESCAPE = {b"n": b"\n", b"r": b"\r", b"t": b"\t", b"(": b"(", b")": b")", b"\\": b"\\"}


def _read_pdf(payload: bytes) -> str:
    """PDF 文本抽取：优先用 pypdf，没装才用下面那个手写兜底。

    为什么要分两层：PDF 里的文字可能是 ``(文字) Tj``（简单字体），也可能是
    ``<4E2D6587> Tj``（CID 字体的十六进制串，得配合字体的 CMap 才能还原）。
    后者手写解析要处理字体子集和编码表，属于不该自己造的轮子——
    reportlab、Word 导出的中文 PDF 基本都是这种。
    所以：装了 ``pypdf`` 就用它（``llama-index-readers-file`` 也会带上它），
    没装才退回手写实现，并明确提示"装 pypdf 就能读"。
    """
    try:
        from pypdf import PdfReader  # noqa: PLC0415

        reader = PdfReader(io.BytesIO(payload))
        text = "\n".join((page.extract_text() or "") for page in reader.pages)
        if text.strip():
            return text
        raise ParseError(
            "这个 PDF 里没有可提取的文字（可能是扫描件/图片版）。"
            "请改用文字版 PDF，或先把内容复制成 txt / md 再上传"
        )
    except ParseError:
        raise
    except ImportError:
        # 没装 pypdf：走下面的手写兜底
        pass
    except Exception as exc:  # noqa: BLE001
        raise ParseError(f"PDF 解析失败：{exc}") from exc

    return _read_pdf_fallback(payload)


def _read_pdf_fallback(payload: bytes) -> str:
    """手写 PDF 文本抽取：只认 ``(...) Tj`` / ``[...] TJ`` 这种简单字体。"""
    texts: list[str] = []
    for raw in _PDF_STREAM.findall(payload):
        data = raw
        try:
            data = zlib.decompress(raw)
        except zlib.error:
            try:
                data = zlib.decompressobj().decompress(raw)
            except zlib.error:
                data = raw
        if b"Tj" not in data and b"TJ" not in data:
            continue
        for match in _PDF_TEXT_ARRAY.finditer(data):
            texts.append(_pdf_strings(match.group(1), join=True))
        for match in _PDF_TEXT_SHOW.finditer(data):
            texts.append(_pdf_strings(match.group(0), join=False))
    text = "\n".join(part for part in texts if part.strip())
    if not text.strip():
        raise ParseError(
            "这个 PDF 里的文字抽不出来。常见原因有两个：\n"
            "1) 是扫描件/图片版 —— 请先把内容复制成 txt / md 再上传；\n"
            "2) 是文字版但用了 CID 编码字体（Word / reportlab 导出的中文 PDF 多为这种）"
            "—— 装一下 pypdf 即可：pip install pypdf"
        )
    return text


def _pdf_strings(raw: bytes, *, join: bool) -> str:
    """把 ``(...)`` 里的字符串还原成可读文本，并按需去掉断字连字符。"""
    pieces = []
    for match in _PDF_STRING.finditer(raw):
        body = match.group(0)[1:-1]
        for escaped, plain in _PDF_ESCAPE.items():
            body = body.replace(b"\\" + escaped, plain)
        body = re.sub(rb"\\([0-7]{1,3})", lambda m: bytes([int(m.group(1), 8) & 0xFF]), body)
        pieces.append(body.decode("utf-8", errors="ignore"))
    if join:
        return "".join(pieces)
    return "".join(pieces) + "\n"


def normalize(text: str) -> str:
    """统一做一次清洗：去掉控制字符、压掉多余空行、去掉行尾空格。"""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    text = re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", text)
    lines = [line.rstrip() for line in text.split("\n")]
    text = "\n".join(lines)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()
```

### 4.2 切块

切块质量直接决定检索质量：切得太碎，一句话被拆成两半，两边都召回不到；切得太大，一块里塞了三个主题，检索出来噪音多。

做法和 LlamaIndex 的 `SentenceSplitter` 一致：递归降级切分（段落 → 换行 → 句号 → 分号 → 逗号 → 空格 → 单字），再把碎片拼回接近 `chunk_size` 的块，块之间留重叠。

### 文件：yunti-ai/ai/rag/splitter.py

新增文件：递归切块。

``` code-block-container
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
```

### 4.3 向量化

真实向量走千问的 OpenAI 兼容 `/embeddings`（`text-embedding-v3`，1024 维）；没配密钥时用"分词 + 哈希"生成确定性向量兜底——**它只有字面匹配能力**，仅用于把链路跑通，接口返回里会明确标 `local-hash`。

### 文件：yunti-ai/ai/rag/embedding.py

新增文件：向量化（远程优先、本地兜底）。

``` code-block-container
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
    if settings.embedding_api_key and settings.embedding_provider != "local":
        try:
            vectors = _embed_remote(texts, settings, tenant_code=tenant_code, trace_id=trace_id)
            return vectors, f"{settings.embedding_provider}:{settings.embedding_model}"
        except Exception as exc:  # noqa: BLE001
            # 远程失败不能把整个上传卡死：退回本地向量，并把原因记下来
            logger.warning("远程向量化失败，降级为本地向量 trace=%s error=%s: %s",
                           trace_id or "-", type(exc).__name__, exc)
    return [_local_vector(text) for text in texts], "local-hash"


def _embed_remote(texts: list[str], settings, *, tenant_code: str, trace_id: str) -> list[list[float]]:
    url = settings.embedding_base_url.rstrip("/") + "/embeddings"
    headers = {
        "Authorization": f"Bearer {settings.embedding_api_key}",
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
```

### 4.4 向量存取（pgvector）

为什么用 pgvector 而不是另起一个向量库：知识库本身就在 PostgreSQL 里，切片和向量放同一个库、同一个 `tenant_code` 维度下，租户隔离和备份策略都不用再想一套。

### 文件：yunti-ai/ai/rag/store.py

新增文件：切片存取与余弦检索。

``` code-block-container
"""向量存取：切片写进 pgvector、按相似度检索出来。

为什么用 pgvector 而不是另起一个向量库：知识库本身就在 PostgreSQL 里（kb_document），
把切片和向量放在同一个库、同一个 tenant_code 维度下，租户隔离和备份策略都不用再想一套。
数据量真上来了（千万级切片）再换 Milvus 之类的专用库也不迟，接口就下面这几个函数。
"""

from __future__ import annotations

import logging
import re
import threading
import time

import psycopg
from psycopg.rows import dict_row

from ..config import get_settings
from .embedding import to_pgvector

logger = logging.getLogger(__name__)

# 单进程内的自增序号：配合毫秒时间戳拼成 19 位雪花号（和 Java 侧的 id 风格保持一致）
_lock = threading.Lock()
_last_ms = 0
_sequence = 0


def next_id() -> int:
    """生成一个分布式 ID：毫秒时间戳左移 12 位 + 自增序号（单机够用，多实例也不会撞）。"""
    global _last_ms, _sequence
    with _lock:
        now = int(time.time() * 1000)
        if now == _last_ms:
            _sequence += 1
            if _sequence > 4095:
                time.sleep(0.001)
                now = int(time.time() * 1000)
                _sequence = 0
        else:
            _sequence = 0
        _last_ms = now
        # 时间戳左移 12 位 + 12 位自增：16 位数字，离 BIGINT 上限很远，不会撞号
        return (now << 12) | (_sequence & 0xFFF)


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
          JOIN kb_document d ON d.id = c.doc_id
         WHERE c.tenant_code = %s
           AND c.embedding IS NOT NULL
           AND d.is_deleted = FALSE
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
          JOIN kb_document d ON d.id = c.doc_id
         WHERE c.tenant_code = %s
           AND d.is_deleted = FALSE
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
```

### 4.5 LlamaIndex 版实现（这一节踩了三个坑）

这是本篇的"实战"部分：读文件交给 `SimpleDirectoryReader`、切块交给 `SentenceSplitter`。**写之前先假设它不是万能的**，三次实测踩出来的坑都写在这里：

**坑一：**`OpenAIEmbedding`\*\* 不认千问的模型名。\*\* 它用枚举校验模型名，只认 OpenAI 官方那 7 个（`text-embedding-ada-002`、`3-small`、`3-large`…）。塞 `text-embedding-v3` 直接抛：

``` code-block-container
ValueError: 'text-embedding-v3' is not a valid OpenAIEmbeddingModelType
```

所以向量化这一环按"模型名是不是 OpenAI 官方"分流：是 → 用 LlamaIndex 的 `OpenAIEmbedding`；不是（千问、自建兼容服务）→ 用本项目自己的客户端走同一个 `/embeddings` 协议。**"用 LlamaIndex"不等于"每一环都必须用它"**。

**坑二：它自己的 Reader 还要装小依赖。** 读 docx 要 `docx2txt`、读 pdf 要 `pypdf`，缺了会抛 `docx2txt is required`。README 里的依赖清单补上，同时读文件失败时**退回内置解析器**（并打日志说明原因），不让一篇文档因为少个包就整体索引失败。

**坑三：**`chunk_size`\*\* 的单位是 token，不是字符。\*\* 内置实现按字符算、LlamaIndex 按 token 算，同样填 600，切出来的块大小不一样（中文大致 1 token ≈ 1\~1.5 个字）。想让两边接近就把配置调小。

还有一处**故意没交给 LlamaIndex 托管**：`PGVectorStore` 会建它自己的表（data JSONB + embedding），而本项目的 `kb_chunk` 是定制的（切片号、字数、token 数），Java 侧的切片预览和检索结果都直接读这张表。所以是"用 LlamaIndex 出节点和向量，写进既有表"——把 LlamaIndex 接进已有系统的常规做法。

### 文件：yunti-ai/ai/rag/llama\_engine.py

新增文件：LlamaIndex 版实现。

``` code-block-container
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
        api_key=settings.embedding_api_key or "not-configured",
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
    return {"query": query, "results": results, "vector_source": source, "mode": mode}
```

### 4.6 流水线调度：两套实现自动择一

对外只暴露 `index_document` / `index_text` / `search`，里面判断"装没装 llama-index"决定用哪套。这样换实现不用改调用方，服务也不会因为少一个包起不来。

**检索这里连续踩了两个坑，值得单独说**：

**坑一**：没配 embedding 密钥时用的是本地兜底向量，它只有字面匹配能力。拿它去算余弦相似度，会返回一堆"看着像但没关系"的切片——**比没有结果更误导人**。所以这种情况不做向量检索，改走关键词。

**坑二（改完才发现的）**：关键词检索第一版是拿**整句话**当子串去匹配：`WHERE content ILIKE '%退货多久能到账%'`。文档里当然不会出现这一整句，于是"一条都搜不到"。

正确的做法是**把查询拆成片段再逐个匹配**（中文没有分词器时，取"相邻两字"就够用）：

``` code-block-container
查询：退货多久能到账？
片段：退货 / 货多 / 多久 / 久能 / 能到 / 到账

文档里其实没有"退货"这个词（写的是"退款"），但"到账"命中了——
按"命中片段数 ÷ 片段总数"排序，含"到账"的切片照样能被捞出来。
```

返回里的 `mode` 会标明这次是怎么检的：`vector`（真实向量）、`keyword-local-vector`（没配密钥走关键词）、`keyword`（向量没命中后的兜底）。前端据此把"相似度"改成"匹配度"，避免拿匹配度当相似度误判效果。

### 文件：yunti-ai/ai/rag/pipeline.py

新增文件：流水线调度 + 内置实现。

``` code-block-container
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

    logger.info("知识库检索 trace=%s tenant=%s query=%s 命中=%d 模式=%s 向量来源=%s",
                get_trace_id() or "-", tenant_code, query[:40], len(results), mode, source)
    return {
        "query": query,
        "results": results,
        "vector_source": source,
        "mode": mode,
    }


def _chunk_to_dict(chunk: splitter_module.Chunk) -> dict:
    return {
        "chunk_no": chunk.index,
        "char_count": chunk.char_count,
        "token_count": chunk.token_count,
        "content": chunk.content,
    }
```

### 4.7 检索入口

原来 `retriever.py` 是个空壳（"未安装 llama-index 时返回空结果"）。现在它是真实检索入口，后面第 21 篇的 RAG 问答直接调它。

### 改动：yunti-ai/ai/rag/retriever.py

改动点：从空壳改成真实检索；检索失败不抛异常，退化成"没有知识可用"。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 1 行附近）**

原来是这样：

``` code-block-container
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
```

改成：

``` code-block-container
"""知识检索入口：给对话机器人做 RAG 召回用。

这一层薄薄包一下 pipeline.search，好处是**调用方不用知道底层换了什么**：
今天是 pgvector，明天换成 Milvus 或者加上重排（rerank），这里改一行就行。

用法：
    from ai.rag.retriever import retrieve
    hits = retrieve("退款多久到账", tenant_code="T2026...", top_k=3)
"""

from __future__ import annotations

import logging
from typing import Any

from . import pipeline

logger = logging.getLogger(__name__)


def retrieve(query: str, tenant_code: str = "", top_k: int = 3) -> list[dict[str, Any]]:
    """检索知识切片；没配租户或没建索引时返回空列表（调用方按"没查到"处理即可）。"""
    if not query or not tenant_code:
        return []
    try:
        result = pipeline.search(tenant_code=tenant_code, query=query, top_k=top_k)
    except Exception as exc:  # noqa: BLE001
        # 检索失败不能把对话打挂：机器人退化成"没有知识可用"
        logger.warning("知识检索失败 tenant=%s query=%s error=%s: %s",
                       tenant_code, query[:40], type(exc).__name__, exc)
        return []
    return result.get("results", [])
```

### 4.8 知识库接口

文件走**原始字节**而不是 multipart：Python 侧不用装 python-multipart，Java 侧直接发 body 就行，少一层依赖。

### 文件：yunti-ai/ai/api/kb.py

新增文件：知识库接口。

``` code-block-container
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
import time

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from ..core.trace import require_trace_id
from ..rag import pipeline, store

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai/v1/kb", tags=["kb"])


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
    body: IndexTextRequest,
    tenant_code: str = Query(alias="tenant_code", min_length=1),
    trace_id: str = Depends(require_trace_id),
) -> dict:
    """手工录入的正文：不用解析文件，直接切块索引。"""
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
async def search(body: SearchRequest, trace_id: str = Depends(require_trace_id)) -> dict:
    """检索：给"检索测试"用，后续机器人 RAG 也走它。"""
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
    doc_id: int,
    tenant_code: str = Query(alias="tenant_code", min_length=1),
    limit: int = Query(default=200, ge=1, le=1000),
) -> dict:
    rows = store.list_chunks(tenant_code, doc_id, limit=limit)
    for row in rows:
        row["id"] = str(row["id"])
        row["doc_id"] = str(row["doc_id"])
        row["create_time"] = str(row.get("create_time") or "")
    return {"total": len(rows), "chunks": rows}


@router.delete("/documents/{doc_id}")
def delete_chunks(
    doc_id: int,
    tenant_code: str = Query(alias="tenant_code", min_length=1),
) -> dict:
    removed = store.delete_chunks(tenant_code, doc_id)
    logger.info("删除知识库切片 tenant=%s docId=%s 删除=%d", tenant_code, doc_id, removed)
    return {"deleted": removed}


@router.get("/health")
def kb_health() -> dict:
    """知识库探活：库连不上或没装 pgvector 时这里会是 DOWN。"""
    ok = store.ping()
    return {
        "status": "UP" if ok else "DOWN",
        "vectorStore": "pgvector",
        "table": "kb_chunk",
        # 说明当前用的是哪套实现：装了 llama-index 就是它，否则是内置兜底
        "engine": pipeline.engine_name(),
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
```

### 4.9 配置、注册与依赖

### 改动：yunti-ai/ai/config.py

改动点：加向量化与切块参数。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 34 行附近）**

原来是这样：

``` code-block-container
    deepseek_model: str = "deepseek-chat"

    redis_url: str = "redis://localhost:6379/0"
    kafka_bootstrap: str = "localhost:9092"
```

改成：

``` code-block-container
    deepseek_model: str = "deepseek-chat"

    # 向量化（知识库用）：默认走千问的 OpenAI 兼容 /embeddings 接口。
    # 不配 embedding_api_key 时自动降级为"本地哈希向量"——只能把链路跑通，语义效果差。
    embedding_provider: str = "qwen"
    embedding_api_key: str = ""
    embedding_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    embedding_model: str = "text-embedding-v3"
    embedding_timeout: int = 30

    # 知识库：切片参数与数据源（切片存在 customer_db，和文档表同库，租户隔离靠 tenant_code）
    kb_database_url: str = ""
    # 切块大小：内置实现按"字符"算，LlamaIndex 按"token"算（同数值粒度不同，可按需分别调）
    kb_chunk_size: int = 600
    kb_chunk_overlap: int = 80
    kb_max_file_mb: int = 20

    redis_url: str = "redis://localhost:6379/0"
    kafka_bootstrap: str = "localhost:9092"
```

### 改动：yunti-ai/ai/main.py

改动点：注册 kb 路由。

这个文件一共 2 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 10 行附近）**

原来是这样：

``` code-block-container
setup_logging()

from .api import bot, chat, health, qa  # noqa: E402

```

改成：

``` code-block-container
setup_logging()

from .api import bot, chat, health, kb, qa  # noqa: E402

```

**新增 2（第 24 行附近）**

原来是这样：

``` code-block-container
    app.include_router(bot.router, prefix=settings.api_prefix)
    app.include_router(qa.router, prefix=settings.api_prefix)
    return app
```

改成：

``` code-block-container
    app.include_router(bot.router, prefix=settings.api_prefix)
    app.include_router(qa.router, prefix=settings.api_prefix)
    app.include_router(kb.router, prefix=settings.api_prefix)
    return app
```

### 改动：yunti-ai/requirements-ai.txt

改动点：补 llama-index 相关依赖与 pypdf。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 2 行附近）**

原来是这样：

``` code-block-container
langgraph>=0.2
langchain-openai>=0.2
llama-index>=0.11
redis>=5
kafka-python>=2.2
```

改成：

``` code-block-container
langgraph>=0.2
langchain-openai>=0.2
# 知识库（第 20 篇）：解析 docx/pdf 要 readers-file，向量化要 embeddings-openai
llama-index>=0.11
llama-index-readers-file>=0.4
# LlamaIndex 读 docx 走 DocxReader，它内部依赖 docx2txt（不装会在读 docx 时报 'docx2txt is required'）
docx2txt>=0.8
llama-index-embeddings-openai>=0.3
pgvector>=0.3
# 中文 PDF 多为 CID 编码字体，内置兜底解析读不出来，pypdf 才能读
pypdf>=5
redis>=5
kafka-python>=2.2
```

### 改动：yunti-ai/.env.example

改动点：补向量化配置项。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 16 行附近）**

原来是这样：

``` code-block-container
YUNTI_AI_DEEPSEEK_MODEL=deepseek-chat
YUNTI_AI_REDIS_URL=redis://localhost:6379/0
YUNTI_AI_KAFKA_BOOTSTRAP=localhost:9092
```

改成：

``` code-block-container
YUNTI_AI_DEEPSEEK_MODEL=deepseek-chat
YUNTI_AI_REDIS_URL=redis://localhost:6379/0
YUNTI_AI_KAFKA_BOOTSTRAP=localhost:9092

# 知识库向量化（第 20 篇）：留空则用本地兜底向量（只能字面匹配，建议配上）
# 千问的 OpenAI 兼容接口和 LLM 用同一个 key
YUNTI_AI_EMBEDDING_PROVIDER=qwen
YUNTI_AI_EMBEDDING_API_KEY=
YUNTI_AI_EMBEDDING_MODEL=text-embedding-v3
# 切片参数（字符数 / 重叠字符数）
YUNTI_AI_KB_CHUNK_SIZE=600
YUNTI_AI_KB_CHUNK_OVERLAP=80
# 知识库所在库（留空则从 ai_db 推导成 customer_db）
YUNTI_AI_KB_DATABASE_URL=
```

依赖装在哪、怎么装，也一并写进 README 和初始化脚本——这一篇踩过一次"直接敲 pip 报 command not found"的坑（依赖全在 `.venv` 里）：

### 改动：yunti-ai/README.md

改动点：把安装命令改成 .venv 里的 pip，并补上国内换源与装完自检。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 72 行附近）**

原来是这样：

``` code-block-container
```bash
pip install -r requirements-ai.txt
```

当前未装 AI 依赖时，对话接口返回 **mock 回复**（代码里已留好接入点），保证骨架始终可运行。

## Java ↔ Python 通信约定（与后端对齐）

``` code-block-container
改成：

```markdown
```bash
# 注意：用项目自带的 .venv 里的 pip。直接敲 pip 会报 command not found
# （系统没有全局 pip，项目依赖全部装在 .venv 里）
cd yunti-ai

./.venv/bin/pip install -r requirements-ai.txt

# 国内网络慢或连不上 PyPI 时，换清华源：
# ./.venv/bin/pip install -r requirements-ai.txt -i https://pypi.tuna.tsinghua.edu.cn/simple

# 装完自检：确认用的是 llama-index 还是内置兜底实现
./.venv/bin/python scripts/kb-smoke.py
```

> 依赖没装齐时服务仍能启动（对话接口返回 **mock 回复**，知识库退回内置实现）， 但**知识库要真正走 LlamaIndex，必须装上** `requirements-ai.txt` **里的依赖**。

## Java ↔ Python 通信约定（与后端对齐）

``` code-block-container

### 改动：yunti-ai/scripts/bootstrap.sh
改动点：支持 --with-ai 一并安装 AI 依赖。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 14 行附近）**

原来是这样：

```bash
./.venv/bin/pip install -r requirements.txt

echo ""
echo "完成。请在 IDEA 中把项目解释器指向：$(pwd)/.venv/bin/python"
```

改成：

``` code-block-container
./.venv/bin/pip install -r requirements.txt

# 知识库 / Agent 的可选依赖（llama-index、langchain 等）体积较大，
# 默认不装；需要时显式加 --with-ai，或用 AI=1 bash scripts/bootstrap.sh
if [ "${AI:-0}" = "1" ] || [ "${1:-}" = "--with-ai" ]; then
  echo "[附加] 安装 AI 依赖（LlamaIndex / LangChain ...，体积较大，请耐心等待）..."
  ./.venv/bin/pip install -r requirements-ai.txt
else
  echo ""
  echo "提示：知识库要真正走 LlamaIndex，需要再装一次可选依赖："
  echo "    ./.venv/bin/pip install -r requirements-ai.txt"
  echo "或重跑本脚本并加参数：bash scripts/bootstrap.sh --with-ai"
fi

echo ""
echo "完成。请在 IDEA 中把项目解释器指向：$(pwd)/.venv/bin/python"
```

### 4.10 离线自检脚本

不连数据库、不起服务，先把"解析 → 切块 → 向量化"跑一遍，确认用的是哪套实现：

### 文件：yunti-ai/scripts/kb-smoke.py

新增文件：知识库离线自检。

``` code-block-container
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
```

##
