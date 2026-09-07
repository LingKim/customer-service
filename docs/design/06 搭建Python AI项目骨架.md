---
title: "06 搭建Python AI项目骨架"
source: "https://articles.zsxq.com/id_zc7mo09t21sx.html"
author:
  - "[[苏三]]"
published:
created: 2026-09-06
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

## 前言

咱们的云梯智能客服系统里，AI 服务（yunti-ai）的骨架已经搭起来了，健康检查、对话接口、多租户校验这些基础能力都能直接跑。

今天专门写这篇教程，跟大家介绍一下怎么从空目录一步步搭出这个工程：29 个文件全部贴出来，照着敲完就能启动。

不需要装数据库、消息队列和大模型，想用 IDEA 运行的话，文末也给了现成的运行配置。

## 1\. 成品长什么样

最终目录结构如下：

```latex
yunti-ai/
├── main.py                        # 根入口：python main.py（IDEA Python 运行配置指向这里）
├── start.sh                       # 一键启动脚本（IDEA Shell 运行配置执行它）
├── requirements.txt               # 运行必需依赖（装了就能启动）
├── requirements-ai.txt            # 可选：LangGraph / LlamaIndex / Kafka / 模型 SDK
├── README.md
├── .env.example                   # 环境变量样例（复制为 .env 可改端口/前缀）
├── .gitignore
├── scripts/
│   └── bootstrap.sh               # 一键创建 .venv 并安装依赖
├── ai/                            # AI 编排中心主包
│   ├── __init__.py                # 包信息 + 版本号
│   ├── __main__.py                # 入口：python -m ai
│   ├── main.py                    # FastAPI 应用工厂 create_app()
│   ├── config.py                  # 环境变量配置（前缀 YUNTI_AI_）
│   ├── api/                       # HTTP 路由层
│   │   ├── __init__.py
│   │   ├── health.py              # GET /api/ai/health
│   │   └── chat.py                # POST /api/ai/v1/agent/chat（多租户）
│   ├── agents/                    # LangGraph 对话 Agent（骨架）
│   │   ├── __init__.py
│   │   └── graph.py
│   ├── core/                      # 核心基础设施
│   │   ├── __init__.py
│   │   ├── llm_gateway.py         # LLM 网关（默认 mock，接入点已留）
│   │   └── tenant.py              # 租户上下文 X-Tenant-Code
│   ├── rag/                       # LlamaIndex 知识检索（骨架）
│   │   ├── __init__.py
│   │   └── retriever.py
│   ├── services/                  # 质检/训练等业务服务（骨架）
│   │   ├── __init__.py
│   │   └── qa.py
│   └── workers/                   # Kafka 批处理消费者（骨架）
│       ├── __init__.py
│       └── consumer.py
├── tests/
│   └── test_health.py             # 健康检查单元测试（可选运行）
└── .run/                          # IDEA 共享运行配置
    ├── yunti-ai (Shell).run.xml
    └── yunti-ai (Python).run.xml
```

启动成功后：

- 健康检查： `GET http://127.0.0.1:9100/api/ai/health` 返回 `{"status":"UP","service":"ai-center","version":"0.1.0","app":"yunti-ai"}`
- 对话接口： `POST http://127.0.0.1:9100/api/ai/v1/agent/chat` （带 `X-Tenant-Code` 请求头）返回 mock 回复
- API 文档： `http://127.0.0.1:9100/docs`

---

## 2\. 环境准备

1. 安装 Python 3.10+：

```bash
python3 --version
```

1. （可选）配置 PyPI 国内镜像，下载依赖更快：

```bash
pip config set global.index-url https://pypi.tuna.tsinghua.edu.cn/simple
```

1. （可选，只在想用 IDEA 运行时需要）IntelliJ IDEA + Python 插件；不装插件也能用，教程第 7 节有纯命令行之外的“Shell 脚本运行配置”方案，不依赖 Python 插件。

---

## 3\. 创建目录

在任意目录（本文以 `~/workspace` 为例）创建工程根目录并进入：

```bash
mkdir -p ~/workspace/yunti-ai && cd ~/workspace/yunti-ai
```

再创建所有子目录：

```bash
mkdir -p scripts ai/api ai/agents ai/core ai/rag ai/services ai/workers tests .run
```

> 后续所有「创建文件」均相对于该目录。

---

## 4\. 逐个创建文件

### 4.1 文件：.gitignore

```
.venv/
__pycache__/
*.pyc
.env
.pytest_cache/
.idea/
.DS_Store
logs/
```

### 4.2 文件：.env.example

