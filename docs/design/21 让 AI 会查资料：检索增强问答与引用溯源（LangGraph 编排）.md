---
title: "21 让 AI 会查资料：检索增强问答与引用溯源（LangGraph 编排）"
source: "https://articles.zsxq.com/id_7w50qvafp0jd.html"
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

上一篇把企业资料变成了能检索的知识库，但**机器人还不会用它**：它的回复是 mock，客户问什么都得不到答案。中间缺的这一步就是 RAG —— 让模型**先查资料、再照着资料回答，并把出处标出来**。

这一篇做四件事：

1.  **RAG 编排（LangGraph）**：检索 → 判断资料够不够 →（不够就换个问法重查）→ 生成回答 → 校验引用，用 `StateGraph` 把这条带分支的链路画出来；

2.  **引用溯源**：提示词要求模型用 `[1][2]` 标出处，回答里出现的每个编号都能对上下面的来源卡片（文档名 + 第几块 + 原文）；**越界的编号会被剔掉**，不给错出处；

3.  **检索口径自适应**：向量检索看余弦相似度、关键词检索看命中占比——两种分数量纲不同，阈值分开设；

4.  **接进业务**：机器人对话主链路（`/api/ai/v1/agent/chat`）真的会查知识库了，并且带回"资料不够，建议转人工"的信号；坐席工作台里加了「知识助手」，遇到不会答的问题问一下，答案能一键填进回复框。

### 1.2 协议与数据模型变化

HTTP（新增）：

|      |                       |                                                                   |
|------|-----------------------|-------------------------------------------------------------------|
| 方法 | 路径                  | 说明                                                              |
| POST | /api/ai/v1/rag/ask    | 知识问答：返回回答 + 引用来源 + 编排轨迹 + 编排引擎               |
| GET  | /api/ai/v1/rag/health | 编排层探活 **+ 配置体检**（密钥有没有读进来，只报有无不回显密钥） |
| POST | /api/customer/kb/ask  | 上一行在网关后的对外接口（浏览器用，走租户鉴权）                  |

HTTP（改动）：

|      |                       |                                                                                                |
|------|-----------------------|------------------------------------------------------------------------------------------------|
| 方法 | 路径                  | 变化                                                                                           |
| POST | /api/ai/v1/agent/chat | 机器人回复不再是 mock：走"查知识库 → 带出处回答"，并新增 `citations` / `need_human` / `engine` |

数据模型：**这一篇没有新表**。问答不落库，只是把知识库查出来的切片编号后交给模型——检索结果本来就存在 `kb_chunk` 里。

### 1.3 本篇怎么读（增量教程）

这一篇**接着第 20 篇写**，改动前的版本就是第 20 篇验证过的那一份（备份项目）。规则和前两篇一致：

-   **完整文件**：全新文件给完整内容直接新建；

-   **改动文件**：只给「原来 → 改成」两段，把文件里对应的那段换成新的即可。

## 二、环境准备

``` code-block-container
java -version    # 21
mvn -v           # 3.8+
psql --version   # PostgreSQL 17（知识库数据在 customer_db）
cd yunti-ai && ./.venv/bin/python -V
```

依赖（第 20 篇已经装过，这一篇只是把清单理清楚）：

``` code-block-container
cd yunti-ai
./.venv/bin/pip install -r requirements-ai.txt
# 国内慢就换源：-i https://pypi.tuna.tsinghua.edu.cn/simple
```

**密钥是这一篇最容易踩的地方**，先花一分钟配好：

``` code-block-container
cd yunti-ai
cp .env.example .env        # .env 已在 .gitignore 里，不会被提交
# 填这两行（从千问/DeepSeek 控制台拿）：
#   YUNTI_AI_QWEN_API_KEY=sk-...
#   YUNTI_AI_DEEPSEEK_API_KEY=sk-...（可选，两个都有时千问优先）

# 向量化不用单独配：YUNTI_AI_EMBEDDING_API_KEY 留空会自动复用千问的 key
```

> 为什么强调写进 `.env`：IDEA 的 Run Configuration 里配的环境变量**只对那一个配置生效**。如果密钥配在 `yunti-ai (Shell)` 里、却用 `yunti-ai (Python)` 启动，程序读到的是空值——回答会退化成"未配置大模型密钥，先把检索到的原文给你"，而且**没有任何报错**，非常难查。

## 三、yunti-ai：把"查资料"编排成一条流程

### 3.1 大模型调用抽成公共层

AI 质检（第 14 篇）和知识问答都要"按租户配置的模型调一次对话接口"。选厂商、拼请求、记日志、读租户配置这套逻辑跟业务无关，各写一份迟早会出现"质检支持千问、问答忘了加"。

所以先抽一层 `ai/core/llm_chat.py`：`choose_provider`（挑供应商）、`call_chat`（调接口 + 打印入参出参）、`tenant_model`（读租户在「智能机器人 → 模型选择」里的配置）、`resolve_provider`（综合三者）。

### 文件：yunti-ai/ai/core/llm\_chat.py

新增文件：大模型调用的公共层。

``` code-block-container
"""大模型对话的统一入口：挑供应商 → 调 /chat/completions → 租户级模型配置。

为什么单独抽一层：AI 质检（第 14 篇）和知识问答（第 21 篇）都要"按租户配置的模型
调一次对话接口"。这套逻辑（选厂商、拼请求、记日志、读租户配置）跟业务无关，
放在业务里各写一份，迟早会出现"质检支持千问、问答忘了加"这种不一致。
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any

import httpx

from ..config import get_settings
from .db import connect
from .trace import get_trace_id

logger = logging.getLogger(__name__)


def provider_conf(name: str, api_key: str, base_url: str, model: str) -> dict[str, str]:
    return {
        "provider": name,
        "api_key": api_key,
        "base_url": (base_url or "").rstrip("/"),
        "model": model,
    }


def choose_provider(provider: str | None, model: str | None) -> dict[str, str] | None:
    """挑选真实可用的模型供应商。

    优先级：显式指定 > 全局默认配置 > 自动选择已配置密钥的厂商（千问优先）。
    全部没配密钥时返回 None，调用方走各自的兜底（质检用规则、问答给检索原文）。
    """
    settings = get_settings()
    requested = (provider or settings.llm_default_provider or "").lower()
    if "deepseek" in requested:
        return provider_conf("deepseek", settings.deepseek_api_key, settings.deepseek_base_url,
                             model or settings.deepseek_model)
    if "qwen" in requested or "dashscope" in requested:
        return provider_conf("qwen", settings.qwen_api_key, settings.qwen_base_url,
                             model or settings.qwen_model)
    if settings.qwen_api_key:
        logger.info("未指定真实模型供应商（provider=%s），自动使用千问 model=%s",
                    requested or "空", model or settings.qwen_model)
        return provider_conf("qwen", settings.qwen_api_key, settings.qwen_base_url,
                             model or settings.qwen_model)
    if settings.deepseek_api_key:
        logger.info("未指定真实模型供应商（provider=%s），自动使用 DeepSeek model=%s",
                    requested or "空", model or settings.deepseek_model)
        return provider_conf("deepseek", settings.deepseek_api_key, settings.deepseek_base_url,
                             model or settings.deepseek_model)
    return None


async def call_chat(chosen: dict[str, str], payload: dict[str, Any], timeout: int,
                    *, scene: str = "对话") -> dict[str, Any]:
    """调用 OpenAI 兼容的 /chat/completions；入参出参按需打印（排障用）。"""
    trace = get_trace_id() or "-"
    if get_settings().llm_log_payload:
        logger.info("%s模型入参 trace=%s provider=%s model=%s payload=%s",
                    scene, trace, chosen["provider"], chosen["model"],
                    json.dumps(payload, ensure_ascii=False))
    headers = {"Authorization": f"Bearer {chosen['api_key']}", "Content-Type": "application/json"}
    started = time.perf_counter()
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(f"{chosen['base_url']}/chat/completions",
                                     headers=headers, json=payload)
        response.raise_for_status()
        data = response.json()
    if get_settings().llm_log_payload:
        logger.info("%s模型出参 trace=%s provider=%s cost=%dms body=%s",
                    scene, trace, chosen["provider"],
                    int((time.perf_counter() - started) * 1000),
                    json.dumps(data, ensure_ascii=False)[:2000])
    return data


def message_content(data: dict[str, Any]) -> str:
    """从响应里取正文（兼容个别厂商把字段名写错的情况）。"""
    choices = data.get("choices") or []
    if not choices:
        return ""
    message = choices[0].get("message") or {}
    return str(message.get("content") or "").strip()


# 租户模型配置缓存：同一租户短时间内不重复查库
_TENANT_MODEL_TTL = 60.0
_tenant_model_cache: dict[str, tuple[float, dict[str, str] | None]] = {}


def tenant_model(tenant_code: str) -> dict[str, str] | None:
    """读取租户在「智能机器人 → 模型选择」里配置的模型。

    读取失败（未配置 / 库不可用）返回 None，由调用方决定用全局默认还是走兜底。
    """
    if not tenant_code:
        return None
    now = time.time()
    cached = _tenant_model_cache.get(tenant_code)
    if cached and now - cached[0] < _TENANT_MODEL_TTL:
        return cached[1]
    result: dict[str, str] | None = None
    try:
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT provider, model, api_key, base_url
                  FROM ai_bot_config
                 WHERE tenant_code = %s AND is_deleted = FALSE
                 ORDER BY update_time DESC
                 LIMIT 1
                """,
                (tenant_code,),
            )
            row = cur.fetchone()
            if row and (row.get("provider") or row.get("model")):
                result = {
                    "provider": str(row.get("provider") or ""),
                    "model": str(row.get("model") or ""),
                    "api_key": str(row.get("api_key") or ""),
                    "base_url": str(row.get("base_url") or ""),
                }
    except Exception as exc:  # noqa: BLE001
        logger.debug("读取租户模型配置失败 tenant=%s：%s", tenant_code, exc)
    _tenant_model_cache[tenant_code] = (now, result)
    return result


def resolve_provider(tenant_code: str, provider: str | None = None,
                     model: str | None = None) -> tuple[dict[str, str] | None, str]:
    """综合"租户配置 + 显式指定 + 全局默认"挑一个可用供应商。

    @return (供应商配置, 说明) —— 说明用于日志和接口返回，讲清这次用的是谁
    """
    tenant = tenant_model(tenant_code) or {}
    chosen = choose_provider(provider or tenant.get("provider"),
                             model or tenant.get("model"))
    if chosen is None:
        return None, "未配置任何大模型密钥"
    if tenant.get("api_key"):
        # 租户自带密钥：优先用租户的
        chosen = dict(chosen)
        chosen["api_key"] = tenant["api_key"]
        if tenant.get("base_url"):
            chosen["base_url"] = tenant["base_url"].rstrip("/")
    return chosen, f"{chosen['provider']}:{chosen['model']}"
```

