"""知识问答：把检索到的资料交给大模型，让它"照着资料回答 + 标出处"。

两个关键约束：
    1. **只依据资料回答**：提示词里明确"资料里没有的不要编"，并在结尾要求给出口径；
    2. **引用必须可核**：要求模型用 `[1]` `[2]` 标注依据，编号对应检索结果的序号，
       回答里出现越界编号（例如只给了 3 条资料却写 [5]）会在校验环节被剔掉。

没配大模型密钥时不给"假装回答"，而是**把最相关的原文摘出来 + 说明没配模型**——
宁可给用户看原文，也不要编一段像模像样的话。
"""

from __future__ import annotations

import logging
import re
from typing import Any

from ..config import get_settings
from ..core.llm_chat import call_chat, message_content, resolve_provider
from ..core.trace import get_trace_id

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """你是企业客服的知识助手。请严格根据下面提供的【资料】回答问题。

要求：
1. 用中文回答，简洁、口语化，像客服在跟客户讲话，一般 3 句话以内；
2. 每条结论后面用 [编号] 标出依据，例如：退款一般 1 个工作日到账[1]；
3. 资料里没有的内容**不要编**，直接说明"资料里没有提到"，并建议用户联系人工客服；
4. 直接输出回答本身，不要输出 Markdown 代码块，也不要解释推理过程。"""

# 把"第几块"告诉模型时用的序号起点
FIRST_CITATION_INDEX = 1


def build_prompt(question: str, hits: list[dict[str, Any]]) -> str:
    """把召回切片编号后拼成资料区。编号就是引用编号，两边必须一致。

    注意字段名：检索结果用的是**下划线命名**（``doc_title`` / ``chunk_no``，
    来自 SQL 的列别名，和 kb 模块其它接口一致），不是驼峰——
    这里写错过一次，结果回答里出现"《None》第 None 块"。
    """
    blocks = []
    for offset, hit in enumerate(hits):
        index = offset + FIRST_CITATION_INDEX
        title = hit.get("doc_title") or "未命名文档"
        blocks.append(f"[{index}] 《{title}》第 {hit.get('chunk_no')} 块：\n{hit.get('content') or ''}")
    materials = "\n\n".join(blocks) if blocks else "（没有检索到相关资料）"
    return f"【资料】\n{materials}\n\n【问题】\n{question}"


def _citations_of(hits: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """把召回结果转成"引用来源"结构，供前端展示与跳转。"""
    citations = []
    for offset, hit in enumerate(hits):
        citations.append({
            "index": offset + FIRST_CITATION_INDEX,
            # 保持和 kb 模块其它接口一致的下划线命名，Java 侧统一转驼峰给前端
            "chunk_id": str(hit.get("id") or ""),
            "doc_id": str(hit.get("doc_id") or ""),
            "doc_title": hit.get("doc_title") or "未命名文档",
            "chunk_no": hit.get("chunk_no"),
            "score": hit.get("score"),
            "content": hit.get("content") or "",
        })
    return citations


def extract_citation_indexes(answer: str) -> list[int]:
    """从回答里抠出引用编号，例如"…1 个工作日到账[1][2]" → [1, 2]。"""
    return sorted({int(match) for match in re.findall(r"\[(\d{1,2})\]", answer or "")})


def verify_citations(answer: str, citations: list[dict[str, Any]]) -> tuple[list[dict], list[int]]:
    """只保留"回答里真的引用了、且编号在范围内"的来源。

    @return (保留的来源, 被剔除的越界编号)
    """
    if not citations:
        return [], []
    valid = {int(item["index"]) for item in citations}
    used = extract_citation_indexes(answer)
    out_of_range = [index for index in used if index not in valid]
    if not used:
        # 未标出处时不能把全部召回结果伪装成回答依据。
        return [], []
    kept = [item for item in citations if int(item["index"]) in used]
    return kept, out_of_range


async def compose_answer(question: str, hits: list[dict[str, Any]],
                         tenant_code: str = "") -> tuple[str, list[dict[str, Any]]]:
    """生成回答与引用来源。

    @return (回答正文, 引用来源列表)
    """
    citations = _citations_of(hits)
    if not hits:
        return ("知识库里没有查到与这个问题相关的资料。可以先到「企业知识库」上传对应文档，"
                "或者转人工客服处理。"), []

    chosen, desc = resolve_provider(tenant_code)
    if chosen is None:
        # 没配密钥：不编答案，把最相关的原文摆出来
        top = hits[0]
        logger.warning("知识问答未配置大模型密钥，返回检索原文 tenant=%s question=%s",
                       tenant_code or "-", question[:40])
        return (f"（当前未配置大模型密钥，先把检索到的原文给你）\n\n"
                f"依据《{top.get('doc_title') or '未命名文档'}》第 {top.get('chunk_no')} 块：\n"
                f"{top.get('content') or ''}",
                citations)

    payload = {
        "model": chosen["model"],
        "temperature": 0.2,
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_prompt(question, hits)},
        ],
    }
    logger.info("知识问答请求 trace=%s tenant=%s 模型=%s 资料条数=%d",
                get_trace_id() or "-", tenant_code or "-", desc, len(hits))
    data = await call_chat(chosen, payload, get_settings().llm_timeout, scene="知识问答")
    answer = message_content(data)
    if not answer:
        raise RuntimeError("大模型返回了空回答")
    return answer, citations