```
YUNTI_AI_APP_NAME=yunti-ai
YUNTI_AI_DEBUG=false
YUNTI_AI_API_PREFIX=/api
YUNTI_AI_LLM_DEFAULT_PROVIDER=mock
YUNTI_AI_LLM_DEFAULT_MODEL=mock-model
YUNTI_AI_REDIS_URL=redis://localhost:6379/0
YUNTI_AI_KAFKA_BOOTSTRAP=localhost:9092
```

> 本工程不连 Redis/Kafka，这两项只是给后续 AI 能力预留的配置，先放着即可。

### 4.3 文件：requirements.txt

```latex
fastapi>=0.115,<1
uvicorn[standard]>=0.30,<1
pydantic>=2.7,<3
pydantic-settings>=2.2,<3
httpx>=0.27,<1
```

> 其中 `httpx` 是 FastAPI TestClient 需要的，装它才能跑单元测试。

### 4.4 文件：requirements-ai.txt（可选依赖）

```latex
# 可选：接入 LangGraph / LlamaIndex / 消息队列 / 模型 SDK
langchain>=0.3
langgraph>=0.2
langchain-openai>=0.2
llama-index>=0.11
redis>=5
kafka-python>=2.2
openai>=1.40
```

> 先不要急着装它。骨架启动只需要 4.3 的依赖；等你需要真实 Agent/RAG 时再 `pip install -r requirements-ai.txt` 。

### 4.5 文件：README.md

```markdown
# yunti-ai · AI 编排中心骨架

对应系统架构文档中的 **ai-center（Python AI 服务集群）**：

| 子服务 | 职责 | 关键依赖 |
| --- | --- | --- |
| Agent 服务 | 对话编排（LangGraph 状态机）、意图/情绪、转人工 | langgraph / langchain |
| RAG 服务 | 知识检索（LlamaIndex）、向量库 | llama-index |
| 质检/训练服务 | 全量质检流水线、模型微调任务 | langgraph（批处理） |
| LLM 网关 | 千问 / DeepSeek 统一接入、降级与计量 | openai / 厂商 SDK |

## 快速运行（健康检查即可用）

\`\`\`bash
cd yunti-ai
bash scripts/bootstrap.sh     # 一键创建 .venv 并安装依赖
./start.sh                   # 一键启动（自动管理端口与依赖）
```

验证： `curl http://localhost:9100/api/ai/health`

## 在 IntelliJ IDEA 中直接运行（参考企业知识库 Python 后端的方案）

项目自带两个共享运行配置（用 IDEA 打开 `yunti-ai` 目录后，右上角运行配置下拉即可看到）：

| 运行配置 | 类型 | 说明 |
| --- | --- | --- |
| `yunti-ai (Shell)` | Shell 脚本 | **推荐** 。无需安装 Python 插件，脚本自动管理.venv/依赖/端口，与 IDEA 解释器设置完全解耦 |
| `yunti-ai (Python)` | Python | 备选。需要安装并启用 Python 插件，且项目解释器指向 `.venv` |

### 方式一：yunti-ai (Shell)——零插件、零配置（推荐）

1. 用 IntelliJ IDEA 打开 `yunti-ai` 目录；
2. 右上角运行配置选择 **yunti-ai (Shell)** ，点击 ▶ 启动；
3. 脚本会自动完成：检查并释放 9100 端口 → 校验/创建 `.venv` → 校验/安装依赖 → 打印启动信息并运行服务；
4. 控制台出现 `Uvicorn running on http://127.0.0.1:9100` 后，访问 `http://127.0.0.1:9100/api/ai/health` 验证；
5. 换端口：运行配置 → Script options 填入 `--port 9200` （支持 `--host` 、 `--no-reload` ，详见 `./start.sh --help` ）。

> 等价命令行：`./start.sh` 或 `./start.sh --port 9200` 。

### 方式二：yunti-ai (Python)——需要 Python 插件

1. 安装 Python 插件： `Settings → Plugins → Marketplace` ，搜索 **Python** （IDEA Community 为 **Python Community Edition** ），安装后重启 IDEA；
2. 运行配置已直接绑定项目 `.venv` （不依赖项目解释器设置），右上角选择 **yunti-ai (Python)** ，点击 ▶ 即可启动；
3. 若 IDEA 仍提示找不到解释器： `Settings → Project → Python Interpreter → Add Interpreter → Existing` ，选择 `yunti-ai/.venv/bin/python` ，再运行一次。