质检那边通过别名复用，业务代码一行不改：

### 改动：yunti-ai/ai/services/qa.py

改动点：供应商选择 / 对话调用 / 租户模型配置改为复用公共层。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 76 行附近）**

原来是这样：

``` code-block-container
def _provider_conf(name: str, api_key: str, base_url: str, model: str) -> dict[str, str]:
    return {
        "provider": name,
        "api_key": api_key,
        "base_url": (base_url or "").rstrip("/"),
        "model": model,
    }


async def _call_chat(chosen: dict[str, str], payload: dict[str, Any], timeout: int) -> dict[str, Any]:
    """调用 OpenAI 兼容的 /chat/completions 接口，并打印真实请求体。"""
    if get_settings().llm_log_payload:
        logger.info("AI 质检模型入参 trace=%s provider=%s model=%s payload=%s",
                    get_trace_id() or "-", chosen["provider"], chosen["model"],
                    json.dumps(payload, ensure_ascii=False))
    headers = {"Authorization": f"Bearer {chosen['api_key']}", "Content-Type": "application/json"}
    async with httpx.AsyncClient(timeout=timeout) as client:
        response = await client.post(
            f"{chosen['base_url']}/chat/completions",
            headers=headers,
            json=payload,
        )
        response.raise_for_status()
        return response.json()


def _choose_provider(provider: str | None, model: str | None):
    """挑选真实可用的模型供应商。

    优先级：显式指定 > 全局默认配置 > 自动选择已配置密钥的厂商（千问优先）。
    只有所有厂商都没有密钥时才返回 None，此时质检走本地规则兜底。
    这样即使 provider 仍是骨架默认的 mock，只要配了密钥就会用真实模型。
    """
    settings = get_settings()
    requested = (provider or settings.llm_default_provider or "").lower()
    if "deepseek" in requested:
        return _provider_conf(
            "deepseek", settings.deepseek_api_key, settings.deepseek_base_url,
            model or settings.deepseek_model,
        )
    if "qwen" in requested or "dashscope" in requested:
        return _provider_conf(
            "qwen", settings.qwen_api_key, settings.qwen_base_url,
            model or settings.qwen_model,
        )
    # provider=mock / 未配置：只要配了厂商密钥就用真实模型
    if settings.qwen_api_key:
        logger.info("未指定真实模型供应商（provider=%s），自动使用千问 model=%s",
                    requested or "空", model or settings.qwen_model)
        return _provider_conf(
            "qwen", settings.qwen_api_key, settings.qwen_base_url,
            model or settings.qwen_model,
        )
    if settings.deepseek_api_key:
        logger.info("未指定真实模型供应商（provider=%s），自动使用 DeepSeek model=%s",
                    requested or "空", model or settings.deepseek_model)
        return _provider_conf(
            "deepseek", settings.deepseek_api_key, settings.deepseek_base_url,
            model or settings.deepseek_model,
        )
    return None


# 租户模型配置缓存：批量质检时避免每个任务都查一次库
_TENANT_MODEL_TTL = 60.0
_tenant_model_cache: dict[str, tuple[float, tuple[str | None, str | None]]] = {}


def _tenant_model(tenant_code: str) -> tuple[str | None, str | None]:
    """读取租户在「智能机器人 → 模型选择」里配置的模型。

    读不到（未配置 / 数据库不可用）时返回 ``(None, None)``，不影响质检主流程。
    """
    if not tenant_code:
        return None, None
    now = time.monotonic()
    cached = _tenant_model_cache.get(tenant_code)
    if cached and now - cached[0] < _TENANT_MODEL_TTL:
        return cached[1]

    resolved: tuple[str | None, str | None] = (None, None)
    try:
        with connect() as conn:
            row = conn.execute(
                """SELECT m.provider, m.model_key
                     FROM bot_setting s
                     JOIN bot_model m
                       ON m.tenant_code = s.tenant_code AND m.model_key = s.model_key
                    WHERE s.tenant_code = %s AND s.is_deleted = FALSE
                    LIMIT 1""",
                (tenant_code,),
            ).fetchone()
        if row:
            provider = (row.get("provider") or "").lower()
            model_key = (row.get("model_key") or "").lower()
            # provider 列可能被写成平台自定义值（如 yunti），此时按模型名兜底识别
            if "deepseek" in provider or "deepseek" in model_key:
                resolved = ("deepseek", row.get("model_key"))
            elif "qwen" in provider or "dashscope" in provider or "qwen" in model_key:
                resolved = ("qwen", row.get("model_key"))
    except Exception as exc:  # noqa: BLE001
        logger.warning("读取租户模型配置失败 tenant=%s error=%s: %s",
                       tenant_code, type(exc).__name__, exc)

    _tenant_model_cache[tenant_code] = (now, resolved)
    return resolved

```

改成：

``` code-block-container
# 供应商选择 / 对话调用 / 租户模型配置统一走 core.llm_chat（第 21 篇抽出来的公共层），
# 这里保留原有的私有名字，业务代码不用改。
from ..core.llm_chat import call_chat as _call_chat  # noqa: E402
from ..core.llm_chat import choose_provider as _choose_provider  # noqa: E402
from ..core.llm_chat import tenant_model as _tenant_model  # noqa: E402

```

### 3.2 检索入口收口

第 20 篇留下的 `retrieve()` 只返回结果列表，而编排需要知道**这次是向量检索还是关键词检索**——两种分数不在一个量纲上，判断阈值不一样。所以补一个"带口径"的入口：

### 改动：yunti-ai/ai/rag/retriever.py

改动点：加 retrieve\_with\_meta（带检索口径），retrieve 变成它的薄封装。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 20 行附近）**

原来是这样：

``` code-block-container
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

改成：

``` code-block-container
def retrieve(query: str, tenant_code: str = "", top_k: int = 3) -> list[dict[str, Any]]:
    """检索知识切片；没配租户或没建索引时返回空列表（调用方按"没查到"处理即可）。"""
    return retrieve_with_meta(query, tenant_code, top_k).get("results", [])


def retrieve_with_meta(query: str, tenant_code: str = "", top_k: int = 3,
                       doc_ids: list[int] | None = None) -> dict[str, Any]:
    """检索并带上"这次是怎么检的"（口径、向量来源）。

    知识问答的编排节点用的就是它——编排需要知道检索口径才能判断"召回够不够"
    （向量看相似度、关键词看命中占比，两者阈值不同）。
    检索失败不让调用方崩：返回空结果 + 原因，机器人退化成"没有知识可用"。
    """
    if not query or not tenant_code:
        return {"query": query, "results": [], "mode": "empty", "vector_source": "none"}
    try:
        return pipeline.search(tenant_code=tenant_code, query=query, top_k=top_k, doc_ids=doc_ids)
    except Exception as exc:  # noqa: BLE001
        logger.warning("知识检索失败 tenant=%s query=%s error=%s: %s",
                       tenant_code, query[:40], type(exc).__name__, exc)
        return {"query": query, "results": [], "mode": "error", "vector_source": "none",
                "error": str(exc)}
