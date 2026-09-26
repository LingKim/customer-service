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