> **常见问题：Unknown run configuration type PythonConfigurationType** 该提示只影响 **yunti-ai (Python)** （它依赖 Python 插件）。解决方式：
> 
> 1. 直接使用 **yunti-ai (Shell)** 启动，完全不需要 Python 插件（推荐）；
> 2. 若想用 Python 配置，安装并启用 Python 插件后重启 IDEA。

> **常见问题：SDK is not defined for Run Configuration** 这是 **yunti-ai (Python)** 的解释器问题（Shell 配置不会遇到）。解决方式：
> 
> 1. 使用 **yunti-ai (Shell)** 启动；
> 2. 如必须用 Python 配置： `File → Project Structure → Project → Python Interpreter → Add Interpreter → Existing` ，选择 `yunti-ai/.venv/bin/python` ，再点 ▶。

> **常见问题：ModuleNotFoundError: No module named 'uvicorn'** 说明运行用的 Python 不是 `.venv` （如系统 Python 3.10，未装任何依赖）。处理：
> 
> 1. 使用 **yunti-ai (Shell)** 启动，脚本会自动使用 `.venv` 并补齐依赖（最稳）；
> 2. 若用 Python 配置：当前共享配置已直接绑定 `.venv` ，重新加载项目后即可生效；若旧运行条目仍在，删除旧条目后再从下拉框选择新的 **yunti-ai (Python)** ；
> 3. 也可以手动把项目解释器指向 `yunti-ai/.venv/bin/python` （ `Settings → Project → Python Interpreter → Add Interpreter → Existing` ）。

其他等价启动命令（任选其一，默认 9100 端口，可用 `YUNTI_AI_PORT` / `YUNTI_AI_HOST` 覆盖）：

```bash
./.venv/bin/python main.py    # 与 IDEA Python 配置入口一致
./.venv/bin/python -m ai      # 包模块方式
```

## 完整 AI 能力

安装可选依赖后可启用真实 Agent/RAG：

```bash
pip install -r requirements-ai.txt
```

当前未装 AI 依赖时，对话接口返回 **mock 回复** （代码里已留好接入点），保证骨架始终可运行。

## Java ↔ Python 通信约定（与后端对齐）

- 实时对话：Java customer-service 调 `POST /api/ai/v1/agent/chat` （后续演进 SSE 流式）；
- 批处理（训练/全量质检）：Kafka topic `ai.task.train` / `ai.task.qa` ；
- 租户隔离：请求头 `X-Tenant-Code` 全链路透传校验。

```
### 4.6 文件：main.py
\`\`\`python
"""yunti-ai 启动入口（参考企业知识库后端 main.py 的 IDEA 运行方案）。

支持两种等价启动方式：
1. python main.py      —— IDEA 的 Python 运行配置默认指向本文件；
2. python -m ai        —— 包模块方式启动。

均可通过环境变量 YUNTI_AI_HOST / YUNTI_AI_PORT 覆盖监听地址与端口。
"""

from __future__ import annotations

import os

try:
    import uvicorn
except ModuleNotFoundError as exc:
    if exc.name == "uvicorn":
        raise SystemExit(
            "未找到 uvicorn：当前运行解释器不是项目 .venv。\n"
            "请选择运行配置「yunti-ai (Shell)」，"
            "或在 Settings → Project → Python Interpreter 中把解释器指向 "
            "yunti-ai/.venv/bin/python 后重试。"
        ) from None
    raise

from ai.config import get_settings

def main() -> None:
    settings = get_settings()
    host = os.getenv("YUNTI_AI_HOST", "127.0.0.1")
    port = int(os.getenv("YUNTI_AI_PORT", "9100"))
    uvicorn.run(
        "ai.main:app",
        host=host,
        port=port,
        reload=settings.debug,
    )

if __name__ == "__main__":
    main()
```

### 4.7 文件：start.sh