```

### 3.3 RAG 编排：这一篇的核心

这条链路不是"一步接一步"，中间有分支——资料够不够？不够要不要换个问法再查？回答里的引用编号越界了怎么办？用 LangGraph 的 `StateGraph` 画出来，比一长串 if/else 清楚：

``` code-block-container
retrieve ──▶ grade ──┬─(资料够)──▶ answer ──▶ verify ──▶ END
            ▲        └─(不够)──▶ rewrite ──┘
            └──────────────────────┘   最多改写一次，防死循环
```

五个节点各自的职责、以及两个容易忽略的点：

-   **retrieve**：调上一节的检索入口；

-   **grade**：看最高分过不过阈值。**阈值分两套**（向量 0.5 / 关键词 0.15），因为两种分数不是一个口径；

-   **rewrite**：把长问题拆成关键词重查一次。**故意不用大模型改写**——一次改写就多一次模型调用和一份 token，而"把'退货多久能到账'拆成'退货 到账'"规则完全够用；

-   **answer**：把检索到的切片编号后交给模型；

-   **verify**：检查回答里的 `[n]` 是否都在范围内，**越界的剔掉**。模型偶尔会"顺手编一个 \[5\]"，宁可少一个出处，也不能给错出处。

### 文件：yunti-ai/ai/rag/graph.py

新增文件：RAG 编排（LangGraph StateGraph + 线性兜底）。

``` code-block-container
"""知识问答的编排：检索 → 判断 → （不够就改写重检）→ 生成带引用的回答 → 校验引用。

**为什么用 LangGraph**：这条链路不是"一步接一步"那么简单，中间有分支——
召回结果够不够？不够要不要换个问法再查一次？回答里的引用编号有没有越界？
用 LangGraph 的 StateGraph 把这几步和分支画出来，比写一长串 if/else 清楚得多，
每一步的输入输出也都能记录下来（前端能展示"这次是怎么查的"）。

流程图：

    retrieve ──▶ grade ──┬─(够)──▶ answer ──▶ verify ──▶ END
                ▲        └─(不够)─▶ rewrite ──┘
                └────────────────────┘（最多改写一次，防死循环）

没装 langgraph 时退化成同样步骤的线性实现（`_linear_flow`），
差别只是"没有显式的图"，流程和结果一致——服务不会因为少一个包就不可用。
"""

from __future__ import annotations

import logging
from typing import Any, TypedDict

from . import retriever
from .store import query_terms

logger = logging.getLogger(__name__)

# 召回质量阈值：两种检索口径的分数不在一个量纲上，要分开设
#   vector  → 余弦相似度，0.5 以上算"确实相关"
#   keyword → 命中片段占比，0.15 以上算"沾边"
VECTOR_MIN_SCORE = 0.5
KEYWORD_MIN_SCORE = 0.15

# 最多改写重检几次（防死循环）
MAX_REWRITE_ROUNDS = 1


class RagState(TypedDict, total=False):
    """图里流转的状态。每个节点返回要更新的字段即可。"""

    question: str
    tenant_code: str
    top_k: int
    doc_ids: list[int]
    query: str            # 当前这一轮实际用的检索问法
    hits: list[dict]      # 召回的切片
    mode: str             # 检索口径
    enough: bool          # 召回够不够
    rounds: int           # 已经改写了几次
    answer: str
    citations: list[dict]
    steps: list[dict]     # 每一步的执行记录，前端展示"编排过程"用


def _step(state: RagState, node: str, detail: str, **extra: Any) -> list[dict]:
    """记一条执行轨迹（累加，不覆盖）。"""
    steps = list(state.get("steps") or [])
    steps.append({"node": node, "detail": detail, **extra})
    return steps


# --------------------------------------------------------------------------- 节点


def node_retrieve(state: RagState) -> dict:
    """检索：拿当前问法去知识库捞最相关的切片。"""
    query = state.get("query") or state["question"]
    result = retriever.retrieve_with_meta(
        query=query,
        tenant_code=state["tenant_code"],
        top_k=state.get("top_k", 5),
        doc_ids=state.get("doc_ids") or None,
    )
    hits = result.get("results") or []
    mode = result.get("mode") or "vector"
    return {
        "query": query,
        "hits": hits,
        "mode": mode,
        "steps": _step(state, "retrieve",
                       f"用「{query}」检索，命中 {len(hits)} 条（{mode}）",
                       hitCount=len(hits), mode=mode),
    }


def node_grade(state: RagState) -> dict:
    """判断召回够不够：有没有结果 + 最高分有没有过阈值。"""
    hits = state.get("hits") or []
    keyword_mode = "keyword" in (state.get("mode") or "")
    threshold = KEYWORD_MIN_SCORE if keyword_mode else VECTOR_MIN_SCORE
    top = max((float(hit.get("score") or 0) for hit in hits), default=0.0)
    enough = bool(hits) and top >= threshold
    return {
        "enough": enough,
        "steps": _step(state, "grade",
                       f"最高分 {top:.3f}（阈值 {threshold}）→ "
                       + ("资料够用，直接回答" if enough else "资料不够，换个问法再查"),
                       topScore=round(top, 4), threshold=threshold, enough=enough),
    }


def node_rewrite(state: RagState) -> dict:
    """改写问法：把长问题拆成关键词再查一次。

    这里**故意不用大模型改写**：一次改写就多一次模型调用、多一份 token 成本，
    而"把'退货多久能到账'拆成'退货 到账'"这件事规则完全够用（复用的是关键词检索
    那套切片段逻辑）。需要更聪明的改写时，把这个节点换成调模型即可。
    """
    terms = query_terms(state["question"], max_terms=6)
    rewritten = " ".join(terms) if terms else state["question"]
    return {
        "query": rewritten,
        "rounds": int(state.get("rounds") or 0) + 1,
        "steps": _step(state, "rewrite", f"改写问法为「{rewritten}」", rewritten=rewritten),
    }


async def node_answer(state: RagState) -> dict:
    """生成回答：把召回切片编号后交给模型，要求它按 [1][2] 标注出处。"""
    from ..services.rag import compose_answer

    answer, citations = await compose_answer(
        question=state["question"],
        hits=state.get("hits") or [],
        tenant_code=state.get("tenant_code") or "",
    )
    return {
        "answer": answer,
        "citations": citations,
        "steps": _step(state, "answer",
                       f"基于 {len(citations)} 条资料生成回答",
                       citationCount=len(citations)),
    }


def node_verify(state: RagState) -> dict:
    """校验引用：回答里出现的 [n] 必须在召回结果范围内，越界的剔掉。

    模型偶尔会"顺手编一个出处"，比如只给了 3 条资料却写 [5]。
    这一步把越界的引用去掉并记进轨迹——宁可少一个出处，也不能给错出处。
    """
    from ..services.rag import verify_citations

    kept, dropped = verify_citations(state.get("answer") or "", state.get("citations") or [])
    return {
        "citations": kept,
        "steps": _step(state, "verify",
                       f"校验引用：保留 {len(kept)} 条" + (f"，剔除越界 {len(dropped)} 条" if dropped else ""),
                       kept=len(kept), dropped=dropped),
    }


def _route_after_grade(state: RagState) -> str:
    if state.get("enough"):
        return "answer"
    if int(state.get("rounds") or 0) < MAX_REWRITE_ROUNDS:
        return "rewrite"
    return "answer"  # 改写过了还是不够：也让模型基于现有资料回答（回答里会说明依据不足）


# --------------------------------------------------------------------------- 图


def langgraph_available() -> bool:
    import importlib.util

    try:
        return importlib.util.find_spec("langgraph.graph") is not None
    except (ImportError, ModuleNotFoundError, ValueError):
        return False


def engine_name() -> str:
    return "langgraph" if langgraph_available() else "linear"


def _build_graph():
    from langgraph.graph import END, StateGraph

    builder = StateGraph(RagState)
    builder.add_node("retrieve", node_retrieve)
    builder.add_node("grade", node_grade)
    builder.add_node("rewrite", node_rewrite)
    builder.add_node("answer", node_answer)
    builder.add_node("verify", node_verify)

    builder.set_entry_point("retrieve")
    builder.add_edge("retrieve", "grade")
    builder.add_conditional_edges("grade", _route_after_grade,
                                  {"answer": "answer", "rewrite": "rewrite"})
    builder.add_edge("rewrite", "retrieve")
    builder.add_edge("answer", "verify")
    builder.add_edge("verify", END)
    return builder.compile()


_graph = None


async def ask(question: str, tenant_code: str, top_k: int = 5,
              doc_ids: list[int] | None = None) -> dict:
    """知识问答主入口：编排一条完整的"查资料 → 回答 → 标出处"链路。"""
    global _graph
    state: RagState = {
        "question": question,
        "tenant_code": tenant_code,
        "top_k": top_k,
        "doc_ids": doc_ids or [],
        "query": question,
        "rounds": 0,
        "steps": [],
    }
    if langgraph_available():
        if _graph is None:
            _graph = _build_graph()
            logger.info("知识问答编排已就绪：LangGraph StateGraph")
        final: RagState = await _graph.ainvoke(state)
    else:
        logger.info("知识问答编排：线性实现（未装 langgraph，建议 pip install langgraph）")
        final = await _linear_flow(state)

    return {
        "question": question,
        "answer": final.get("answer") or "",
        "citations": final.get("citations") or [],
        "retrieved": final.get("hits") or [],
        "mode": final.get("mode"),
        "engine": engine_name(),
        "enough": bool(final.get("enough")),
        "steps": final.get("steps") or [],
    }


async def _linear_flow(state: RagState) -> RagState:
    """没装 langgraph 时的等价实现：步骤、分支判断完全一致，只是写成顺序代码。"""
    state.update(node_retrieve(state))
    state.update(node_grade(state))
    while not state.get("enough") and int(state.get("rounds") or 0) < MAX_REWRITE_ROUNDS:
        state.update(node_rewrite(state))
        state.update(node_retrieve(state))
        state.update(node_grade(state))
    state.update(await node_answer(state))
    state.update(node_verify(state))
    return state
```

### 3.4 生成回答与引用校验

两条底线写在提示词里：**只依据资料回答**、**每条结论标出处**。另外两条兜底写在代码里：

-   资料为空 → 直接告诉用户"知识库里没查到，可以先上传文档或转人工"，不调模型；

-   **没配模型密钥 → 不假装回答**，而是把最相关的原文摘出来并说明原因。宁可给原文，也不编一段像模像样的话。

### 文件：yunti-ai/ai/services/rag.py

新增文件：提示词、引用解析与校验。

``` code-block-container
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
        # 模型没标引用（偶尔会）：全都留着，让用户自己看资料，但不假装它有出处
        return list(citations), []
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
```

### 3.5 接口与配置

接口走**原始 JSON**（不是 multipart），并且在健康检查里加了**配置体检**——排障时最常问的就是"我明明配了密钥，为什么还在用兜底"，这里直接告诉你有没有读到（只报有无，不回显密钥）：

### 文件：yunti-ai/ai/api/rag.py

新增文件：问答接口 + 配置体检。

``` code-block-container
"""知识问答接口：让 AI 会查资料，并把出处标出来。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/ai/v1/rag/ask | 问一句，返回回答 + 引用来源 + 编排轨迹 |

调用方是 customer-service（浏览器不直连 AI 服务）。
"""

from __future__ import annotations

import logging
import time

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from ..core.trace import require_trace_id
from ..rag import graph

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai/v1/rag", tags=["rag"])


class AskRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    tenant_code: str = Field(min_length=1, max_length=32)
    question: str = Field(min_length=1, max_length=500)
    top_k: int = Field(default=5, ge=1, le=20)
    doc_ids: list[int] = Field(default_factory=list)


@router.post("/ask")
async def ask(body: AskRequest, trace_id: str = Depends(require_trace_id)) -> dict:
    started = time.perf_counter()
    logger.info("收到知识问答请求 trace=%s tenant=%s question=%s",
                trace_id, body.tenant_code, body.question[:60])
    try:
        result = await graph.ask(
            question=body.question.strip(),
            tenant_code=body.tenant_code,
            top_k=body.top_k,
            doc_ids=body.doc_ids or None,
        )
    except Exception as exc:  # noqa: BLE001
        logger.exception("知识问答失败 trace=%s：%s", trace_id, exc)
        raise HTTPException(status_code=500, detail=f"知识问答失败：{exc}") from exc
    logger.info("知识问答完成 trace=%s 编排=%s 引用=%d cost=%dms",
                trace_id, result.get("engine"), len(result.get("citations") or []),
                int((time.perf_counter() - started) * 1000))
    return result


@router.get("/health")
def rag_health() -> dict:
    """编排层探活 + **配置体检**。

    只回答"有没有配"，绝不回显密钥本身——排障时最常问的就是
    "我明明配了密钥，为什么还在用兜底向量/兜底回答"。
    """
    from ..config import get_settings

    settings = get_settings()
    llm_key = settings.qwen_api_key or settings.deepseek_api_key
    llm_provider = "qwen" if settings.qwen_api_key else ("deepseek" if settings.deepseek_api_key else "none")
    embedding_key = settings.embedding_key()
    return {
        "status": "UP",
        "engine": graph.engine_name(),
        # 回答用的模型：没配密钥时会退化成"只给检索原文"
        "llmProvider": llm_provider,
        "llmKeyConfigured": bool(llm_key),
        "llmModel": settings.qwen_model if settings.qwen_api_key else settings.deepseek_model,
        # 向量化用的模型：没配密钥时会退化成本地兜底向量（只能字面匹配）
        "embeddingKeyConfigured": bool(embedding_key),
        "embeddingModel": settings.embedding_model,
        "embeddingKeyFrom": "embedding_api_key" if settings.embedding_api_key else (
            "qwen_api_key（复用）" if embedding_key else "未配置"),
        "hint": "" if (llm_key and embedding_key) else
                "有密钥没配：缺 LLM 密钥→回答只给原文；缺 embedding 密钥→检索只做字面匹配。"
                "两条都配齐才是真正的语义检索 + AI 生成回答。",
    }
```

### 改动：yunti-ai/ai/main.py

改动点：注册 rag 路由。

这个文件一共 2 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 10 行附近）**

原来是这样：

``` code-block-container
setup_logging()

from .api import bot, chat, health, kb, qa  # noqa: E402

```

改成：

``` code-block-container
setup_logging()

from .api import bot, chat, health, kb, qa, rag  # noqa: E402

```

**新增 2（第 25 行附近）**

原来是这样：

``` code-block-container
    app.include_router(qa.router, prefix=settings.api_prefix)
    app.include_router(kb.router, prefix=settings.api_prefix)
    return app
```

改成：

``` code-block-container
    app.include_router(qa.router, prefix=settings.api_prefix)
    app.include_router(kb.router, prefix=settings.api_prefix)
    app.include_router(rag.router, prefix=settings.api_prefix)
    return app
```

### 改动：yunti-ai/ai/config.py

改动点：加 embedding\_key()：向量化密钥没配时复用千问的 key。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 42 行附近）**

原来是这样：

``` code-block-container
    embedding_timeout: int = 30

    # 知识库：切片参数与数据源（切片存在 customer_db，和文档表同库，租户隔离靠 tenant_code）
    kb_database_url: str = ""
```

改成：

``` code-block-container
    embedding_timeout: int = 30

    def embedding_key(self) -> str:
        """向量化用哪个密钥。

        优先专用的 ``embedding_api_key``；没配就**复用千问的 key**——
        千问的对话和向量化是同一个账号同一套 OpenAI 兼容接口，
        让用户把同一个密钥配两遍（少配一处就悄悄退化成兜底向量）是个坑。
        """
        return self.embedding_api_key or self.qwen_api_key

    # 知识库：切片参数与数据源（切片存在 customer_db，和文档表同库，租户隔离靠 tenant_code）
    kb_database_url: str = ""
```

### 改动：yunti-ai/ai/rag/embedding.py

改动点：向量化改用 embedding\_key()。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 42 行附近）**

原来是这样：

``` code-block-container
        return [], "none"
    settings = get_settings()
    if settings.embedding_api_key and settings.embedding_provider != "local":
        try:
            vectors = _embed_remote(texts, settings, tenant_code=tenant_code, trace_id=trace_id)
```

改成：

``` code-block-container
        return [], "none"
    settings = get_settings()
    # 没单独配向量密钥时复用千问的 key（同一个账号），避免"明明配了密钥却还在用兜底向量"
    if settings.embedding_key() and settings.embedding_provider != "local":
        try:
            vectors = _embed_remote(texts, settings, tenant_code=tenant_code, trace_id=trace_id)
```

### 改动：yunti-ai/requirements-ai.txt

改动点：依赖分三档：必需 / 间接 / 预留，并删掉一个用不到的多余依赖。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 1 行附近）**

原来是这样：

``` code-block-container
# 可选：接入 LangGraph / LlamaIndex / 消息队列 / 模型 SDK
langchain>=0.3
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
openai>=1.40
```

改成：

``` code-block-container
# =====================================================================
# yunti-ai 的可选依赖（AI 能力）
#   pip 不在 PATH 上，用项目自带的：./.venv/bin/pip install -r requirements-ai.txt
#   国内网络慢：加 -i https://pypi.tuna.tsinghua.edu.cn/simple
# =====================================================================