```bash
#!/usr/bin/env bash
# ============================================================================
# yunti-ai · AI 编排中心启动脚本（参考企业知识库后端 start.sh 方案）
#
# 用法:
#   ./start.sh                     # 默认 127.0.0.1:9100
#   ./start.sh --host 0.0.0.0      # 监听所有网卡
#   ./start.sh --port 9200         # 指定端口
#   ./start.sh --no-reload         # 关闭热重载
#   ./start.sh -h, --help          # 显示帮助
#
# 该脚本可在 IDEA（Shell 运行配置）、终端中直接使用，
# 不依赖 Python 插件与 IDEA 解释器设置，由脚本自行管理 .venv。
# ============================================================================

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

APP_HOST="${YUNTI_AI_HOST:-127.0.0.1}"
APP_PORT="${YUNTI_AI_PORT:-9100}"
APP_RELOAD="true"

usage() {
  cat <<EOF
用法: $0 [选项]

  --host HOST    监听地址（默认 \$YUNTI_AI_HOST 或 127.0.0.1）
  --port PORT    监听端口（默认 \$YUNTI_AI_PORT 或 9100）
  --no-reload    关闭热重载
  -h, --help     显示帮助
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host)
      APP_HOST="$2"
      shift 2
      ;;
    --port)
      APP_PORT="$2"
      shift 2
      ;;
    --no-reload)
      APP_RELOAD="false"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo -e "${RED}未知参数: $1${NC}（使用 --help 查看用法）"
      exit 1
      ;;
  esac
done

echo ""
echo -e "${CYAN}${BOLD}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}║              yunti-ai · AI 编排中心启动              ║${NC}"
echo -e "${CYAN}${BOLD}╚══════════════════════════════════════════════════════╝${NC}"
echo ""

# ============================================================================
# Step 1: 释放端口（避免重复启动残留进程占用）
# ============================================================================
echo -e "${BOLD}[1/4]${NC} 检查端口 $APP_PORT ..."
pids_on_port=$(lsof -ti tcp:"$APP_PORT" 2>/dev/null || true)
if [ -n "$pids_on_port" ]; then
  echo -e "  ${YELLOW}发现残留进程: $pids_on_port，正在停止...${NC}"
  # shellcheck disable=SC2086
  kill $pids_on_port 2>/dev/null || true
  sleep 1
fi
echo -e "  ${GREEN}✓${NC} 端口检查完成"
echo ""

# ============================================================================
# Step 2: 校验 Python 虚拟环境
# ============================================================================
echo -e "${BOLD}[2/4]${NC} 检查 Python 虚拟环境..."
if [ ! -x .venv/bin/python ]; then
  echo -e "  ${YELLOW}未发现 .venv，正在创建...${NC}"
  python3 -m venv .venv
fi
PY_VERSION="$(.venv/bin/python --version 2>&1)"
echo -e "  ${GREEN}✓${NC} $PY_VERSION"
echo ""

# ============================================================================
# Step 3: 安装/校验依赖
# ============================================================================
echo -e "${BOLD}[3/4]${NC} 检查运行依赖..."
if ./.venv/bin/python -c "import fastapi, uvicorn" >/dev/null 2>&1; then
  echo -e "  ${GREEN}✓${NC} 依赖已就绪"
else
  echo -e "  ${YELLOW}缺少依赖，正在安装 requirements.txt ...${NC}"
  ./.venv/bin/pip install -r requirements.txt
  echo -e "  ${GREEN}✓${NC} 依赖安装完成"
fi
echo ""

# ============================================================================
# Step 4: 启动 FastAPI 应用
# ============================================================================
echo -e "${BOLD}[4/4]${NC} 启动 FastAPI 应用..."
echo ""
echo -e "  ${CYAN}══════════════════════════════════════════════════${NC}"
echo -e "  ${BOLD}AI 服务地址:${NC}  ${BLUE}http://$APP_HOST:$APP_PORT${NC}"
echo -e "  ${BOLD}健康检查:${NC}    ${BLUE}http://$APP_HOST:$APP_PORT/api/ai/health${NC}"
echo -e "  ${BOLD}API 文档:${NC}    ${BLUE}http://$APP_HOST:$APP_PORT/docs${NC}"
echo -e "  ${BOLD}热重载:${NC}      ${YELLOW}$APP_RELOAD${NC}"
echo -e "  ${BOLD}退出:${NC}        Ctrl + C"
echo -e "  ${CYAN}══════════════════════════════════════════════════${NC}"
echo ""

export YUNTI_AI_HOST="$APP_HOST"
export YUNTI_AI_PORT="$APP_PORT"
if [ "$APP_RELOAD" = "true" ]; then
  export YUNTI_AI_DEBUG="true"
else
  export YUNTI_AI_DEBUG="false"
fi

# 前台运行：日志实时输出到 IDEA Console / 终端，Ctrl+C 即可停止
exec ./.venv/bin/python -m ai
```

创建后给它加执行权限：

```bash
chmod +x start.sh
```

### 4.8 文件：scripts/bootstrap.sh