# ---------- 知识库真正必需的（第 20 篇）----------
# 读文件用 SimpleDirectoryReader、切块用 SentenceSplitter、向量化分流走 OpenAIEmbedding
llama-index>=0.11
# Reader 实现：docx / pdf / md 等格式的解析器都在这
llama-index-readers-file>=0.4
# OpenAI 系模型的向量化（千问这类非官方模型名走项目自己的客户端，见 llama_engine.py）
llama-index-embeddings-openai>=0.3
# 中文 PDF 多为 CID 编码字体，内置兜底解析读不出来，只有 pypdf 能读
pypdf>=5

# ---------- 知识库的间接依赖 ----------
# LlamaIndex 的 DocxReader 内部依赖它；缺了传 Word 会报 "docx2txt is required"
docx2txt>=0.8

# ---------- RAG 编排必需（第 21 篇）----------
# 知识问答的"检索 → 判断 → 改写重检 → 生成 → 校验引用"用它的 StateGraph 编排；
# 没装也能跑（退化成线性实现），但就看不到编排过程了
langgraph>=0.2

# ---------- 预留给后续章节（当前代码还没用到）----------
langchain>=0.3
langchain-openai>=0.2
openai>=1.40
redis>=5
kafka-python>=2.2
```

> 这里踩过一个坑：原来向量化要求单独配 `YUNTI_AI_EMBEDDING_API_KEY`，**没配就悄悄退化成兜底向量**（只能字面匹配）。同一个账号的对话和向量化本来就是一套接口，让用户把同一个密钥配两遍本身就是设计问题——现在会自动复用千问的 key。

## 四、把机器人接到知识库

前面几节做的是"能力"，这一节把它接进**机器人对话主链路**：客户问一句 → 查知识库 → 带出处回答。

原来的 `chat_reply` 是个 mock 骨架（建了个 `StateGraph` 却从没调用）。现在它真的走 RAG，并返回三个业务信号：

|           |                                                                        |
|-----------|------------------------------------------------------------------------|
| 字段      | 含义                                                                   |
| citations | 这条回复的依据来自哪几块（前端可以展示"回复出处"）                     |
| needHuman | 资料里查不到 → 建议转人工。**下一篇《AI 客服大脑》的自动转人工要用它** |
| engine    | 用的哪套编排（langgraph / linear）                                     |

### 改动：yunti-ai/ai/agents/graph.py

改动点：机器人回复从 mock 改成"查知识库 + 带出处回答"。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 1 行附近）**

原来是这样：

``` code-block-container
"""对话 Agent 骨架：状态定义 + 节点编排。

未安装 langgraph 时返回 mock 回复；安装 requirements-ai.txt 后，
替换 build_graph 实现即可（意图识别 -> 知识检索 -> 生成 -> 情绪/转人工）。
"""

from __future__ import annotations

from typing import Any

from ..core.llm_gateway import gateway


def _try_build_graph() -> Any | None:
    try:
        from langgraph.graph import StateGraph  # noqa: F401
    except Exception:
        return None
    # TODO: 定义 ChatState + 节点（intent / retrieve / generate / escalate）
    return StateGraph(dict)


def chat_reply(messages: list[dict[str, str]]) -> str:
    graph = _try_build_graph()
    if graph is not None:
        # TODO: graph.invoke(...)
        pass
    prompt = messages[-1].get("content", "") if messages else ""
    return gateway.generate(prompt)
```

改成：

``` code-block-container
"""客服机器人的对话入口：先查知识库，再照着资料回答（RAG）。

分工说明（很重要）：
    第 20 篇做了知识库（文档 → 切片 → 向量），第 21 篇做了"检索 + 引用溯源"，
    这个文件负责把两者**接到机器人主链路**上：客户问一句话 → 查知识库 → 带出处回答。

    意图识别、情绪识别、多轮状态、自动转人工属于下一篇《AI 客服大脑》，
    那时这里会从"单轮 RAG"升级成多节点的 LangGraph 状态机。现在先把
    "会查资料"这件事做扎实：**查不到资料就明说，不编**。
"""

from __future__ import annotations

import logging

from ..rag import graph as rag_graph

logger = logging.getLogger(__name__)


def _question_of(messages: list[dict[str, str]]) -> str:
    """取最后一条客户消息作为问题（历史消息暂时只用于日志）。"""
    for message in reversed(messages or []):
        if (message.get("role") or "user") == "user":
            return (message.get("content") or "").strip()
    return (messages[-1].get("content") if messages else "") or ""


async def chat_reply(messages: list[dict[str, str]], tenant_code: str = "",
                     session_id: str = "") -> dict:
    """生成机器人回复。

    @return {"reply": 回答正文, "citations": 引用来源, "needHuman": 是否建议转人工,
             "engine": 编排引擎, "enough": 资料是否够用}
    """
    question = _question_of(messages)
    if not question:
        return {"reply": "您好，请问有什么可以帮您？", "citations": [],
                "needHuman": False, "engine": rag_graph.engine_name(), "enough": True}

    if not tenant_code:
        # 没有租户就查不到知识库（知识是租户隔离的），明确说明而不是给个泛泛的回答
        logger.warning("机器人对话缺少租户编码 session=%s，无法查知识库", session_id or "-")
        return {"reply": "抱歉，暂时无法为您查询资料，正在为您转接人工客服。",
                "citations": [], "needHuman": True,
                "engine": rag_graph.engine_name(), "enough": False}

    result = await rag_graph.ask(question=question, tenant_code=tenant_code, top_k=5)
    citations = result.get("citations") or []
    enough = bool(result.get("enough")) and bool(citations)
    logger.info("机器人回复 session=%s 编排=%s 引用=%d 资料够用=%s",
                session_id or "-", result.get("engine"), len(citations), enough)
    return {
        "reply": result.get("answer") or "",
        "citations": citations,
        "needHuman": not enough,
        "engine": result.get("engine"),
        "enough": enough,
    }
```

### 改动：yunti-ai/ai/api/chat.py

改动点：对话接口返回 citations / need\_human / engine。

这个文件一共 3 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 1 行附近）**

原来是这样：

``` code-block-container
"""实时对话接口：POST /api/ai/v1/agent/chat。

对接后端 customer-service；后续演进为 SSE 流式（LangGraph 输出逐字返回）。
"""
```

改成：

``` code-block-container
"""实时对话接口：POST /api/ai/v1/agent/chat。

机器人回复不再是 mock：走"查知识库 → 带出处回答"（见 agents/graph.py）。
查不到资料时返回 need_human=true，由业务侧决定是否转人工。
后续演进为 SSE 流式（LangGraph 输出逐字返回）。
"""
```

**修改 2（第 47 行附近）**

原来是这样：

``` code-block-container
    )
    messages = [m.model_dump() for m in payload.messages]
    reply = chat_reply(messages)
    logger.info(
        "智能客服对话请求完成 trace=%s tenant=%s session=%s latencyMs=%d replyChars=%d",
        trace_id, get_tenant_code(), payload.session_id,
        int((time.perf_counter() - started) * 1000), len(reply or ""),
    )
    return {
```

改成：

``` code-block-container
    )
    messages = [m.model_dump() for m in payload.messages]
    result = await chat_reply(messages, tenant_code=get_tenant_code(), session_id=payload.session_id)
    reply = result.get("reply") or ""
    logger.info(
        "智能客服对话请求完成 trace=%s tenant=%s session=%s latencyMs=%d replyChars=%d "
        "引用=%d 需要转人工=%s 编排=%s",
        trace_id, get_tenant_code(), payload.session_id,
        int((time.perf_counter() - started) * 1000), len(reply),
        len(result.get("citations") or []), result.get("needHuman"), result.get("engine"),
    )
    return {
```

**新增 3（第 63 行附近）**

原来是这样：

``` code-block-container
            "tenant_code": get_tenant_code(),
            "reply": reply,
            "stream": payload.stream,
        },
```

改成：

``` code-block-container
            "tenant_code": get_tenant_code(),
            "reply": reply,
            # 回答的依据（前端可以展示"这条回复来自哪份文档"）
            "citations": result.get("citations") or [],
            # 知识库里查不到时建议转人工——下一篇的自动转人工会用到这个信号
            "need_human": result.get("needHuman"),
            "engine": result.get("engine"),
            "stream": payload.stream,
        },
```

## 五、customer-service：对外接口

Java 这边只做三件事：租户校验、参数校验、**把 Python 的下划线字段转成前端用的驼峰**。检索与编排都在 AI 服务里，Java 不重复实现。

### 改动：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/internal/KbAiClient.java

改动点：加 ask()：调 AI 服务的 RAG 接口。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 141 行附近）**

原来是这样：

``` code-block-container
    /**
     * 删除某个文档的全部切片（文档删除 / 下线时调用）。
     */
```

改成：

``` code-block-container
    /**
     * 知识问答：把问题交给 RAG 编排（检索 → 判断 → 生成 → 校验引用）。
     *
     * @return 原始响应（answer / citations / steps 等，键名是下划线风格）
     */
    public Map<String, Object> ask(String tenantCode, String question, int topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", tenantCode);
        body.put("question", question);
        body.put("topK", topK);
        try {
            String response = postJson(baseUrl + "/api/ai/v1/rag/ask", body, tenantCode);
            if (logPayload) {
                log.info("知识问答响应 tenant={} question={} body={}",
                        tenantCode, question, safe(response));
            }
            return objectMapper.readValue(response, new TypeReference<>() {
            });
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "知识问答失败：" + friendly(e));
        }
    }

    /**
     * 删除某个文档的全部切片（文档删除 / 下线时调用）。
     */
```

### 改动：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/service/KbService.java

改动点：加问答服务与字段名转换（answer / citations / steps）。

这个文件一共 3 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 25 行附近）**

原来是这样：

``` code-block-container
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
```

改成：

``` code-block-container
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
```

**新增 2（第 232 行附近）**

原来是这样：

``` code-block-container
    }

    // ---------------------------------------------------------------- 写
```

改成：

``` code-block-container
    }

    /**
     * 知识问答：让 AI 先查知识库、再照着资料回答，并把出处带回来。
     *
     * <p>这一层只做三件事：租户校验、参数校验、把 Python 那边的下划线字段转成前端用的驼峰。
     * 检索与编排都在 yunti-ai（LangGraph），Java 不重复实现。</p>
     */
    public AskResult ask(LoginUser user, String question, Integer topK) {
        String tenant = tenantOf(user);
        if (question == null || question.isBlank()) {
            throw new BizException(40001, "请输入要问的问题");
        }
        int size = topK == null ? 5 : Math.max(1, Math.min(topK, 20));
        Map<String, Object> raw = aiClient.ask(tenant, question.trim(), size);

        List<CitationVO> citations = new ArrayList<>();
        Object rawCitations = raw.get("citations");
        if (rawCitations instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    citations.add(new CitationVO(
                            intOf(map.get("index")),
                            str(map.get("chunk_id")),
                            str(map.get("doc_id")),
                            str(map.get("doc_title")),
                            intOf(map.get("chunk_no")),
                            doubleOf(map.get("score")),
                            str(map.get("content"))));
                }
            }
        }

        List<StepVO> steps = new ArrayList<>();
        Object rawSteps = raw.get("steps");
        if (rawSteps instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    steps.add(new StepVO(str(map.get("node")), str(map.get("detail"))));
                }
            }
        }
        return new AskResult(
                str(raw.get("question")),
                str(raw.get("answer")),
                str(raw.get("engine")),
                str(raw.get("mode")),
                Boolean.TRUE.equals(raw.get("enough")),
                citations,
                steps);
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int intOf(Object value) {
        if (value == null) {
            return 0;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private double doubleOf(Object value) {
        if (value == null) {
            return 0;
        }
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    // ---------------------------------------------------------------- 写
```

**新增 3（第 775 行附近）**

原来是这样：

``` code-block-container
    public record CategoryVO(String id, String parentId, String name, int sortNo, long docCount) {
    }
}
```

改成：

``` code-block-container
    public record CategoryVO(String id, String parentId, String name, int sortNo, long docCount) {
    }

    /** 知识问答结果 */
    public record AskResult(
            String question,
            String answer,
            /** 编排引擎：langgraph / linear */
            String engine,
            /** 检索口径：vector / keyword-local-vector / keyword */
            String mode,
            /** 召回结果是否够用（不够时回答会保守一些） */
            boolean enough,
            List<CitationVO> citations,
            List<StepVO> steps
    ) {
    }

    /** 引用来源：回答里的 [n] 对应这里的第 n 条 */
    public record CitationVO(
            int index,
            String chunkId,
            String docId,
            String docTitle,
            int chunkNo,
            double score,
            String content
    ) {
    }

    /** 编排轨迹：每一步做了什么，前端展示"这次是怎么查的" */
    public record StepVO(String node, String detail) {
    }
}
```

### 改动：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/controller/KbController.java

改动点：加 POST /api/customer/kb/ask。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 171 行附近）**

原来是这样：

``` code-block-container
    }

    /** 新建 / 编辑文档请求体 */
    public record DocumentBody(
```

改成：

``` code-block-container
    }

    /** 知识问答：让 AI 查资料后回答并标出处 */
    @PostMapping("/ask")
    public ApiResponse<KbService.AskResult> ask(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody AskBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(kbService.ask(user, body.question(), body.topK()));
    }

    /** 知识问答请求体 */
    public record AskBody(
            @NotBlank(message = "请输入要问的问题")
            @Size(max = 500)
            String question,

            @Min(value = 1, message = "最多参考 1~20 条资料")
            @Max(value = 20, message = "最多参考 1~20 条资料")
            Integer topK
    ) {
    }

    /** 新建 / 编辑文档请求体 */
    public record DocumentBody(
```

## 六、前端：知识助手

### 6.1 一个组件，两个入口

问答界面在**知识库页**（测试用）和**在线客服工作台**（坐席日常用）都要有。写两份 UI 迟早会不一致，所以抽成公共组件：

### 文件：yunti-frontend/src/components/KnowledgeAssistant.vue

新增文件：知识助手公共组件。

``` code-block-container
<template>
  <el-dialog v-model="visible" title="知识助手（AI 查资料后回答）" width="820px">
    <div class="ka-tip">
      问一句客户可能会问的话，AI 会先去知识库里查资料，再照着资料回答，并在每句话后标出依据来自哪一块。
      回答里没有出处的内容，说明资料里确实没有——那就该补文档，而不是让 AI 编。
    </div>
    <div class="ka-bar">
      <el-input
        v-model="question"
        placeholder="例如：退货多久能到账？"
        maxlength="500"
        @keyup.enter="ask"
      />
      <el-button type="primary" :loading="asking" @click="ask">提问</el-button>
    </div>
    <div v-if="result" class="ka-result">
      <div class="ka-meta">
        <el-tag size="small" effect="light" type="success">编排：{{ result.engine }}</el-tag>
        <el-tag size="small" effect="light" :type="result.enough ? 'success' : 'warning'">
          {{ result.enough ? '资料充分' : '资料偏少，回答较保守' }}
        </el-tag>
        <el-tag size="small" effect="light">
          {{ result.mode === 'vector' ? '向量语义检索' : '关键词匹配' }}
        </el-tag>
        <el-button link type="primary" size="small" @click="showSteps = !showSteps">
          {{ showSteps ? '收起编排过程' : '查看编排过程' }}
        </el-button>
        <span class="ka-spacer" />
        <el-button v-if="insertable" size="small" type="primary" plain @click="emitInsert(result.answer)">
          把回答填入回复
        </el-button>
      </div>
      <div v-if="showSteps" class="ka-steps">
        <div v-for="(step, i) in result.steps" :key="i" class="ka-step">
          <span class="ka-step-no">{{ i + 1 }}</span>
          <span class="ka-step-node">{{ step.node }}</span>
          <span class="ka-step-detail">{{ step.detail }}</span>
        </div>
      </div>
      <div class="ka-answer" v-html="renderAnswer(result.answer)" />

      <div v-if="result.citations.length" class="ka-citations">
        <div class="ka-cit-title">引用来源（{{ result.citations.length }} 条）</div>
        <div v-for="cit in result.citations" :key="cit.index" class="ka-cit">
          <span class="ka-cit-no">[{{ cit.index }}]</span>
          <div class="ka-cit-main">
            <div class="ka-cit-head">
              <span class="ka-cit-doc">{{ cit.docTitle }}</span>
              <span class="ka-cit-sub">第 {{ cit.chunkNo }} 块 · 分值 {{ (cit.score ?? 0).toFixed(3) }}</span>
              <el-button
                v-if="insertable"
                link
                type="primary"
                size="small"
                @click="emitInsert(cit.content)"
              >
                引用这段
              </el-button>
            </div>
            <div class="ka-cit-content">{{ cit.content }}</div>
          </div>
        </div>
      </div>
    </div>
  </el-dialog>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { askKb, type KbAskResult } from '../api/customer/kb'

const props = withDefaults(defineProps<{
  /** 是否显示（配合 v-model:visible 用） */
  visible: boolean
  /** 打开时预填的问题：坐席场景下传客户最后一句，省得再打一遍 */
  defaultQuestion?: string
  /** 是否提供"填入回复/引用这段"（坐席工作台用；纯测试场景不需要） */
  insertable?: boolean
}>(), { defaultQuestion: '', insertable: false })

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void
  /** 把文本交给调用方（坐席场景：填进回复框） */
  (e: 'insert', text: string): void
}>()