```bash
#!/usr/bin/env bash
# 一键初始化：创建 .venv 并安装运行依赖
set -e
cd "$(dirname "$0")/.."

PY="${PYTHON:-python3}"
if [ ! -d .venv ]; then
  echo "[1/2] 创建虚拟环境 .venv ..."
  "$PY" -m venv .venv
else
  echo "[1/2] 虚拟环境已存在，跳过创建"
fi

echo "[2/2] 安装依赖 ..."
./.venv/bin/pip install -r requirements.txt

echo ""
echo "完成。请在 IDEA 中把项目解释器指向：$(pwd)/.venv/bin/python"
echo "然后选择运行配置 yunti-ai (Shell) 或 yunti-ai (Python) 即可。"
```

### 4.9 文件：ai/init.py

```python
"""云梯智能客服平台 · AI 编排中心（ai-center）。"""

__version__ = "0.1.0"
```

### 4.10 文件：ai/config.py

```python
"""应用配置：环境变量前缀 YUNTI_AI_，支持 .env。"""

from __future__ import annotations

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="YUNTI_AI_", env_file=".env", extra="ignore")

    app_name: str = "yunti-ai"
    service_name: str = "ai-center"
    debug: bool = False
    api_prefix: str = "/api"

    # LLM 网关（默认 mock，装好 AI 依赖后可切 qwen/deepseek）
    llm_default_provider: str = "mock"
    llm_default_model: str = "mock-model"

    redis_url: str = "redis://localhost:6379/0"
    kafka_bootstrap: str = "localhost:9092"

@lru_cache
def get_settings() -> Settings:
    return Settings()
```

> 说明：所有配置项都带 `YUNTI_AI_` 前缀，例如 `YUNTI_AI_PORT` （端口由入口文件单独读取）、 `YUNTI_AI_DEBUG` 。工程根目录放一个 `.env` 也能被自动读取。

### 4.11 文件：ai/main.py

```python
"""FastAPI 入口。"""

from __future__ import annotations

from fastapi import FastAPI

from .api import chat, health
from .config import get_settings

def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(
        title=settings.app_name,
        version="0.1.0",
        debug=settings.debug,
    )
    app.include_router(health.router, prefix=settings.api_prefix)
    app.include_router(chat.router, prefix=settings.api_prefix)
    return app

app = create_app()
```

### 4.12 文件：ai/main.py

```python
"""入口：支持 python -m ai 或在 IntelliJ IDEA 中直接运行。"""

from __future__ import annotations

import os

import uvicorn

from .config import get_settings

def main() -> None:
    settings = get_settings()
    host = os.getenv("YUNTI_AI_HOST", "127.0.0.1")
    port = int(os.getenv("YUNTI_AI_PORT", "9100"))
    uvicorn.run(
        "ai.main:app",
        host=host,
        port=port,
        reload=settings.debug,
    )

if __name__ == "__main__":
    main()
```

> 三种启动方式选一种即可： `python main.py` 、 `python -m ai` 、`./start.sh` 。前两者默认不开热重载（ `YUNTI_AI_DEBUG=false` ）， `start.sh` 默认开热重载。

### 4.13 文件：ai/api/init.py

```python
"""HTTP API 路由。"""
```

### 4.14 文件：ai/api/health.py

```python
"""健康检查。"""

from __future__ import annotations

from fastapi import APIRouter

from .. import __version__
from ..config import get_settings

router = APIRouter()

@router.get("/ai/health")
def health() -> dict[str, object]:
    settings = get_settings()
    return {
        "status": "UP",
        "service": settings.service_name,
        "version": __version__,
        "app": settings.app_name,
    }
```

### 4.15 文件：ai/api/chat.py

```python
"""实时对话接口：POST /api/ai/v1/agent/chat。

对接后端 customer-service；后续演进为 SSE 流式（LangGraph 输出逐字返回）。
"""

from __future__ import annotations

from typing import List

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from ..agents.graph import chat_reply
from ..core.tenant import get_tenant_code, require_tenant_code

router = APIRouter()

class Message(BaseModel):
    role: str
    content: str

class ChatRequest(BaseModel):
    session_id: str = Field(default="")
    messages: List[Message] = Field(default_factory=list)
    stream: bool = False

@router.post("/ai/v1/agent/chat")
async def chat(
    payload: ChatRequest,
    _tenant: str = Depends(require_tenant_code),
) -> dict[str, object]:
    messages = [m.model_dump() for m in payload.messages]
    reply = chat_reply(messages)
    return {
        "code": 0,
        "message": "成功",
        "data": {
            "session_id": payload.session_id,
            "tenant_code": get_tenant_code(),
            "reply": reply,
            "stream": payload.stream,
        },
    }
```

### 4.16 文件：ai/agents/init.py

```python
"""LangGraph 对话 Agent。"""
```

### 4.17 文件：ai/agents/graph.py

```python
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

> 关键设计： `langgraph` 用 try/except 惰性导入。没装可选依赖时走 mock；装了之后直接替换 TODO 部分即可，不会影响骨架启动。

### 4.18 文件：ai/core/init.py

```python
"""核心基础设施：LLM 网关、租户上下文。"""
```

### 4.19 文件：ai/core/llm\_gateway.py

```python
"""LLM 网关：统一鉴权/路由/降级/计量。当前为 mock 实现，接入点已预留。"""

from __future__ import annotations

from typing import Protocol

from ..config import get_settings

class ChatModel(Protocol):
    def generate(self, prompt: str) -> str: ...

class MockModel:
    def generate(self, prompt: str) -> str:
        return f"[mock] 已收到你的消息：{prompt[:60]}（接入大模型后由 LLM 网关真实生成）"

class LLMGateway:
    """统一入口：provider 路由、超时降级、token 计量（后续实现）。"""

    def __init__(self) -> None:
        settings = get_settings()
        self.provider = settings.llm_default_provider
        self.model = settings.llm_default_model
        self._model: ChatModel = MockModel()
        if self.provider != "mock":
            # TODO: 按 provider 初始化 OpenAI/千问/DeepSeek 客户端
            pass

    def generate(self, prompt: str) -> str:
        # TODO: 流式（SSE）、成本计量、失败降级
        return self._model.generate(prompt)

gateway = LLMGateway()
```

### 4.20 文件：ai/core/tenant.py

```python
"""租户上下文：请求头 X-Tenant-Code 透传与校验。"""

from __future__ import annotations

from contextvars import ContextVar

from fastapi import Header, HTTPException

TENANT_CODE_HEADER = "X-Tenant-Code"

_tenant_code: ContextVar[str] = ContextVar("tenant_code", default="")

def set_tenant_code(code: str) -> None:
    _tenant_code.set(code)

def get_tenant_code() -> str:
    return _tenant_code.get()

async def require_tenant_code(
    x_tenant_code: str | None = Header(default=None, alias=TENANT_CODE_HEADER),
) -> str:
    """依赖注入：解析并校验租户上下文（对接多租户隔离方案）。"""
    if not x_tenant_code:
        raise HTTPException(status_code=400, detail="缺少 X-Tenant-Code 请求头")
    set_tenant_code(x_tenant_code)
    return x_tenant_code
```

> 多租户说明：对话接口强制要求 `X-Tenant-Code` 请求头；当前骨架用 ContextVar 在同一请求链路内透传，后续接入真实后端时可替换为 JWT/Redis 校验，但接口契约不用变。

### 4.21 文件：ai/rag/init.py

```python
"""LlamaIndex RAG 检索。"""
```

### 4.22 文件：ai/rag/retriever.py

```python
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

### 4.23 文件：ai/services/init.py

```python
"""质检 / 训练等业务服务。"""
```

### 4.24 文件：ai/services/qa.py

```python
"""全量 AI 质检骨架（批处理任务消费入口）。"""

from __future__ import annotations

def run_qa_task(tenant_code: str, session_id: str) -> dict[str, object]:
    """TODO: 规则引擎 -> LLM 初检 -> 结果回调 customer-service。"""
    return {
        "tenant_code": tenant_code,
        "session_id": session_id,
        "status": "pending",
        "note": "接入 LangGraph 质检流水线后返回真实评分",
    }
```

### 4.25 文件：ai/workers/init.py

```python
"""Kafka 批处理消费者（训练 / 全量质检）。"""
```

### 4.26 文件：ai/workers/consumer.py

```python
"""消费 ai.task.train / ai.task.qa 任务（骨架占位）。"""

from __future__ import annotations

def consume_task(topic: str) -> None:
    """TODO: kafka-python 消费 + 幂等 + 结果回调。"""
    raise NotImplementedError("接入 Kafka 后实现")
```

### 4.27 文件：tests/test\_health.py

```python
from fastapi.testclient import TestClient

from ai.main import app

client = TestClient(app)

def test_health() -> None:
    resp = client.get("/api/ai/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"
```

> 运行单元测试需要额外安装 pytest：`./.venv/bin/pip install pytest` ，然后执行 `./.venv/bin/python -m pytest tests -q` 。不装 pytest 也不影响服务启动，可以跳过本节。

### 4.28 文件：.run/yunti-ai (Shell).run.xml

> IDEA 的共享运行配置：把 yunti-ai 目录用 IDEA 打开后，右上角就能看到 `yunti-ai (Shell)` 。它执行的是 `start.sh` ，不依赖 Python 插件。