const visible = ref(props.visible)
const question = ref('')
const asking = ref(false)
const result = ref<KbAskResult | null>(null)
const showSteps = ref(false)

watch(() => props.visible, (value) => {
  visible.value = value
  if (value) {
    // 每次打开都清空上一次的结果，并按需预填问题
    result.value = null
    showSteps.value = false
    question.value = props.defaultQuestion || ''
  }
})

watch(visible, (value) => emit('update:visible', value))

async function ask() {
  if (!question.value.trim()) {
    ElMessage.warning('请输入要问的问题')
    return
  }
  asking.value = true
  try {
    result.value = await askKb({ question: question.value.trim(), topK: 5 })
  } catch {
    // 请求层已提示
  } finally {
    asking.value = false
  }
}

function emitInsert(text: string) {
  emit('insert', text || '')
  ElMessage.success('已填入回复框，确认后再发送')
}

/** 把回答里的 [1] [2] 渲染成小标签，和下面的引用来源一一对应 */
function renderAnswer(text: string) {
  const escaped = (text || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  return escaped.replace(/\[(\d{1,2})\]/g, '<span class="ka-ref">[$1]</span>')
}
</script>
<style scoped>
.ka-tip { font-size: 13px; color: #64748b; line-height: 1.8; margin-bottom: 10px; }
.ka-bar { display: flex; gap: 8px; }
.ka-result { margin-top: 12px; }
.ka-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ka-spacer { flex: 1; }

.ka-steps { margin: 10px 0; padding: 10px 12px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; }
.ka-step { display: flex; align-items: baseline; gap: 8px; font-size: 12px; line-height: 1.9; }
.ka-step-no { flex: none; width: 18px; height: 18px; border-radius: 50%; background: #e2e8f0; color: #475569; text-align: center; line-height: 18px; font-size: 11px; }
.ka-step-node { flex: none; font-weight: 600; color: #334155; min-width: 62px; }
.ka-step-detail { color: #64748b; }

.ka-answer { margin-top: 10px; padding: 12px 14px; background: #f6faff; border: 1px solid #dbeafe; border-radius: 10px; font-size: 14px; line-height: 1.9; color: #0f172a; white-space: pre-wrap; }
.ka-ref { display: inline-block; margin: 0 1px; padding: 0 4px; border-radius: 4px; background: #dbeafe; color: #1d4ed8; font-size: 12px; font-weight: 600; }

.ka-citations { margin-top: 12px; }
.ka-cit-title { font-size: 13px; font-weight: 600; color: #334155; margin-bottom: 8px; }
.ka-cit { display: flex; gap: 8px; padding: 8px 10px; border: 1px solid #e6ebf2; border-radius: 8px; margin-bottom: 8px; }
.ka-cit-no { flex: none; color: #1d4ed8; font-weight: 700; font-size: 13px; }
.ka-cit-main { min-width: 0; }
.ka-cit-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 3px; }
.ka-cit-doc { font-weight: 600; font-size: 13px; color: #0f172a; }
.ka-cit-sub { font-size: 12px; color: #94a3b8; }
.ka-cit-content { font-size: 12px; color: #475569; line-height: 1.7; }
</style>
```

组件有两个开关：

-   `default-question`：打开时预填问题。**工作台传客户最后那句话**——坐席不用把客户的问题重新打一遍；

-   `insertable`：是否显示「把回答填入回复」「引用这段」。工作台开启，纯测试场景不需要。

### 6.2 接进在线客服工作台

业务场景很具体：**坐席遇到不会答的问题**。所以入口放在聊天输入框旁边（选中会话才出现），点开就是"带客户问题的问答"，答案一键填进回复框。

一个刻意的取舍：**只填空、不自动发送**。AI 的回答可能带引用标记、措辞也未必适合直接发给客户，让坐席看一眼改一改再发更稳——这是 Copilot 而不是 Autopilot。

### 改动：yunti-frontend/src/views/workspace/index.vue

改动点：工作台接入「知识助手」：入口 + 预填客户问题 + 答案填入回复框。

这个文件一共 5 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 222 行附近）**

原来是这样：

``` code-block-container
            <div class="ci-actions">
              <div class="ci-left">
                <el-switch v-model="noteMode" :disabled="isClosed" active-text="内部备注" />
                <span class="ci-tip">
```

改成：

``` code-block-container
            <div class="ci-actions">
              <div class="ci-left">
                <el-button
                  size="small"
                  type="primary"
                  plain
                  :disabled="isClosed"
                  title="不会答的问题，问一下知识库：AI 查完资料给你答案和出处"
                  @click="openKnowledgeAssistant"
                >
                  <el-icon class="btn-icon"><MagicStick /></el-icon>
                  知识助手
                </el-button>
                <el-switch v-model="noteMode" :disabled="isClosed" active-text="内部备注" />
                <span class="ci-tip">
```

**新增 2（第 293 行附近）**

原来是这样：

``` code-block-container
      </template>
    </el-dialog>
  </div>
</template>
```

改成：

``` code-block-container
      </template>
    </el-dialog>
    <!-- 知识助手：坐席接待时遇到不会答的问题，问知识库，答案可以直接填进回复 -->
    <KnowledgeAssistant
      v-model:visible="kbAskVisible"
      :default-question="kbAskDefault"
      insertable
      @insert="onInsertKnowledge"
    />
  </div>
</template>
```

**新增 3（第 322 行附近）**

原来是这样：

``` code-block-container
import { fetchAgentStatuses, updateAgentStatus, type AgentStatusItem } from '../../api/customer/agentStatus'
import { fetchSessionQaAlerts, handleQaAlert, type QaAlertItem } from '../../api/customer/qa'
import { openVisitorTestTab } from '../../utils/visitor'
import {
```

改成：

``` code-block-container
import { fetchAgentStatuses, updateAgentStatus, type AgentStatusItem } from '../../api/customer/agentStatus'
import { fetchSessionQaAlerts, handleQaAlert, type QaAlertItem } from '../../api/customer/qa'
import KnowledgeAssistant from '../../components/KnowledgeAssistant.vue'
import { openVisitorTestTab } from '../../utils/visitor'
import {
```

**新增 4（第 373 行附近）**

原来是这样：

``` code-block-container
/** 当前会话的实时质检告警（边聊边检命中后由长连接推过来） */
const qaAlerts = ref<QaAlertItem[]>([])
/** 还等着处理的告警数：>0 时聊天区顶部挂警示条 */
const pendingQaAlerts = computed(() => qaAlerts.value.filter((item) => item.status === 1).length)
```

改成：

``` code-block-container
/** 当前会话的实时质检告警（边聊边检命中后由长连接推过来） */
const qaAlerts = ref<QaAlertItem[]>([])
/** 知识助手：坐席问知识库（AI 查资料后回答，可把答案填进回复） */
const kbAskVisible = ref(false)
const kbAskDefault = ref('')
/** 还等着处理的告警数：>0 时聊天区顶部挂警示条 */
const pendingQaAlerts = computed(() => qaAlerts.value.filter((item) => item.status === 1).length)
```

**新增 5（第 1268 行附近）**

原来是这样：

``` code-block-container
}

/** 被自动分配的会话在列表里闪两下，提示"有新单进来了" */
function highlightAssigned(sessionNo: string) {
```

改成：

``` code-block-container
}

/**
 * 打开知识助手：默认把客户最后一句带过去。
 *
 * 坐席最常见的场景是"客户问了我不确定的问题"——让他重新打一遍问题很多余，
 * 直接把客户那句话丢进去，点一下就能看答案。
 */
function openKnowledgeAssistant() {
  const lastCustomer = [...messages.value]
    .reverse()
    .find((item) => item.senderType === 1 && (item.content || '').trim())
  kbAskDefault.value = lastCustomer?.content?.trim() || ''
  kbAskVisible.value = true
}

/** 把知识助手的答案填进回复框：只填不发，坐席确认后再发（避免 AI 的话直接发给客户） */
function onInsertKnowledge(text: string) {
  const value = (text || '').trim()
  if (!value) {
    return
  }
  draft.value = (draft.value || '').trim() ? `${draft.value.trim()}\n${value}` : value
}

/** 被自动分配的会话在列表里闪两下，提示"有新单进来了" */
function highlightAssigned(sessionNo: string) {
```

### 6.3 知识库页改用同一个组件

第 20 篇那份内联的问答弹框删掉，换成公共组件——**同一份 UI 维护两遍，迟早不一致**。

### 改动：yunti-frontend/src/views/kb/index.vue

改动点：问答弹框改为公共组件。

这个文件一共 5 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 12 行附近）**

原来是这样：

``` code-block-container
          刷新
        </el-button>
        <el-button @click="openSearch">
          <el-icon class="btn-icon"><Search /></el-icon>
```

改成：

``` code-block-container
          刷新
        </el-button>
        <el-button @click="openAsk">
          <el-icon class="btn-icon"><MagicStick /></el-icon>
          智能问答
        </el-button>
        <el-button @click="openSearch">
          <el-icon class="btn-icon"><Search /></el-icon>
```

**新增 2（第 336 行附近）**

原来是这样：

``` code-block-container
      </div>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  changeKbDocumentStatus,
```

改成：

``` code-block-container
      </div>
    </el-dialog>
    <!-- 知识助手（公共组件，工作台里用的是同一个） -->
    <KnowledgeAssistant v-model:visible="askVisible" />
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import KnowledgeAssistant from '../../components/KnowledgeAssistant.vue'
import {
  changeKbDocumentStatus,
```

**新增 3（第 406 行附近）**

原来是这样：

``` code-block-container
const searchQuery = ref('')
const searchResult = ref<KbSearchResult | null>(null)
/** 是否是关键词兜底模式：是的话分值是"匹配度"，不是余弦相似度，标签也要跟着换 */
const keywordMode = computed(() => (searchResult.value?.mode || '').includes('keyword'))
```

改成：

``` code-block-container
const searchQuery = ref('')
const searchResult = ref<KbSearchResult | null>(null)

/** 知识助手弹框（内容都在公共组件里，这里只管开关） */
const askVisible = ref(false)
/** 是否是关键词兜底模式：是的话分值是"匹配度"，不是余弦相似度，标签也要跟着换 */
const keywordMode = computed(() => (searchResult.value?.mode || '').includes('keyword'))
```

**新增 4（第 639 行附近）**

原来是这样：

``` code-block-container
}

/* ---------------- 展示辅助 ---------------- */
```

改成：

``` code-block-container
}

/* ---------------- 知识助手 ---------------- */

/** 打开知识助手弹框（问答逻辑都在公共组件里） */
function openAsk() {
  askVisible.value = true
}

/* ---------------- 展示辅助 ---------------- */
```

**修改 5（第 779 行附近）**

原来是这样：

``` code-block-container
.search-hint { margin-top: 6px; font-size: 12px; color: #b45309; line-height: 1.7; }
.search-hint code { background: #fff7ed; padding: 0 4px; border-radius: 4px; }
.hit-list { margin-top: 10px; display: flex; flex-direction: column; gap: 10px; max-height: 420px; overflow: auto; }
.hit-item { border: 1px solid #e6ebf2; border-radius: 10px; padding: 10px 12px; }
.hit-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; font-size: 12px; color: #94a3b8; }
.hit-doc { font-weight: 700; color: #0f172a; font-size: 13px; }
.hit-score { margin-left: auto; color: #2563eb; font-weight: 600; }
.hit-content { font-size: 13px; color: #334155; line-height: 1.75; white-space: pre-wrap; word-break: break-word; }
</style>
```

改成：

``` code-block-container
.search-hint { margin-top: 6px; font-size: 12px; color: #b45309; line-height: 1.7; }
.search-hint code { background: #fff7ed; padding: 0 4px; border-radius: 4px; }

</style>
```

### 6.4 接口封装

### 改动：yunti-frontend/src/api/customer/kb.ts

改动点：加 askKb 与问答相关类型。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 167 行附近）**

原来是这样：

``` code-block-container
export function searchKb(data: { query: string; topK?: number }): Promise<KbSearchResult> {
  return request<KbSearchResult>({ url: '/customer/kb/search', method: 'post', data })
}
```

改成：

``` code-block-container
export function searchKb(data: { query: string; topK?: number }): Promise<KbSearchResult> {
  return request<KbSearchResult>({ url: '/customer/kb/search', method: 'post', data })
}

/** 引用来源：回答里的 [n] 对应这里第 n 条 */
export interface KbCitation {
  index: number
  chunkId: string
  docId: string
  docTitle: string
  chunkNo: number
  score: number
  content: string
}

/** 编排轨迹：这次是怎么查的 */
export interface KbAskStep {
  node: string
  detail: string
}

export interface KbAskResult {
  question: string
  answer: string
  /** 编排引擎：langgraph / linear */
  engine: string
  /** 检索口径：vector / keyword-local-vector / keyword */
  mode: string
  /** 召回是否够用 */
  enough: boolean
  citations: KbCitation[]
  steps: KbAskStep[]
}

/** 知识问答：让 AI 先查知识库再回答，并带回出处 */
export function askKb(data: { question: string; topK?: number }): Promise<KbAskResult> {
  return request<KbAskResult>({ url: '/customer/kb/ask', method: 'post', data, timeout: 120000 })
}
```

## 七、运行与验证

### 7.1 后端

``` code-block-container
cd yunti-backend
mvn -pl yunti-customer-service spring-boot:run    # 9093  问答对外接口
mvn -pl yunti-gateway spring-boot:run             # 9090  HTTP 网关

# AI 服务（检索 + 编排 + 生成都在它里面）
cd ../yunti-ai && ./start.sh                       # 9100
```

### 7.2 前端

``` code-block-container
cd yunti-frontend
npm install
npm run dev        # http://localhost:5173
```

### 7.3 手工验证（5 个场景）

**① 先做配置体检**（省得白试）：`curl http://127.0.0.1:9100/api/ai/v1/rag/health` —— `llmKeyConfigured` 与 `embeddingKeyConfigured` 都要是 true。

  

<img src="https://article-images.zsxq.com/FlnJL2K5ItzslEbQma-xzV-omft7" class="tiptap-image" alt="图片.png" />

**② 工作台里问一句**：在线客服 → 选中会话 → 输入框下方左侧「知识助手」→ 问题框已带客户那句话 → 点「提问」。

  

<img src="https://article-images.zsxq.com/FnnEENCeDrqjYYuH64j9F3gxP3YP" class="tiptap-image" alt="图片.png" />

看标签「编排：langgraph」「资料充分」，回答里带 `[1]`，下面是引用来源卡片。

  

<img src="https://article-images.zsxq.com/FlfAoIImWJLFgLEm_CxUm3rpNkZF" class="tiptap-image" alt="图片.png" />

**③ 看编排过程**：点「查看编排过程」，会看到 retrieve → grade → answer → verify 每一步的结论（grade 那行写着最高分与阈值）。

  

<img src="https://article-images.zsxq.com/FjN_DCgiuQfOrYVPkp6Ztg77vnp6" class="tiptap-image" alt="图片.png" />

**④ 把答案用起来**：点「把回答填入回复」或某条引用旁的「引用这段」，内容进回复框，确认后再发送。

  

<img src="https://article-images.zsxq.com/FtzZn6t43SaaW2q-1v4ABEvVBc6n" class="tiptap-image" alt="图片.png" />

**⑤ 反向测试**：问一个知识库里没有的（比如"你们火星仓库什么时候开业"）——回答会明说资料里没有、建议转人工，**不会编**。

  

<img src="https://article-images.zsxq.com/FnJexX4rqmLUOTMHXs19Ewy9UPXO" class="tiptap-image" alt="图片.png" />

### 总结

这一篇让机器人从"复读机"变成了"会查资料的客服"：

1.  **RAG 的价值在"有据可查"**：不是让模型自由发挥，而是先检索、再回答，并且**每条结论都能点回原文**。查不到就明说——这是企业客服场景的底线要求。

2.  **LangGraph 用在"有分支的流程"上**：检索够不够、要不要换个问法重查、引用有没有越界——这些判断和分支写成图，比一长串 if/else 清楚，每个节点的输入输出还能留下来给前端展示。

3.  **阈值要按量纲分**：向量看余弦相似度、关键词看命中占比，分数不是一个尺度，用一个阈值会误判。**任何"打分"逻辑都要先问清楚分是谁算的、什么量纲**。

4.  **提示词之外还要代码兜底**：提示词要求"标出处"，代码还得**校验引用编号是否越界**。只靠提示词约束模型，迟早会出现"引用了不存在的第 5 条"。

5.  **没配密钥就不要假装能答**：直接给检索原文 + 说明原因，比编一段像模像样的话诚实得多。**降级路径要显式、可解释**，而不是悄悄给个看起来正常的错误结果。

6.  **密钥这类配置最容易配错地方**：IDEA 里有多个 Run Configuration，配在 A 上用 B 启动就失效，而且**没有任何报错**。所以这一篇加了个"配置体检"接口——把"有没有读到"变成一条命令能查的事。

7.  **AI 辅助要放在人干活的地方**：能力做得再全，坐席在工作台里看不到就等于没有。这一篇把入口放在聊天输入框旁边、自动带上客户的问题、答案一键填进回复框——**Copilot 而不是 Autopilot**。