```xml
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="yunti-ai (Shell)" type="ShConfigurationType" singleton="false">
    <option name="SCRIPT_TEXT" value="" />
    <option name="INDEPENDENT_SCRIPT_PATH" value="true" />
    <option name="SCRIPT_PATH" value="$PROJECT_DIR$/start.sh" />
    <option name="SCRIPT_OPTIONS" value="" />
    <option name="INDEPENDENT_SCRIPT_WORKING_DIRECTORY" value="true" />
    <option name="SCRIPT_WORKING_DIRECTORY" value="$PROJECT_DIR$" />
    <option name="INDEPENDENT_INTERPRETER_PATH" value="true" />
    <option name="INTERPRETER_PATH" value="/bin/zsh" />
    <option name="INTERPRETER_OPTIONS" value="" />
    <option name="EXECUTE_IN_TERMINAL" value="true" />
    <option name="EXECUTE_SCRIPT_FILE" value="true" />
    <envs>
      <env name="PYTHONUNBUFFERED" value="1" />
    </envs>
    <method v="2" />
  </configuration>
</component>
```

### 4.29 文件：.run/yunti-ai (Python).run.xml

> 该配置需要 Python 插件。其中 `<option name="SDK_HOME" ...>` 直接绑定项目 `.venv` 的解释器，避免 IDEA 用错系统 Python。 **请把** `value` **中的路径换成你自己电脑上的项目绝对路径** ；如果你在 IDEA 里手动选了解释器，也可以把这行和 `IS_MODULE_SDK` 那行删掉，让配置跟随项目解释器。

```xml
<component name="ProjectRunConfigurationManager">
  <configuration default="false" name="yunti-ai (Python)" type="PythonConfigurationType" factoryName="Python" singleton="false">
    <module name="yunti-ai" />
    <option name="ENV_FILES" value="" />
    <option name="INTERPRETER_OPTIONS" value="" />
    <option name="PARENT_ENVS" value="true" />
    <envs>
      <env name="PYTHONUNBUFFERED" value="1" />
    </envs>
    <option name="SDK_HOME" value="/你的项目绝对路径/yunti-ai/.venv/bin/python" />
    <option name="WORKING_DIRECTORY" value="$PROJECT_DIR$" />
    <option name="IS_MODULE_SDK" value="false" />
    <option name="ADD_CONTENT_ROOTS" value="true" />
    <option name="ADD_SOURCE_ROOTS" value="true" />
    <option name="SCRIPT_NAME" value="$PROJECT_DIR$/main.py" />
    <option name="PARAMETERS" value="" />
    <option name="SHOW_COMMAND_LINE" value="false" />
    <option name="EMULATE_TERMINAL" value="true" />
    <option name="MODULE_MODE" value="false" />
    <option name="REDIRECT_INPUT" value="false" />
    <option name="INPUT_FILE" value="" />
    <method v="2" />
  </configuration>
</component>
```

---

## 5\. 初始化虚拟环境与依赖

回到工程根目录，一键初始化（创建 `.venv` 并安装 requirements.txt）：

```bash
cd ~/workspace/yunti-ai
bash scripts/bootstrap.sh
```

等价手动方式：

```bash
python3 -m venv .venv
./.venv/bin/pip install -r requirements.txt
```

看到最后输出“完成。请在 IDEA 中把项目解释器指向：.../.venv/bin/python”即成功。

---

## 6\. 运行与验证

### 6.1 方式一：一键脚本（推荐）

```bash
./start.sh
```

脚本会自动释放 9100 端口、校验 `.venv` 、补齐依赖并启动。看到下面的输出即为成功：

```latex
[4/4] 启动 FastAPI 应用...
Uvicorn running on http://127.0.0.1:9100 (Press CTRL+C to quit)
```

### 6.2 方式二：直接运行入口

```bash
./.venv/bin/python main.py
```

等价命令：`./.venv/bin/python -m ai` 。

### 6.3 验证接口

新开一个终端执行：

```bash
curl http://127.0.0.1:9100/api/ai/health
```

预期返回：

```json
{"status":"UP","service":"ai-center","version":"0.1.0","app":"yunti-ai"}
```

测试带租户的对话接口（注意必须带 `X-Tenant-Code` 请求头）：

```bash
curl -X POST http://127.0.0.1:9100/api/ai/v1/agent/chat \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Code: T-10001" \
  -d '{"session_id":"s1","messages":[{"role":"user","content":"你好"}]}'
```

预期返回（mock 回复）：

```json
{"code":0,"message":"成功","data":{"session_id":"s1","tenant_code":"T-10001","reply":"[mock] 已收到你的消息：你好（接入大模型后由 LLM 网关真实生成）","stream":false}}
```

不带租户头访问会得到 400：

```bash
curl -X POST http://127.0.0.1:9100/api/ai/v1/agent/chat \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"hi"}]}'
```

浏览器打开 `http://127.0.0.1:9100/docs` 可看到 Swagger 文档。

---

## 7\. 在 IntelliJ IDEA 中运行

前提：`.run/` 下两个共享运行配置已创建（4.28 / 4.29）。

1. 用 IDEA 打开 `yunti-ai` 目录（注意是打开这个目录本身，不要打开外层文件夹）；
2. 右上角运行配置选择 **yunti-ai (Shell)** ，点击 ▶；
3. 若想断点调试且已装 Python 插件，可改选 **yunti-ai (Python)** （首次使用前确认 `.venv` 已创建、4.29 里的 SDK\_HOME 路径已改成你自己的路径）。

> 纯命令行用户完全不需要 IDEA，第 6 节三种启动方式即可。

---

## 8\. 如何接入真实 AI 能力（可选）

骨架保证“没装大模型也能跑”。

需要真实能力时：

1. 安装可选依赖：`./.venv/bin/pip install -r requirements-ai.txt` ；
2. 在 `.env` （复制 `.env.example` 生成）中配置 `YUNTI_AI_LLM_DEFAULT_PROVIDER=qwen|deepseek|openai` 和对应模型名；
3. 在 `ai/core/llm_gateway.py` 的 TODO 位置按 provider 初始化客户端；
4. 在 `ai/agents/graph.py` 用 LangGraph 定义真实的状态机节点（意图 → 检索 → 生成 → 转人工）；
5. 在 `ai/rag/retriever.py` 实现按 `tenant_code` 分区的向量检索。

接口契约（ `/api/ai/v1/agent/chat` + `X-Tenant-Code` ）保持不变，Java 后端无需改动。

---

## 9\. 常见问题

1. \*\*报 \*\* `No module named 'uvicorn'` ：当前解释器不是 `.venv` 。命令行用 `./.venv/bin/python main.py` ；IDEA 里用 `yunti-ai (Shell)` ，或在 Project Structure 里把解释器指到 `.venv/bin/python` 。
2. \*\*报 \*\* `Unknown run configuration type PythonConfigurationType` ：Python 插件没装。直接用 `yunti-ai (Shell)` 运行，不需要插件。
3. **端口被占用** ：`./start.sh --port 9200` ，或先 `lsof -ti tcp:9100 | xargs kill` 。
4. **改了端口但没生效** ：入口读环境变量 `YUNTI_AI_PORT` ； `start.sh` 的 `--port` 参数优先级最高。
5. **访问对话接口返回 400 缺少 X-Tenant-Code** ：这是设计行为，多租户接口必须带租户头。
6. **想跑 tests 目录的单元测试** ：先 `./.venv/bin/pip install pytest` ，再 `./.venv/bin/python -m pytest tests -q` 。
7. **想开启热重载** ： `YUNTI_AI_DEBUG=true ./.venv/bin/python main.py` ，或直接用默认开热重载的 `./start.sh` 。

---

## 10\. 文件清单核对

创建完成后核对文件是否齐全：

```bash
find . -path ./.venv -prune -o -path ./__pycache__ -prune -o -type f -print | sort
```

预期清单：

```latex
.env.example
.gitignore
.run/yunti-ai (Python).run.xml
.run/yunti-ai (Shell).run.xml
README.md
ai/__init__.py
ai/__main__.py
ai/agents/__init__.py
ai/agents/graph.py
ai/api/__init__.py
ai/api/chat.py
ai/api/health.py
ai/config.py
ai/core/__init__.py
ai/core/llm_gateway.py
ai/core/tenant.py
ai/main.py
ai/rag/__init__.py
ai/rag/retriever.py
ai/services/__init__.py
ai/services/qa.py
ai/workers/__init__.py
ai/workers/consumer.py
main.py
requirements-ai.txt
requirements.txt
scripts/bootstrap.sh
start.sh
tests/test_health.py
```

（`.venv/` 、 `__pycache__/` 、`.idea/` 属本机生成物，不在清单内。）

![](data:image/png;base64,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)

扫码加入星球

查看更多优质内容

https://wx.zsxq.com/mweb/views/joingroup/join\_group.html?group\_id=28851182188851