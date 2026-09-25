---
title: "14 接入Qwen和DeepSeek大模型"
source: "https://articles.zsxq.com/id_38w0m38ha8qb.html"
author:
  - "[[苏三]]"
published:
created: 2026-09-13
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

## 一、项目概述

### 1.1 功能范围

上一篇《增加质检中心功能》做完时，质检初检还是 customer-service 里的本地规则（用任务号哈希凑分数），"AI 质检"名不副实。

这一篇把初检真正接到大模型上：

1.  yunti-ai 用 OpenAI 兼容接口同时接入千问（DashScope）和 DeepSeek，一套代码支持两家，切换只改配置；

2.  新增质检接口 `POST /api/ai/v1/qa/evaluate`：把质检规则（含每条规则的检查内容）和客服会话记录交给大模型，返回结构化 JSON；

3.  模型选择采用四级策略：请求指定 → 租户「模型选择」配置 → 全局默认 → 按已配置密钥自动挑选；

4.  提示词约束 + JSON 输出模式 + 字段别名容错，模型偶尔把 riskLevel 写成 rel 也能正确落库；

5.  没有可用密钥或模型调用失败时，自动降级为本地规则兜底，质检流程不中断；

6.  AI 调用的入参、出参、耗时、token 用量全部打日志，Java 与 Python 用同一个 trace 串联；

7.  customer-service 送检内容改造：带上真实会话的对话记录，历史任务按会话名回填对话。

### 1.2 技术选型

|                        |                                                  |
|------------------------|--------------------------------------------------|
| 技术                   | 说明                                             |
| 千问 qwen-plus         | DashScope 的 OpenAI 兼容接口，国内直连           |
| DeepSeek deepseek-chat | OpenAI 兼容接口，成本低                          |
| FastAPI + httpx        | yunti-ai 调用大模型                              |
| JDK HttpClient         | customer-service 调用 yunti-ai                   |
| PostgreSQL 17          | ai\_db 存租户模型配置，customer\_db 存会话与质检 |

### 1.3 接口清单

|      |                                 |                                              |
|------|---------------------------------|----------------------------------------------|
| 方法 | 路径                            | 说明                                         |
| POST | /api/ai/v1/qa/evaluate          | AI 质检初检（yunti-ai 提供）                 |
| POST | /api/ai/v1/agent/chat           | 智能客服对话（yunti-ai 提供）                |
| POST | /api/customer/qa/scan           | 全量质检（customer-service，内部调用第一条） |
| POST | /api/customer/qa/tasks/batch-ai | 批量 AI 质检（customer-service）             |

### 1.4 与上一篇的关系

|                                                   |                                  |
|---------------------------------------------------|----------------------------------|
| 上一篇（13 增加质检中心）已有                     | 本次处理                         |
| qa\_rule / qa\_task / qa\_review 三张表与质检接口 | 保留，初检来源改为真实大模型     |
| 质检中心前端页面                                  | 不动                             |
| QaService 里的哈希凑分逻辑                        | 删除，替换为调用 yunti-ai        |
| yunti-ai 的 qa 骨架（run\_qa\_task 占位）         | 重写为真实质检服务               |
| 对话接口 /api/ai/v1/agent/chat                    | 补日志与 trace，暂仍走 mock 网关 |

## 二、环境准备

``` code-block-container
python3 --version      # 3.11+（本项目用 3.13）
java -version          # 21
mvn -v                 # 3.8+
```

然后准备一个模型密钥，任选其一即可：

|                              |                                       |               |
|------------------------------|---------------------------------------|---------------|
| 厂商                         | 申请地址                              | 用到的模型    |
| 千问（阿里云百炼 DashScope） | <https://bailian.console.aliyun.com/> | qwen-plus     |
| DeepSeek 开放平台            | <https://platform.deepseek.com/>      | deepseek-chat |

yunti-ai 的 `requirements.txt` 里已经有 httpx，不用额外装依赖。  
一个密钥都不配也能把本文跑完：服务会自动降级成本地规则兜底，只是分数不是模型给的。

## 三、大模型配置

### 3.1 密钥放哪里

三种方式，选一种即可（优先级从低到高）：

1.  环境变量：`export YUNTI_AI_DEEPSEEK_API_KEY=sk-xxx`；

2.  项目根目录的 `.env` 文件：pydantic-settings 会自动读取，该文件已被 git 忽略；

3.  IDEA 运行配置的 Environment variables（推荐，见 3.3）。

### 3.2 配置样例文件

### 文件：yunti-ai/.env.example

``` code-block-container
YUNTI_AI_APP_NAME=yunti-ai
YUNTI_AI_DEBUG=false
YUNTI_AI_API_PREFIX=/api
YUNTI_AI_LOG_LEVEL=INFO
# 是否打印 AI 调用的完整入参/出参（排障用；生产可置 false 降噪）
YUNTI_AI_LLM_LOG_PAYLOAD=true
# 不用改成 qwen / deepseek：只要下面任一密钥有值，质检就会自动使用真实模型；
# 租户在「智能机器人 → 模型选择」里选的模型优先级更高。
YUNTI_AI_LLM_DEFAULT_PROVIDER=
YUNTI_AI_LLM_DEFAULT_MODEL=
YUNTI_AI_LLM_TIMEOUT=30
YUNTI_AI_QWEN_API_KEY=
YUNTI_AI_QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
YUNTI_AI_QWEN_MODEL=qwen-plus
YUNTI_AI_DEEPSEEK_API_KEY=
YUNTI_AI_DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
YUNTI_AI_DEEPSEEK_MODEL=deepseek-chat
YUNTI_AI_REDIS_URL=redis://localhost:6379/0
YUNTI_AI_KAFKA_BOOTSTRAP=localhost:9092
```

### 3.3 IDEA 运行配置里配置密钥

yunti-ai 自带两个共享运行配置，用 IDEA 打开 yunti-ai 目录后右上角即可看到。推荐用 Shell 配置：不依赖 Python 插件，脚本自己管理 .venv、依赖和端口。

### 文件：yunti-ai/.run/yunti-ai (Shell).run.xml

``` code-block-container
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
      <env name="YUNTI_AI_QWEN_API_KEY" value="你的DashScope密钥" />
      <env name="YUNTI_AI_DEEPSEEK_API_KEY" value="你的DeepSeek密钥" />
      <env name="PYTHONUNBUFFERED" value="1" />
    </envs>
    <method v="2" />
  </configuration>
</component>
```

注意：文件里那两个 value 换成你自己的密钥。改完直接点右上角的 ▶ 启动即可，不用再配任何环境变量。

### 3.4 配置项

### 文件：yunti-ai/ai/config.py

``` code-block-container
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
    # 日志级别：DEBUG / INFO / WARNING / ERROR
    log_level: str = "INFO"
    # 是否打印 AI 调用的完整入参/出参（真实模型请求与响应原文）
    llm_log_payload: bool = True

    # LLM 网关（默认 mock；配置 QWEN/DeepSeek 后可切 qwen/deepseek）
    llm_default_provider: str = "mock"
    llm_default_model: str = ""
    llm_timeout: int = 30

    # 千问（DashScope OpenAI 兼容接口）
    qwen_api_key: str = ""
    qwen_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_model: str = "qwen-plus"

    # DeepSeek OpenAI 兼容接口
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com/v1"
    deepseek_model: str = "deepseek-chat"

    redis_url: str = "redis://localhost:6379/0"
    kafka_bootstrap: str = "localhost:9092"

    # ai_db 数据库连接（示例：postgresql://mac@127.0.0.1:5432/ai_db）
    database_url: str = "postgresql://mac@127.0.0.1:5432/ai_db"


@lru_cache
def get_settings() -> Settings:
    return Settings()
```

三个关键点：

1.  环境变量前缀是 `YUNTI_AI_`，所以配置里写 `qwen_api_key`，环境变量就是 `YUNTI_AI_QWEN_API_KEY`；

2.  `llm_default_provider` 保持 `mock` 不用改——只要配了任一密钥，质检会自动选真实模型；

3.  `llm_log_payload` 控制是否打印模型请求与响应的原文，排障时很有用，生产想降噪可以置 false。

## 四、Python：大模型接入的基础能力

### 4.1 统一日志

FastAPI 默认不配置根日志，`logger.info` 是打不出来的（只有 warning 会漏出来）。所以先把日志配置好，AI 调用链路的日志才有统一的格式。

### 文件：yunti-ai/ai/core/logging\_config.py

``` code-block-container
"""统一日志配置：AI 调用链路（接口层 / 服务层 / LLM 网关）共用一套输出格式。

格式说明：``时间(毫秒) | 级别 | 模块 | 内容``，内容里统一带 ``trace=``，
便于和 Java 侧调用日志串联排查。
"""

from __future__ import annotations

import logging
import sys

from ..config import get_settings

LOG_FORMAT = "%(asctime)s.%(msecs)03d | %(levelname)-5s | %(name)-22s | %(message)s"
DATE_FORMAT = "%Y-%m-%d %H:%M:%S"

_configured = False


def setup_logging() -> None:
    """初始化全局日志，重复调用只生效一次。"""
    global _configured
    if _configured:
        return

    settings = get_settings()
    level = getattr(logging, (settings.log_level or "INFO").upper(), logging.INFO)

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter(LOG_FORMAT, DATE_FORMAT))
    handler.setLevel(level)

    root = logging.getLogger()
    root.handlers.clear()
    root.setLevel(level)
    root.addHandler(handler)

    # 第三方 HTTP 客户端日志降噪：调用细节由业务日志记录，避免刷屏
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)

    _configured = True
```

### 4.2 调用链 trace

Java 调用 yunti-ai 时会在请求头带一个 `X-Request-Id`，两边用同一个 trace，出问题能直接从 Java 日志串到 Python 日志。

### 文件：yunti-ai/ai/core/trace.py

``` code-block-container
"""调用链追踪：透传 X-Request-Id，让 Java 调用方与 AI 服务日志能对上同一次调用。"""

from __future__ import annotations

import uuid
from contextvars import ContextVar

from fastapi import Header

TRACE_ID_HEADER = "X-Request-Id"

_trace_id: ContextVar[str] = ContextVar("trace_id", default="")


def set_trace_id(trace_id: str) -> None:
    _trace_id.set(trace_id)


def get_trace_id() -> str:
    return _trace_id.get()


def new_trace_id() -> str:
    """调用方没带请求号时自行生成，保证日志里始终有 trace 可查。"""
    return uuid.uuid4().hex[:16]


async def require_trace_id(
    x_request_id: str | None = Header(default=None, alias=TRACE_ID_HEADER),
) -> str:
    """依赖注入：解析调用方请求号（缺省自动生成）。"""
    trace_id = (x_request_id or "").strip() or new_trace_id()
    set_trace_id(trace_id)
    return trace_id
```

### 4.3 应用入口

日志要在导入 API 模块之前初始化，否则模块级组件（比如 LLM 网关）的启动日志会丢。

### 文件：yunti-ai/ai/main.py

``` code-block-container
"""FastAPI 入口。"""

from __future__ import annotations

from fastapi import FastAPI

from .config import get_settings
from .core.logging_config import setup_logging

# 先初始化日志再导入 API：保证模块级组件（如 LLM 网关）的启动日志走统一格式
setup_logging()

from .api import bot, chat, health, qa  # noqa: E402


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(
        title=settings.app_name,
        version="0.1.0",
        debug=settings.debug,
    )
    app.include_router(health.router, prefix=settings.api_prefix)
    app.include_router(chat.router, prefix=settings.api_prefix)
    app.include_router(bot.router, prefix=settings.api_prefix)
    app.include_router(qa.router, prefix=settings.api_prefix)
    return app


app = create_app()
```

### 4.4 LLM 网关（实时对话）

网关是对话链路的统一入口，目前还是 mock 实现，本次先补上日志，后续接 LangGraph 时替换内部实现即可。

### 文件：yunti-ai/ai/core/llm\_gateway.py

``` code-block-container
"""LLM 网关：统一鉴权/路由/降级/计量。当前为 mock 实现，接入点已预留。"""

from __future__ import annotations

import logging
import time
from typing import Protocol

from ..config import get_settings
from .trace import get_trace_id

logger = logging.getLogger(__name__)


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
        logger.info(
            "LLM 网关初始化 provider=%s model=%s mode=%s",
            self.provider, self.model or "-", "mock" if self.provider == "mock" else "remote",
        )
        if self.provider != "mock":
            # TODO: 按 provider 初始化 OpenAI/千问/DeepSeek 客户端
            pass

    def generate(self, prompt: str) -> str:
        # TODO: 流式（SSE）、成本计量、失败降级
        settings = get_settings()
        started = time.perf_counter()
        logger.info(
            "LLM 生成开始 trace=%s provider=%s model=%s promptChars=%d",
            get_trace_id() or "-", self.provider, self.model or "-", len(prompt or ""),
        )
        if settings.llm_log_payload:
            logger.info("LLM 生成入参 trace=%s prompt=%s", get_trace_id() or "-", prompt)
        try:
            reply = self._model.generate(prompt)
        except Exception as exc:  # noqa: BLE001
            logger.error(
                "LLM 生成失败 trace=%s provider=%s model=%s latencyMs=%d error=%s: %s",
                get_trace_id() or "-", self.provider, self.model or "-",
                int((time.perf_counter() - started) * 1000), type(exc).__name__, exc,
            )
            raise
        if settings.llm_log_payload:
            logger.info("LLM 生成出参 trace=%s reply=%s", get_trace_id() or "-", reply)
        logger.info(
            "LLM 生成完成 trace=%s provider=%s model=%s latencyMs=%d replyChars=%d",
            get_trace_id() or "-", self.provider, self.model or "-",
            int((time.perf_counter() - started) * 1000), len(reply or ""),
        )
        return reply


gateway = LLMGateway()
```

### 4.5 启动脚本（端口占用自愈）

调试大模型时经常反复重启，`start.sh` 顺手补了两件事：启动前先释放端口、支持 `./start.sh stop` 单独停服务。另外提醒一句：IDEA 的 Shell 运行配置是用 `/bin/zsh` 执行脚本的，而 zsh 默认不做单词拆分，`kill $pids` 这种写法会把多行进程号当成一个参数，杀进程静默失败——所以下面的循环写法不能省。

### 文件：yunti-ai/start.sh

``` code-block-container
#!/usr/bin/env bash
# ============================================================================
# yunti-ai · AI 编排中心启动脚本（参考企业知识库后端 start.sh 方案）
#
# 用法:
#   ./start.sh                     # 默认 127.0.0.1:9100
#   ./start.sh --host 0.0.0.0      # 监听所有网卡
#   ./start.sh --port 9200         # 指定端口
#   ./start.sh --no-reload         # 关闭热重载
#   ./start.sh restart             # 重启服务（先停旧进程再启动）
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
# zsh 在函数内部会把 $0 变成函数名，这里先存一份，usage 才能显示正确的脚本名
SCRIPT_NAME="$0"

APP_HOST="${YUNTI_AI_HOST:-127.0.0.1}"
APP_PORT="${YUNTI_AI_PORT:-9100}"
APP_RELOAD="true"
MODE="start"

usage() {
  cat <<EOF
用法: $SCRIPT_NAME [选项]

  --host HOST    监听地址（默认 \$YUNTI_AI_HOST 或 127.0.0.1）
  --port PORT    监听端口（默认 \$YUNTI_AI_PORT 或 9100）
  --no-reload    关闭热重载
  restart        重启服务（自动停止旧进程后启动）
  stop           只停止本项目占用的端口，不启动服务
  -h, --help     显示帮助
EOF
}

# 列出占用指定端口的进程号（每行一个）
port_pids() {
  lsof -ti tcp:"$1" 2>/dev/null || true
}

# 逐个结束进程。
# 注意：IDEA 的 Shell 运行配置是用 /bin/zsh 执行本脚本的，而 zsh 默认不做
# 单词拆分，写成 kill $pids 时多行进程号会被当成“一个”参数，导致 kill 直接失败
# （报 illegal process id 并被 2>/dev/null 吞掉），旧进程永远杀不掉。
kill_each() {
  local sig="$1"
  local pids="$2"
  local pid
  printf '%s\n' "$pids" | while IFS= read -r pid; do
    [ -n "$pid" ] || continue
    kill "-$sig" "$pid" 2>/dev/null || true
  done
}

# 兜底清理：本项目 .venv 启动、但已经不再占用目标端口的残留进程
# （uvicorn 热重载的 reloader 父进程被漏杀时就是这种情况）
kill_orphans() {
  local pid
  for pattern in "$PROJECT_DIR/.venv/bin/python" ".venv/bin/python -m ai"; do
    pgrep -f "$pattern" 2>/dev/null | while IFS= read -r pid; do
      [ -n "$pid" ] || continue
      [ "$pid" = "$$" ] && continue
      [ "$pid" = "$PPID" ] && continue
      kill -9 "$pid" 2>/dev/null || true
    done
  done
}

stop_port() {
  local port="$1"
  local pids
  local wait_round

  pids=$(port_pids "$port")
  if [ -n "$pids" ]; then
    echo -e "  ${YELLOW}发现端口 $port 被占用：$(printf '%s' "$pids" | tr '\n' ' ')${NC}"
    echo -e "  ${YELLOW}正在停止旧进程（SIGTERM）...${NC}"
    kill_each TERM "$pids"
    for wait_round in 1 2 3 4 5; do
      sleep 1
      pids=$(port_pids "$port")
      [ -z "$pids" ] && break
    done
    if [ -n "$pids" ]; then
      echo -e "  ${YELLOW}旧进程未退出，强制停止（SIGKILL）...${NC}"
      kill_each KILL "$pids"
      sleep 1
    fi
  fi

  # 兜底：清理本项目遗留的 AI 服务进程（含热重载的父/子进程）
  if command -v pgrep >/dev/null 2>&1; then
    kill_orphans
    sleep 1
  fi

  pids=$(port_pids "$port")
  if [ -n "$pids" ]; then
    echo -e "  ${RED}端口 $port 仍被占用，无法启动：${NC}"
    lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | sed 's/^/    /'
    echo -e "  ${YELLOW}请手动执行 kill -9 <PID> 后重试。${NC}"
    exit 1
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    restart)
      MODE="restart"
      shift
      ;;
    stop)
      MODE="stop"
      shift
      ;;
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

if [ "$MODE" = "stop" ]; then
  echo ""
  echo -e "${CYAN}${BOLD}停止 yunti-ai（端口 $APP_PORT）${NC}"
  stop_port "$APP_PORT"
  echo -e "  ${GREEN}✓${NC} 已停止"
  echo ""
  exit 0
fi

echo ""
echo -e "${CYAN}${BOLD}╔══════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}${BOLD}║              yunti-ai · AI 编排中心启动              ║${NC}"
echo -e "${CYAN}${BOLD}╚══════════════════════════════════════════════════════╝${NC}"
echo ""

# ============================================================================
# Step 1: 释放端口（避免重复启动残留进程占用）
# ============================================================================
echo -e "${BOLD}[1/4]${NC} 检查端口 $APP_PORT ..."
stop_port "$APP_PORT"
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

## 五、Python：AI 质检接入真实大模型

### 5.1 质检接口

接口接收租户编码、会话名、客服名、会话记录、质检规则（名称 + 检查内容），可选传 provider / model 覆盖默认模型。

### 文件：yunti-ai/ai/api/qa.py

``` code-block-container
"""AI 质检评估接口。"""

from __future__ import annotations

import logging
import time
from typing import Optional

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel

from ..core.tenant import require_tenant_code
from ..core.trace import require_trace_id
from ..services.qa import evaluate

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai/v1/qa", tags=["qa"])


class RuleSpec(BaseModel):
    """质检规则：名称 + 具体检查内容。"""

    name: str = Field(min_length=1, max_length=64)
    content: str = Field(default="", max_length=500)


class EvaluateRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    tenant_code: str = Field(min_length=1, max_length=32)
    session_name: str = Field(min_length=1, max_length=128)
    agent_name: str = Field(min_length=1, max_length=64)
    transcript: str = ""
    rule_names: list[str] = Field(default_factory=list, max_length=20)
    # 规则的完整信息（名称 + 检查内容），有值时优先用它构造提示词
    rules: list[RuleSpec] = Field(default_factory=list, max_length=20)
    provider: Optional[str] = None
    model: Optional[str] = None


@router.post("/evaluate")
async def evaluate_session(
    body: EvaluateRequest,
    tenant_code: str = Depends(require_tenant_code),
    trace_id: str = Depends(require_trace_id),
) -> dict:
    started = time.perf_counter()
    logger.info(
        "收到 AI 质检请求 trace=%s tenant=%s session=%s agent=%s rules=%d",
        trace_id, tenant_code, body.session_name, body.agent_name, len(body.rule_names),
    )
    result = await evaluate(
        tenant_code=body.tenant_code or tenant_code,
        session_name=body.session_name,
        agent_name=body.agent_name,
        transcript=body.transcript,
        rule_names=body.rule_names,
        provider=body.provider,
        model=body.model,
        rules=[item.model_dump() for item in body.rules],
    )
    logger.info(
        "AI 质检请求完成 trace=%s tenant=%s session=%s latencyMs=%d score=%s risk=%s source=%s",
        trace_id, tenant_code, body.session_name, int((time.perf_counter() - started) * 1000),
        result.get("aiScore"), result.get("riskLevel"), result.get("source"),
    )
    return {"code": 0, "message": "成功", "data": result}
```

### 5.2 质检服务（核心）

这是整篇最核心的文件，做了四件事：

1.  选模型：请求指定 → 租户「模型选择」→ 全局默认 → 按密钥自动挑；

2.  拼提示词：系统提示词约束输出格式，用户消息里带规则和会话记录；

3.  调模型：走 OpenAI 兼容的 `/chat/completions`，带上 `response_format=json_object`；

4.  解析与兜底：字段别名容错、取值校验、失败降级到本地规则。

### 文件：yunti-ai/ai/services/qa.py

``` code-block-container
"""AI 质检服务：调用千问 / DeepSeek 对客服会话进行结构化初检。

接入方式参考企业知识库 Python 后端：Qwen/DeepSeek 都走 OpenAI 兼容
``/chat/completions`` 接口；未配置密钥时降级为规则引擎 mock 结果，
保证本地骨架始终可运行。
"""

from __future__ import annotations

import json
import logging
import time
from typing import Any

import httpx

from ..config import get_settings
from ..core.db import connect
from ..core.trace import get_trace_id

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """你是企业智能客服的资深质检专家。
请根据下方质检规则与客服会话记录，完成结构化初检。

评分规则：
1. aiScore：0~100 的整数，90 以上优秀、80~89 良好、60~79 及格、60 以下需改进；
2. riskLevel：1=低风险，2=中风险，3=高风险；
3. rules：仅返回命中的规则名称，没有命中则返回空数组；
4. comment：用一句中文总结问题与改进建议。

必须严格按下面的 JSON 结构输出，字段名一个字母都不能改，不要输出 Markdown 代码块或其他文字：
{"aiScore": 85, "riskLevel": 1, "rules": ["命中的规则名称"], "comment": "中文结论"}"""

# 模型偶尔会写错字段名（例如把 riskLevel 写成 rel），这里做别名兼容
_SCORE_KEYS = ("aiScore", "ai_score", "score", "totalScore")
_RISK_KEYS = ("riskLevel", "risk_level", "risk", "rel", "level")
_RULE_KEYS = ("rules", "ruleNames", "rule_names", "hitRules")
_COMMENT_KEYS = ("comment", "comments", "summary", "conclusion")


def _fallback_result(
    session_name: str,
    agent_name: str,
    rule_names: list[str],
    reason: str,
    *,
    tenant_code: str = "",
    provider: str = "",
    model: str = "",
) -> dict[str, Any]:
    """未配置密钥或调用失败时的确定性兜底，保证质检流程可继续。"""
    logger.warning(
        "AI 质检降级 trace=%s tenant=%s session=%s provider=%s model=%s reason=%s",
        get_trace_id() or "-", tenant_code, session_name, provider or "-", model or "-", reason,
    )
    hit_rules = [name for name in rule_names if _keyword_hit(session_name + agent_name, name)]
    score = 88 if not hit_rules else max(60, 88 - len(hit_rules) * 8)
    risk = 1 if not hit_rules else (2 if len(hit_rules) == 1 else 3)
    return {
        "aiScore": score,
        "riskLevel": risk,
        "rules": hit_rules,
        "comment": "AI 服务未返回结果，当前使用本地规则兜底初检。",
        "source": "fallback-rule",
    }


def _keyword_hit(text: str, rule_name: str) -> bool:
    keywords = {
        "敏感词与禁语": ["烦死了", "随便你", "自己看", "不知道"],
        "服务承诺规范": ["尽快", "马上", "放心", "一定"],
        "必答项完整": ["订单号", "发票", "地址", "电话"],
        "情绪安抚": ["不满意", "投诉", "差评", "生气", "失望"],
    }
    return any(word in text for word in keywords.get(rule_name, []))


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


async def evaluate(
    tenant_code: str,
    session_name: str,
    agent_name: str,
    transcript: str,
    rule_names: list[str],
    provider: str | None = None,
    model: str | None = None,
    rules: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """对会话执行 LLM 初检，返回 aiScore/riskLevel/rules/comment。"""
    settings = get_settings()
    requested_provider = provider
    requested_model = model
    # 数据库里配了模型就优先用（来自「智能机器人 → 模型选择」）
    if not requested_provider:
        tenant_provider, tenant_model = _tenant_model(tenant_code)
        if tenant_provider:
            requested_provider = tenant_provider
            requested_model = requested_model or tenant_model

    chosen = _choose_provider(requested_provider, requested_model)
    # 选中的厂商没配密钥时，退回到其它已配置密钥的厂商，避免白白走规则兜底
    if chosen is not None and not chosen["api_key"]:
        logger.warning("provider=%s 未配置 API Key，尝试改用其它已配置密钥的厂商", chosen["provider"])
        chosen = _choose_provider(None, None)
    if chosen is None or not chosen["api_key"]:
        return _fallback_result(
            session_name, agent_name, rule_names,
            "未配置可用的模型 API Key（YUNTI_AI_QWEN_API_KEY / YUNTI_AI_DEEPSEEK_API_KEY）",
            tenant_code=tenant_code,
            provider=requested_provider or settings.llm_default_provider or "-",
            model=requested_model or settings.llm_default_model or "-",
        )

    rule_count = len(rules) if rules else len(rule_names)
    started = time.perf_counter()
    logger.info(
        "AI 质检调用开始 trace=%s tenant=%s session=%s agent=%s provider=%s model=%s rules=%d transcriptChars=%d",
        get_trace_id() or "-", tenant_code, session_name, agent_name,
        chosen["provider"], chosen["model"], rule_count, len(transcript or ""),
    )

    if rules:
        # 带上规则的具体检查内容，AI 才能按企业自己的规则判分
        rule_text = "\n".join(
            f"- {item.get('name')}：{item.get('content') or '（未填写检查内容）'}"
            for item in rules
        )
    else:
        rule_text = "\n".join(f"- {name}" for name in rule_names)
    rule_text = rule_text or "-（无规则约束，请按客服服务质量通用标准）"
    user_content = f"""企业租户：{tenant_code}
质检会话：{session_name}
接待客服：{agent_name}

【质检规则】
{rule_text}

【客服会话记录】
{transcript[:3000]}"""

    payload = {
        "model": chosen["model"],
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": user_content},
        ],
        "temperature": 0.2,
        "max_tokens": 600,
    }
    # 要求模型按 JSON 输出，避免出现 rel / risk 这类字段名偏差
    json_payload = {**payload, "response_format": {"type": "json_object"}}
    try:
        try:
            data = await _call_chat(chosen, json_payload, settings.llm_timeout)
        except httpx.HTTPStatusError as exc:
            # 个别模型 / 兼容网关不支持 response_format，退回普通模式再试一次
            logger.warning("模型不支持 response_format=json_object（status=%s），改用普通模式重试",
                           exc.response.status_code)
            data = await _call_chat(chosen, payload, settings.llm_timeout)
        content = data["choices"][0]["message"]["content"]
        if settings.llm_log_payload:
            logger.info("AI 质检模型出参 trace=%s provider=%s model=%s content=%s",
                        get_trace_id() or "-", chosen["provider"], chosen["model"], content)
        result = _parse_llm_json(content)
        result["source"] = f"llm-{chosen['provider']}"
        usage = data.get("usage") or {}
        logger.info(
            "AI 质检调用成功 trace=%s tenant=%s session=%s provider=%s model=%s latencyMs=%d "
            "promptTokens=%s completionTokens=%s totalTokens=%s score=%s risk=%s hitRules=%d source=%s",
            get_trace_id() or "-", tenant_code, session_name, chosen["provider"], chosen["model"],
            int((time.perf_counter() - started) * 1000),
            usage.get("prompt_tokens", "-"), usage.get("completion_tokens", "-"),
            usage.get("total_tokens", "-"), result["aiScore"], result["riskLevel"],
            len(result["rules"]), result["source"],
        )
        return result
    except Exception as exc:  # noqa: BLE001
        logger.error(
            "AI 质检调用异常 trace=%s tenant=%s session=%s provider=%s model=%s latencyMs=%d error=%s: %s",
            get_trace_id() or "-", tenant_code, session_name, chosen["provider"], chosen["model"],
            int((time.perf_counter() - started) * 1000), type(exc).__name__, exc,
        )
        return _fallback_result(
            session_name, agent_name, rule_names, str(exc),
            tenant_code=tenant_code, provider=chosen["provider"], model=chosen["model"],
        )


def _parse_llm_json(content: str) -> dict[str, Any]:
    text = content.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.startswith("json"):
            text = text[4:]
    start, end = text.find("{"), text.rfind("}")
    if start >= 0 and end > start:
        text = text[start : end + 1]
    parsed = json.loads(text)

    score_raw = _pick(parsed, _SCORE_KEYS)
    if score_raw is None:
        # 拿不到评分说明模型没按约定输出，交给上层走失败降级，不能凭空编一个分数
        raise ValueError(f"模型返回缺少评分子段：{text[:200]}")
    score = int(float(score_raw))

    risk_raw = _pick(parsed, _RISK_KEYS)
    if risk_raw is None:
        # 缺 riskLevel 时按分数换算，避免一律默认成低风险
        risk = 1 if score >= 88 else (2 if score >= 75 else 3)
        logger.warning("模型返回缺少 riskLevel，按评分 %s 推断为 %s", score, risk)
    else:
        risk = int(float(risk_raw))

    rules = _pick(parsed, _RULE_KEYS) or []
    if not isinstance(rules, list):
        rules = [rules]
    return {
        "aiScore": max(0, min(100, score)),
        "riskLevel": max(1, min(3, risk)),
        "rules": [str(r) for r in rules][:10],
        "comment": str(_pick(parsed, _COMMENT_KEYS, "AI 质检完成，请人工抽样复核确认。"))[:500],
    }


def _pick(data: dict[str, Any], keys: tuple[str, ...], default: Any = None) -> Any:
    """按别名列表取值，忽略空值。"""
    for key in keys:
        value = data.get(key)
        if value is not None and value != "":
            return value
    return default


async def run_qa_task(tenant_code: str, session_id: str) -> dict[str, object]:
    """保留批处理入口签名，后续 Kafka worker 可直接复用。"""
    return await evaluate(tenant_code, f"会话-{session_id}", "客服", "", [], provider="mock")
```

### 5.3 对话接口日志

### 文件：yunti-ai/ai/api/chat.py

``` code-block-container
"""实时对话接口：POST /api/ai/v1/agent/chat。

对接后端 customer-service；后续演进为 SSE 流式（LangGraph 输出逐字返回）。
"""

from __future__ import annotations

import logging
import time
from typing import List

from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field

from ..agents.graph import chat_reply
from ..core.tenant import get_tenant_code, require_tenant_code
from ..core.trace import require_trace_id

logger = logging.getLogger(__name__)

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
    trace_id: str = Depends(require_trace_id),
) -> dict[str, object]:
    started = time.perf_counter()
    question = payload.messages[-1].content if payload.messages else ""
    logger.info(
        "收到智能客服对话请求 trace=%s tenant=%s session=%s messages=%d questionChars=%d",
        trace_id, get_tenant_code(), payload.session_id, len(payload.messages), len(question),
    )
    messages = [m.model_dump() for m in payload.messages]
    reply = chat_reply(messages)
    logger.info(
        "智能客服对话请求完成 trace=%s tenant=%s session=%s latencyMs=%d replyChars=%d",
        trace_id, get_tenant_code(), payload.session_id,
        int((time.perf_counter() - started) * 1000), len(reply or ""),
    )
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

## 六、Java：customer-service 调用 AI 服务

### 6.1 送检客户端

用 JDK 自带的 HttpClient 调用 yunti-ai，重点是把质检规则、会话记录、trace 一起带过去，并把请求和响应原文打出来。

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/internal/QaAiClient.java

``` code-block-container
package cn.net.susan.customer.internal;

import cn.net.susan.common.api.ApiResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * yunti-ai 质检服务客户端（使用 JDK HttpClient，确保 JSON body 真实发送）。
 */
@Component
public class QaAiClient {

    private static final Logger log = LoggerFactory.getLogger(QaAiClient.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final String baseUrl;
    private final boolean logPayload;

    public QaAiClient(
            @Value("${yunti.ai.qa-base-url:http://127.0.0.1:9100}") String baseUrl,
            @Value("${yunti.ai.log-payload:true}") boolean logPayload
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "http://127.0.0.1:9100" : baseUrl.replaceAll("/+$", "");
        this.logPayload = logPayload;
    }

    public EvaluateResult evaluate(EvaluateRequest request) {
        String traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        long startedAt = System.currentTimeMillis();
        int ruleCount = request.ruleNames() == null ? 0 : request.ruleNames().size();
        int transcriptChars = request.transcript() == null ? 0 : request.transcript().length();
        log.info("调用 AI 质检开始 trace={} tenant={} session={} agent={} rules={} transcriptChars={}",
                traceId, request.tenantCode(), request.sessionName(), request.agentName(),
                ruleCount, transcriptChars);
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenant_code", request.tenantCode());
            body.put("session_name", request.sessionName());
            body.put("agent_name", request.agentName());
            body.put("transcript", request.transcript());
            body.put("rule_names", request.ruleNames());
            body.put("rules", request.rules() == null ? List.of() : request.rules());

            String payload = objectMapper.writeValueAsString(body);
            if (logPayload) {
                log.info("AI 质检请求参数 trace={} body={}", traceId, payload);
            }
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/qa/evaluate"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("X-Tenant-Code", request.tenantCode())
                    .header("X-Request-Id", traceId)
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            long cost = System.currentTimeMillis() - startedAt;
            if (logPayload) {
                log.info("AI 质检响应参数 trace={} status={} cost={}ms body={}",
                        traceId, response.statusCode(), cost, response.body());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("调用 AI 质检失败 trace={} tenant={} session={} status={} cost={}ms reason={}",
                        traceId, request.tenantCode(), request.sessionName(),
                        response.statusCode(), cost, safe(response.body()));
                return fallback(request, "HTTP " + response.statusCode() + ": " + response.body());
            }
            ApiResponse<EvaluateResult> parsed = objectMapper.readValue(
                    response.body(),
                    new TypeReference<>() {
                    }
            );
            if (parsed == null || parsed.code() != 0 || parsed.data() == null) {
                log.warn("调用 AI 质检异常 trace={} tenant={} session={} status={} cost={}ms reason={}",
                        traceId, request.tenantCode(), request.sessionName(),
                        response.statusCode(), cost, parsed == null ? "AI 质检无响应" : parsed.message());
                return fallback(request, parsed == null ? "AI 质检无响应" : parsed.message());
            }
            EvaluateResult result = parsed.data();
            log.info("调用 AI 质检成功 trace={} tenant={} session={} cost={}ms score={} risk={} hitRules={} source={}",
                    traceId, request.tenantCode(), request.sessionName(), cost,
                    result.aiScore(), result.riskLevel(),
                    result.rules() == null ? 0 : result.rules().size(), result.source());
            return result;
        } catch (Exception e) {
            log.error("调用 AI 质检异常 trace={} tenant={} session={} cost={}ms error={}",
                    traceId, request.tenantCode(), request.sessionName(),
                    System.currentTimeMillis() - startedAt, e.toString(), e);
            return fallback(request, e.getMessage());
        }
    }

    private EvaluateResult fallback(EvaluateRequest request, String reason) {
        log.warn("AI 质检降级为规则兜底 tenant={} session={} reason={}",
                request.tenantCode(), request.sessionName(), safe(reason));
        return new EvaluateResult(
                80,
                1,
                List.of(),
                "AI 质检服务暂不可用，已使用规则引擎兜底：" + safe(reason),
                "fallback-rule"
        );
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "未配置或调用失败";
        }
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    public record EvaluateRequest(
            String tenantCode,
            String sessionName,
            String agentName,
            String transcript,
            List<String> ruleNames,
            List<RuleSpec> rules
    ) {
    }

    /** 质检规则：名称 + 检查内容（交给 AI 判定） */
    public record RuleSpec(String name, String content) {
    }

    public record EvaluateResult(
            int aiScore,
            int riskLevel,
            List<String> rules,
            String comment,
            String source
    ) {
    }
}
```

### 6.2 会话对话来源

AI 质检要有真实对话才有意义。会话和消息在 customer\_db 的 `session`、`session_message` 两张表里，下面补上读取消息的实体、Mapper 和 SQL。

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/entity/SessionMessage.java

``` code-block-container
package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * session_message 会话消息表实体：AI 质检的真实对话来源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("session_message")
public class SessionMessage {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long sessionId;

    /** 消息编号（客户端幂等键） */
    private String msgNo;

    /** 类型码：1-文本、2-图片、3-卡片、4-事件、5-系统 */
    private Integer msgType;

    /** 发送方码：1-客户、2-坐席、3-机器人、4-系统 */
    private Integer senderType;

    private Long senderId;

    /** 消息内容（文本或 JSON 结构） */
    private String content;

    private LocalDateTime sendTime;

    @TableField("is_deleted")
    private Boolean deleted;
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/SessionMessageMapper.java

``` code-block-container
package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.SessionMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * session_message 会话消息 Mapper。
 */
@Mapper
public interface SessionMessageMapper extends BaseMapper<SessionMessage> {

    /**
     * 按会话读取对话记录（时间正序，SQL 见 SessionMessageMapper.xml）。
     */
    List<SessionMessage> selectDialog(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId,
            @Param("limit") int limit
    );
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/resources/mapper/SessionMessageMapper.xml

``` code-block-container
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="cn.net.susan.customer.mapper.SessionMessageMapper">

    <!-- 按会话取对话记录：只取文本/卡片类消息，按时间正序，供 AI 质检还原真实会话 -->
    <select id="selectDialog" resultType="cn.net.susan.customer.entity.SessionMessage">
        SELECT id, tenant_code, session_id, msg_no, msg_type, sender_type, sender_id, content, send_time
          FROM session_message
         WHERE tenant_code = #{tenantCode}
           AND session_id = #{sessionId}
           AND is_deleted = FALSE
         ORDER BY send_time, id
         LIMIT #{limit}
    </select>
</mapper>
```

### 6.3 质检业务改造

QaService 的改动集中在三处：

1.  删掉原来的哈希凑分，改为调用 QaAiClient；

2.  送检时带上规则的检查内容（原来只传规则名）；

3.  组装送检内容：优先真实会话对话，取不到就用示例对话，历史任务按会话名回填。

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/service/QaService.java

``` code-block-container
package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.QaReview;
import cn.net.susan.customer.entity.QaRule;
import cn.net.susan.customer.entity.QaTask;
import cn.net.susan.customer.entity.SessionMessage;
import cn.net.susan.customer.internal.QaAiClient;
import cn.net.susan.customer.mapper.QaReviewMapper;
import cn.net.susan.customer.mapper.QaRuleMapper;
import cn.net.susan.customer.mapper.QaTaskMapper;
import cn.net.susan.customer.mapper.SessionMessageMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质检中心：规则配置、任务列表、AI 全量扫描、人工复核。
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    private static final DateTimeFormatter TASK_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 送检对话的最大消息条数，避免超长会话把提示词撑爆 */
    private static final int MAX_DIALOG_MESSAGES = 60;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 默认规则：名称、类型码、类型文案、检查内容 */
    private static final RuleSeed[] DEFAULT_RULES = {
            new RuleSeed("敏感词与禁语", 1, "敏感词", "排查“妈的、废物、随便你”等不礼貌用语"),
            new RuleSeed("服务承诺规范", 2, "承诺规范", "承诺必须包含明确时间与可兑现口径"),
            new RuleSeed("必答项完整", 3, "必答项", "订单号、地址、发票抬头等关键信息必须核实"),
            new RuleSeed("情绪安抚", 4, "情绪识别", "客户表达不满时应先致歉再安抚，禁止机械回复"),
    };

    private final QaRuleMapper qaRuleMapper;
    private final QaTaskMapper qaTaskMapper;
    private final QaReviewMapper qaReviewMapper;
    private final SessionMessageMapper sessionMessageMapper;
    private final QaAiClient qaAiClient;
    private final SnowflakeIdGenerator idGenerator;

    public QaService(
            QaRuleMapper qaRuleMapper,
            QaTaskMapper qaTaskMapper,
            QaReviewMapper qaReviewMapper,
            SessionMessageMapper sessionMessageMapper,
            QaAiClient qaAiClient,
            SnowflakeIdGenerator idGenerator
    ) {
        this.qaRuleMapper = qaRuleMapper;
        this.qaTaskMapper = qaTaskMapper;
        this.qaReviewMapper = qaReviewMapper;
        this.sessionMessageMapper = sessionMessageMapper;
        this.qaAiClient = qaAiClient;
        this.idGenerator = idGenerator;
    }

    /**
     * 看板概览。
     */
    public OverviewVO overview(LoginUser user) {
        String tenant = tenantOf(user);
        ensureDemoData(tenant, user.userId());
        List<QaTask> tasks = qaTaskMapper.selectList(
                Wrappers.<QaTask>lambdaQuery()
                        .eq(QaTask::getTenantCode, tenant)
                        .eq(QaTask::getDeleted, false));
        int total = tasks.size();
        int pending = countStatus(tasks, 1);
        int passed = countStatus(tasks, 2);
        int rejected = countStatus(tasks, 3);
        double avgAi = average(tasks.stream().map(QaTask::getAiScore).toList());
        double avgReview = average(tasks.stream()
                .filter(t -> t.getReviewScore() != null)
                .map(QaTask::getReviewScore).toList());
        int riskLow = countRisk(tasks, 1);
        int riskMid = countRisk(tasks, 2);
        int riskHigh = countRisk(tasks, 3);
        double passRate = total == 0 ? 0 : round2(passed * 100.0 / total);
        return new OverviewVO(total, pending, passed, rejected, round2(avgAi), round2(avgReview),
                passRate, riskLow, riskMid, riskHigh);
    }

    /**
     * 任务列表。
     */
    public List<TaskVO> tasks(LoginUser user, Integer status, String keyword) {
        String tenant = tenantOf(user);
        ensureDemoData(tenant, user.userId());
        List<QaTask> rows = qaTaskMapper.selectList(
                Wrappers.<QaTask>lambdaQuery()
                        .eq(QaTask::getTenantCode, tenant)
                        .eq(QaTask::getDeleted, false)
                        .eq(status != null, QaTask::getStatus, status)
                        .orderByDesc(QaTask::getCreateTime));
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        return rows.stream()
                .filter(t -> kw.isBlank() || matches(t, kw))
                .map(this::toTaskVO)
                .toList();
    }

    /**
     * 任务详情（含规则评分明细）。
     */
    public TaskDetailVO detail(LoginUser user, String taskNo) {
        String tenant = tenantOf(user);
        QaTask task = requireTask(tenant, taskNo);
        AiResult ai = parseAi(task);
        List<RuleResultVO> results = new ArrayList<>();
        List<QaRule> rules = enabledRules(tenant);
        for (QaRule rule : rules) {
            boolean fail = ai.rules().contains(rule.getRuleName());
            results.add(new RuleResultVO(
                    rule.getRuleName(),
                    typeText(rule.getRuleType()),
                    !fail,
                    fail ? "AI 标记命中：关注 " + rule.getRuleContent() : "未命中该规则风险项"
            ));
        }
        return new TaskDetailVO(
                String.valueOf(task.getId()),
                task.getTaskNo(),
                ai.sessionName(),
                ai.agentName(),
                number(task.getAiScore()),
                number(task.getReviewScore()),
                task.getRiskLevel(),
                riskText(task.getRiskLevel()),
                task.getStatus(),
                statusText(task.getStatus()),
                task.getCreateTime() == null ? null : task.getCreateTime().toString(),
                task.getReviewTime() == null ? null : task.getReviewTime().toString(),
                ai.comment(),
                ai.rules(),
                results,
                task.getReviewerId() == null ? null : String.valueOf(task.getReviewerId())
        );
    }

    /**
     * 发起 AI 全量质检：按启用规则扫描样本会话，生成待复核任务。
     */
    @Transactional
    public ScanResult scan(LoginUser user) {
        String tenant = tenantOf(user);
        ensureRules(tenant, user.userId());
        List<QaTask> created = new ArrayList<>();
        SampleSpec[] pool = SAMPLE_POOL;
        for (int i = 0; i < 3; i++) {
            SampleSpec spec = pool[RANDOM.nextInt(pool.length)];
            QaTask task = buildSampleTask(tenant, spec);
            qaTaskMapper.insert(task);
            applyAiEvaluation(tenant, task);
            qaTaskMapper.updateById(task);
            created.add(task);
        }
        log.info("AI 全量质检完成 tenant={}, created={}", tenant, created.size());
        return new ScanResult(created.size(), created.stream().map(QaTask::getTaskNo).toList());
    }

    /**
     * 新增单个质检：针对某个会话手动创建一条待复核任务。
     */
    @Transactional
    public TaskVO createManual(
            LoginUser user,
            String sessionName,
            String agentName,
            Double aiScore,
            Integer riskLevel,
            String comment,
            List<String> ruleNames
    ) {
        String tenant = tenantOf(user);
        ensureRules(tenant, user.userId());
        if (sessionName == null || sessionName.isBlank()) {
            throw new BizException(40001, "请填写质检会话名称");
        }
        if (agentName == null || agentName.isBlank()) {
            throw new BizException(40001, "请填写接待客服");
        }
        int risk = riskLevel == null ? 1 : riskLevel;
        if (risk < 1 || risk > 3) {
            throw new BizException(40001, "风险级别不正确");
        }
        double score = aiScore == null ? 80 : Math.max(0, Math.min(100, aiScore));
        List<String> validRules = rules(user).stream()
                .map(QaService.RuleVO::ruleName)
                .filter(name -> ruleNames != null && ruleNames.contains(name))
                .toList();

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("sessionName", sessionName.trim());
        ai.put("agentName", agentName.trim());
        ai.put("comment", comment == null || comment.isBlank() ? "人工发起单个质检，请复核人结合会话内容给出结论。" : comment.trim());
        ai.put("rules", validRules);

        QaTask task = QaTask.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .taskNo(generateTaskNo(tenant))
                .sessionId(idGenerator.nextId())
                .agentId(null)
                .aiScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP))
                .aiResult(writeJson(ai))
                .riskLevel(risk)
                .status(1)
                .creator(String.valueOf(user.userId()))
                .createTime(now)
                .updateTime(now)
                .deleted(false)
                .build();
        qaTaskMapper.insert(task);
        log.info("人工新增单个质检 tenant={}, taskNo={}, creator={}", tenant, task.getTaskNo(), user.userId());
        return toTaskVO(task);
    }

    /**
     * 人工复核：1-通过、2-驳回、3-重检。
     */
    @Transactional
    public TaskVO review(LoginUser user, String taskNo, int action, Double score, String comment) {
        String tenant = tenantOf(user);
        QaTask task = requireTask(tenant, taskNo);
        LocalDateTime now = LocalDateTime.now();
        if (action == 1 || action == 2) {
            if (score == null || score < 0 || score > 100) {
                throw new BizException(40001, "复核评分必须在 0~100 之间");
            }
            if (action == 2 && (comment == null || comment.isBlank())) {
                throw new BizException(40001, "驳回时必须填写复核意见");
            }
            task.setStatus(action == 1 ? 2 : 3);
            task.setReviewScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
            task.setReviewerId(user.userId());
            task.setReviewTime(now);
        } else if (action == 3) {
            task.setStatus(1);
            task.setReviewScore(null);
            task.setReviewerId(null);
            task.setReviewTime(null);
        } else {
            throw new BizException(40001, "复核动作不支持");
        }
        task.setEditor(String.valueOf(user.userId()));
        task.setUpdateTime(now);
        qaTaskMapper.updateById(task);

        qaReviewMapper.insert(QaReview.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .taskId(task.getId())
                .reviewerId(user.userId())
                .action(action)
                .comment(comment == null || comment.isBlank() ? null : comment.trim())
                .reviewTime(now)
                .build());
        return toTaskVO(task);
    }

    /**
     * 批量复核：多条任务一次性通过/驳回/重检。
     */
    @Transactional
    public BatchReviewResult batchReview(
            LoginUser user,
            List<String> taskNos,
            int action,
            Double score,
            String comment
    ) {
        if (taskNos == null || taskNos.isEmpty()) {
            throw new BizException(40001, "请选择至少一条质检任务");
        }
        if (taskNos.size() > 200) {
            throw new BizException(40001, "单次批量质检最多选择 200 条任务");
        }
        for (String taskNo : taskNos) {
            review(user, taskNo, action, score, comment);
        }
        return new BatchReviewResult(taskNos.size(), List.copyOf(taskNos));
    }

    /**
     * 批量 AI 质检：对选中的任务重新执行 AI/规则初检，并回到待复核队列。
     */
    @Transactional
    public BatchAiResult batchAiCheck(LoginUser user, List<String> taskNos) {
        String tenant = tenantOf(user);
        if (taskNos == null || taskNos.isEmpty()) {
            throw new BizException(40001, "请选择至少一条质检任务");
        }
        if (taskNos.size() > 200) {
            throw new BizException(40001, "单次批量 AI 质检最多选择 200 条任务");
        }
        List<QaRule> enabled = enabledRules(tenant);
        LocalDateTime now = LocalDateTime.now();
        for (String taskNo : taskNos) {
            QaTask task = requireTask(tenant, taskNo);
            applyAiEvaluation(tenant, task);
            task.setStatus(1);
            task.setReviewScore(null);
            task.setReviewerId(null);
            task.setReviewTime(null);
            task.setEditor(String.valueOf(user.userId()));
            task.setUpdateTime(now);
            qaTaskMapper.updateById(task);
        }
        return new BatchAiResult(taskNos.size(), List.copyOf(taskNos));
    }

    private void applyAiEvaluation(String tenant, QaTask task) {
        AiResult old = parseAi(task);
        List<QaRule> enabled = enabledRules(tenant);
        List<String> ruleNames = enabled.stream().map(QaRule::getRuleName).toList();
        List<QaAiClient.RuleSpec> ruleSpecs = enabled.stream()
                .map(rule -> new QaAiClient.RuleSpec(rule.getRuleName(), rule.getRuleContent()))
                .toList();
        String realDialog = loadRealDialog(tenant, task.getSessionId());
        String dialog = realDialog != null ? realDialog : effectiveSampleDialog(old);
        // 真实会话记录自带表头，示例数据则拼「摘要 + 对话」
        String transcript = realDialog != null ? realDialog : buildSampleTranscript(old, dialog);
        QaAiClient.EvaluateResult aiResult = qaAiClient.evaluate(new QaAiClient.EvaluateRequest(
                tenant,
                old.sessionName(),
                old.agentName(),
                transcript,
                ruleNames,
                ruleSpecs
        ));
        int score = aiResult.aiScore();
        int risk = aiResult.riskLevel();
        List<String> hitRules = aiResult.rules() == null ? List.of() : aiResult.rules();
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("sessionName", old.sessionName());
        ai.put("agentName", old.agentName());
        ai.put("comment", aiResult.comment() == null || aiResult.comment().isBlank()
                ? "AI 质检完成，请人工抽样复核确认。"
                : aiResult.comment());
        ai.put("rules", hitRules);
        ai.put("source", aiResult.source());
        // 落库对话内容：后续批量质检、复核时复用同一份会话记录，历史任务也顺带回填
        if (dialog != null && !dialog.isBlank()) {
            ai.put("dialog", dialog);
        }
        task.setAiScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
        task.setAiResult(writeJson(ai));
        task.setRiskLevel(risk);
    }

    /**
     * 组装示例任务的送检内容：会话摘要 + 对话记录。
     */
    private String buildSampleTranscript(AiResult sample, String dialog) {
        StringBuilder transcript = new StringBuilder()
                .append("会话场景：").append(sample.sessionName())
                .append("\n接待客服：").append(sample.agentName())
                .append("\n会话摘要：").append(sample.comment());
        if (dialog != null && !dialog.isBlank()) {
            transcript.append("\n\n【对话记录】\n").append(dialog);
        }
        return transcript.toString();
    }

    /**
     * 取示例数据的对话内容。
     *
     * <p>历史任务（早于"记录对话内容"版本创建的）里没有存 dialog，这里按会话名从示例池回填，
     * 否则 AI 只能拿到一句摘要，会直接回"会话记录缺失实际对话内容"。</p>
     */
    private String effectiveSampleDialog(AiResult sample) {
        if (sample.dialog() != null && !sample.dialog().isBlank()) {
            return sample.dialog();
        }
        for (SampleSpec spec : SAMPLE_POOL) {
            if (spec.sessionName().equals(sample.sessionName())) {
                log.info("质检任务缺少对话记录，已按示例会话回填 session={}", sample.sessionName());
                return spec.dialog();
            }
        }
        return null;
    }

    /**
     * 读取真实会话对话；没有真实消息（或会话为空）时返回 null。
     */
    private String loadRealDialog(String tenant, Long sessionId) {
        if (sessionId == null) {
            return null;
        }
        List<SessionMessage> messages = sessionMessageMapper.selectDialog(tenant, sessionId, MAX_DIALOG_MESSAGES);
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        StringBuilder dialog = new StringBuilder("【真实会话记录】\n");
        for (SessionMessage message : messages) {
            dialog.append(senderLabel(message.getSenderType()))
                    .append('：')
                    .append(messageText(message.getContent()))
                    .append('\n');
        }
        return dialog.toString();
    }

    private String senderLabel(Integer senderType) {
        if (senderType == null) {
            return "未知";
        }
        return switch (senderType) {
            case 1 -> "客户";
            case 2 -> "客服";
            case 3 -> "机器人";
            default -> "系统";
        };
    }

    /**
     * 消息内容可能是纯文本，也可能是 {"text": "..."} 之类的 JSON 结构。
     */
    private String messageText(String content) {
        if (content == null || content.isBlank()) {
            return "[非文本消息]";
        }
        String text = content.trim();
        if (text.startsWith("{")) {
            try {
                Map<String, Object> map = JSON.readValue(text, new TypeReference<Map<String, Object>>() {
                });
                Object value = map.containsKey("text") ? map.get("text") : map.get("content");
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (Exception ignored) {
                // 不是标准 JSON，按纯文本处理
            }
        }
        return text;
    }
    /**
     * 规则列表。
     */
    public List<RuleVO> rules(LoginUser user) {
        String tenant = tenantOf(user);
        ensureRules(tenant, user.userId());
        return qaRuleMapper.selectList(
                        Wrappers.<QaRule>lambdaQuery()
                                .eq(QaRule::getTenantCode, tenant)
                                .eq(QaRule::getDeleted, false)
                                .orderByAsc(QaRule::getRuleType)
                                .orderByDesc(QaRule::getCreateTime))
                .stream().map(this::toRuleVO).toList();
    }

    /**
     * 新建/编辑规则。
     */
    @Transactional
    public RuleVO saveRule(LoginUser user, String id, String ruleName, int ruleType,
                           String ruleContent, int weight, boolean enabled) {
        String tenant = tenantOf(user);
        if (ruleType < 1 || ruleType > 4) {
            throw new BizException(40001, "规则类型不正确");
        }
        weight = Math.max(0, Math.min(100, weight));
        LocalDateTime now = LocalDateTime.now();
        QaRule rule;
        if (id == null || id.isBlank()) {
            long exists = qaRuleMapper.selectCount(
                    Wrappers.<QaRule>lambdaQuery()
                            .eq(QaRule::getTenantCode, tenant)
                            .eq(QaRule::getRuleName, ruleName.trim())
                            .eq(QaRule::getDeleted, false));
            if (exists > 0) {
                throw new BizException(40002, "已存在同名质检规则，请修改名称后重试");
            }
            QaRule created = QaRule.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .ruleName(ruleName.trim())
                    .ruleType(ruleType)
                    .ruleContent(ruleContent)
                    .weight(weight)
                    .isEnabled(enabled)
                    .creator(String.valueOf(user.userId()))
                    .deleted(false)
                    .build();
            try {
                qaRuleMapper.insert(created);
                rule = created;
            } catch (DuplicateKeyException e) {
                throw new BizException(40002, "已存在同名质检规则，请修改名称后重试");
            }
        } else {
            rule = qaRuleMapper.selectById(Long.parseLong(id));
            if (rule == null || !tenant.equals(rule.getTenantCode())
                    || Boolean.TRUE.equals(rule.getDeleted())) {
                throw new BizException(40401, "规则不存在");
            }
            rule.setRuleName(ruleName.trim());
            rule.setRuleType(ruleType);
            rule.setRuleContent(ruleContent);
            rule.setWeight(weight);
            rule.setIsEnabled(enabled);
            rule.setEditor(String.valueOf(user.userId()));
            rule.setUpdateTime(now);
            qaRuleMapper.updateById(rule);
        }
        return toRuleVO(rule);
    }

    /**
     * 删除规则（逻辑删除）。
     */
    @Transactional
    public void deleteRule(LoginUser user, String id) {
        String tenant = tenantOf(user);
        QaRule rule = qaRuleMapper.selectById(Long.parseLong(id));
        if (rule == null || !tenant.equals(rule.getTenantCode())
                || Boolean.TRUE.equals(rule.getDeleted())) {
            throw new BizException(40401, "规则不存在");
        }
        rule.setDeleted(true);
        rule.setEditor(String.valueOf(user.userId()));
        rule.setUpdateTime(LocalDateTime.now());
        qaRuleMapper.updateById(rule);
    }

    private void ensureDemoData(String tenant, long userId) {
        ensureRules(tenant, userId);
        long count = qaTaskMapper.selectCount(
                Wrappers.<QaTask>lambdaQuery()
                        .eq(QaTask::getTenantCode, tenant)
                        .eq(QaTask::getDeleted, false));
        if (count == 0) {
            for (int i = 0; i < 6; i++) {
                qaTaskMapper.insert(buildSampleTask(tenant, SAMPLE_POOL[i]));
            }
        }
    }

    private void ensureRules(String tenant, long userId) {
        long count = qaRuleMapper.selectCount(
                Wrappers.<QaRule>lambdaQuery()
                        .eq(QaRule::getTenantCode, tenant)
                        .eq(QaRule::getDeleted, false));
        if (count > 0) {
            return;
        }
        for (RuleSeed seed : DEFAULT_RULES) {
            qaRuleMapper.insertIgnore(QaRule.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .ruleName(seed.name())
                    .ruleType(seed.type())
                    .ruleContent(seed.content())
                    .weight(25)
                    .isEnabled(true)
                    .creator(String.valueOf(userId))
                    .deleted(false)
                    .build());
        }
    }

    private QaTask buildSampleTask(String tenant, SampleSpec spec) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("sessionName", spec.sessionName());
        ai.put("agentName", spec.agentName());
        ai.put("comment", spec.comment());
        ai.put("rules", spec.ruleNames());
        ai.put("dialog", spec.dialog());
        return QaTask.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .taskNo(generateTaskNo(tenant))
                .sessionId(idGenerator.nextId())
                .agentId(null)
                .aiScore(BigDecimal.valueOf(spec.aiScore()).setScale(2, RoundingMode.HALF_UP))
                .aiResult(writeJson(ai))
                .riskLevel(spec.riskLevel())
                .status(1)
                .creator("AI_QA")
                .createTime(now)
                .updateTime(now)
                .deleted(false)
                .build();
    }

    private String generateTaskNo(String tenant) {
        for (int i = 0; i < 10; i++) {
            String no = "QC" + TASK_TIME.format(LocalDateTime.now()) + randomCode(3);
            long count = qaTaskMapper.selectCount(
                    Wrappers.<QaTask>lambdaQuery()
                            .eq(QaTask::getTenantCode, tenant)
                            .eq(QaTask::getTaskNo, no));
            if (count == 0) {
                return no;
            }
        }
        throw new BizException(50001, "质检任务编号生成失败");
    }

    private QaTask requireTask(String tenant, String taskNo) {
        QaTask task = qaTaskMapper.selectOne(
                Wrappers.<QaTask>lambdaQuery()
                        .eq(QaTask::getTenantCode, tenant)
                        .eq(QaTask::getTaskNo, taskNo)
                        .eq(QaTask::getDeleted, false)
                        .last("LIMIT 1"));
        if (task == null) {
            throw new BizException(40401, "质检任务不存在");
        }
        return task;
    }

    private TaskVO toTaskVO(QaTask task) {
        AiResult ai = parseAi(task);
        return new TaskVO(
                String.valueOf(task.getId()),
                task.getTaskNo(),
                ai.sessionName(),
                ai.agentName(),
                number(task.getAiScore()),
                number(task.getReviewScore()),
                task.getRiskLevel(),
                riskText(task.getRiskLevel()),
                task.getStatus(),
                statusText(task.getStatus()),
                task.getCreateTime() == null ? null : task.getCreateTime().toString().replace('T', ' '),
                task.getReviewTime() == null ? null : task.getReviewTime().toString().replace('T', ' '),
                ai.comment(),
                ai.rules()
        );
    }

    private RuleVO toRuleVO(QaRule rule) {
        return new RuleVO(
                String.valueOf(rule.getId()),
                rule.getRuleName(),
                rule.getRuleType(),
                typeText(rule.getRuleType()),
                rule.getRuleContent(),
                rule.getWeight(),
                Boolean.TRUE.equals(rule.getIsEnabled()),
                rule.getUpdateTime() == null ? null : rule.getUpdateTime().toString().replace('T', ' ')
        );
    }

    private List<QaRule> enabledRules(String tenant) {
        return qaRuleMapper.selectList(
                Wrappers.<QaRule>lambdaQuery()
                        .eq(QaRule::getTenantCode, tenant)
                        .eq(QaRule::getDeleted, false)
                        .eq(QaRule::getIsEnabled, true)
                        .orderByAsc(QaRule::getRuleType));
    }

    private AiResult parseAi(QaTask task) {
        if (task.getAiResult() == null || task.getAiResult().isBlank()) {
            return new AiResult("会话-" + task.getId(), "客服", "AI 未返回明细", List.of(), "");
        }
        try {
            Map<String, Object> map = JSON.readValue(task.getAiResult(),
                    new TypeReference<Map<String, Object>>() {
                    });
            @SuppressWarnings("unchecked")
            List<String> rules = (List<String>) map.getOrDefault("rules", List.of());
            return new AiResult(
                    string(map.get("sessionName"), "会话"),
                    string(map.get("agentName"), "客服"),
                    string(map.get("comment"), ""),
                    rules == null ? List.of() : rules,
                    string(map.get("dialog"), "")
            );
        } catch (Exception e) {
            return new AiResult("会话-" + task.getId(), "客服", "AI 结果解析失败", List.of(), "");
        }
    }

    private boolean matches(QaTask task, String kw) {
        AiResult ai = parseAi(task);
        String hay = (task.getTaskNo() + " " + ai.sessionName() + " " + ai.agentName()).toLowerCase();
        return hay.contains(kw);
    }

    private String writeJson(Map<String, Object> map) {
        try {
            String json = JSON.writeValueAsString(map);
            return json.length() > 500 ? json.substring(0, 500) : json;
        } catch (Exception e) {
            return "{}";
        }
    }

    private int countStatus(List<QaTask> tasks, int status) {
        return (int) tasks.stream().filter(t -> t.getStatus() != null && t.getStatus() == status).count();
    }

    private int countRisk(List<QaTask> tasks, int risk) {
        return (int) tasks.stream().filter(t -> t.getRiskLevel() != null && t.getRiskLevel() == risk).count();
    }

    private double average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return values.stream()
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .average().orElse(0);
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String typeText(int type) {
        return switch (type) {
            case 1 -> "敏感词";
            case 2 -> "承诺规范";
            case 3 -> "必答项";
            case 4 -> "情绪识别";
            default -> "其他";
        };
    }

    private String riskText(int risk) {
        return switch (risk) {
            case 1 -> "低风险";
            case 2 -> "中风险";
            case 3 -> "高风险";
            default -> "未知";
        };
    }

    private String statusText(int status) {
        return switch (status) {
            case 1 -> "待复核";
            case 2 -> "已通过";
            case 3 -> "已驳回";
            default -> "未知";
        };
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2) {
            throw new BizException(40301, "仅企业成员可使用质检中心");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用质检中心");
        }
        return tenant;
    }

    private String randomCode(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private record RuleSeed(String name, int type, String typeText, String content) {
    }

    private record SampleSpec(
            String sessionName,
            String agentName,
            double aiScore,
            int riskLevel,
            List<String> ruleNames,
            String comment,
            String dialog
    ) {
    }

    private record AiResult(String sessionName, String agentName, String comment, List<String> rules, String dialog) {
    }

    private static final SampleSpec[] SAMPLE_POOL = {
            new SampleSpec("C-2077 · 赵一诺", "陈思远", 92, 1, List.of(),
                    "整体服务专业，应答准确，建议保持并主动邀评。",
                    "客户：我买的东西什么时候能到？\n"
                            + "客服：您好，我看到您的订单 A20260911001 已从杭州仓发出，预计明天下午 3 点前送达，物流单号 SF1234567890，您可以在订单详情里实时查看。\n"
                            + "客户：好的，谢谢。\n"
                            + "客服：不客气，后续有任何问题随时找我。"),
            new SampleSpec("C-2078 · 王浩宇", "郑凯文", 78, 2, List.of("服务承诺规范"),
                    "承诺较含糊，未给出明确时间节点，建议补充可兑现时限。",
                    "客户：我上周申请的退款还没到账。\n"
                            + "客服：不好意思，我帮您催一下，尽快处理。\n"
                            + "客户：大概什么时候能到？\n"
                            + "客服：应该很快的，您放心。"),
            new SampleSpec("C-2079 · 苏明轩", "吴佳怡", 68, 3, List.of("敏感词与禁语", "情绪安抚"),
                    "出现不耐烦表述，客户不满时未先安抚，需重点改进。",
                    "客户：你们这什么服务，等了一个小时都没人理我！\n"
                            + "客服：这个问题不是我们这边的问题，您自己看看是不是地址填错了，我这边也管不了。\n"
                            + "客户：你什么态度？\n"
                            + "客服：我态度就这样，您要投诉就投诉吧。"),
            new SampleSpec("C-2080 · 张梦琪", "林晓彤", 96, 1, List.of(),
                    "话术符合最新政策，情绪管理与升级机制均规范。",
                    "客户：我想问一下发票怎么开。\n"
                            + "客服：您好，开票需要提供公司名称和税号，请问是开公司抬头还是个人抬头？\n"
                            + "客户：公司的。\n"
                            + "客服：好的，请把公司名称和税号发给我，我提交后 24 小时内会发送到您的邮箱。"),
            new SampleSpec("C-2081 · 罗晓峰", "周子航", 84, 2, List.of("必答项完整"),
                    "未主动核实订单号，已补充确认，建议强化关键信息核验习惯。",
                    "客户：我的订单一直没收到。\n"
                            + "客服：好的，我帮您看看物流。\n"
                            + "客户：你都不问我订单号吗？\n"
                            + "客服：抱歉，麻烦提供一下订单号，我马上为您核实。"),
            new SampleSpec("C-2082 · 陈博文", "孙雨桐", 88, 1, List.of("服务承诺规范"),
                    "承诺了补偿但未明确到账时间，需按规范补充时限。",
                    "客户：收到的商品有质量问题，怎么处理？\n"
                            + "客服：非常抱歉给您带来不好的体验，可以为您办理退货，同时补偿 20 元优惠券。\n"
                            + "客户：优惠券什么时候到账？\n"
                            + "客服：这个后面会安排的，您放心。"),
            new SampleSpec("C-2083 · 刘雅静", "郑凯文", 90, 1, List.of(),
                    "处理路径清晰，主动同步进度，服务体验良好。",
                    "客户：我的优惠券怎么用不了？\n"
                            + "客服：您好，这张券满 199 元可用，您当前订单是 168 元，还差 31 元。需要我帮您看看有没有合适的凑单品吗？\n"
                            + "客户：好的，麻烦你了。\n"
                            + "客服：已经为您推荐两款常用商品，加入购物车后即可使用优惠券。"),
            new SampleSpec("C-2084 · 马天宇", "陈思远", 75, 2, List.of("情绪安抚", "必答项完整"),
                    "客户情绪激动时回应偏机械，需加强共情话术。",
                    "客户：我要投诉！快递员把包裹直接扔在门口就走了！\n"
                            + "客服：您好，请提供一下订单号，我这边核实。\n"
                            + "客户：我现在很生气，你们就是这么送快递的吗？\n"
                            + "客服：嗯，那您先给我订单号。"),
            new SampleSpec("C-2085 · 高雯", "吴佳怡", 81, 2, List.of("敏感词与禁语"),
                    "个别表述口语化较随意，建议使用标准化话术。",
                    "客户：这个价格还能再便宜一点吗？\n"
                            + "客服：这个价已经很实惠了，别家您随便看看，都差不多这个价。\n"
                            + "客户：好吧。\n"
                            + "客服：嗯，那您考虑下。"),
            new SampleSpec("C-2086 · 沈倩", "周子航", 71, 3, List.of("服务承诺规范", "情绪安抚"),
                    "两次承诺时间不一致，且未安抚客户，需重点复盘。",
                    "客户：我的退款到底什么时候能到？\n"
                            + "客服：您别急，这两天会给您处理的，差不多一周左右吧。\n"
                            + "客户：上周你们就说到账了！\n"
                            + "客服：那是系统那边的问题，您再等等。"),
    };

    /**
     * 看板概览。
     */
    public record OverviewVO(
            int total,
            int pending,
            int passed,
            int rejected,
            double avgAiScore,
            double avgReviewScore,
            double passRate,
            int riskLow,
            int riskMid,
            int riskHigh
    ) {
    }

    /**
     * 规则视图。
     */
    public record RuleVO(
            String id,
            String ruleName,
            int ruleType,
            String ruleTypeText,
            String ruleContent,
            int weight,
            boolean enabled,
            String updateTime
    ) {
    }

    /**
     * 任务视图。
     */
    public record TaskVO(
            String id,
            String taskNo,
            String sessionName,
            String agentName,
            Double aiScore,
            Double reviewScore,
            int riskLevel,
            String riskText,
            int status,
            String statusText,
            String createTime,
            String reviewTime,
            String aiComment,
            List<String> ruleNames
    ) {
    }

    /**
     * 任务详情。
     */
    public record TaskDetailVO(
            String id,
            String taskNo,
            String sessionName,
            String agentName,
            Double aiScore,
            Double reviewScore,
            int riskLevel,
            String riskText,
            int status,
            String statusText,
            String createTime,
            String reviewTime,
            String aiComment,
            List<String> ruleNames,
            List<RuleResultVO> ruleResults,
            String reviewerId
    ) {
    }

    /**
     * 规则评分明细。
     */
    public record RuleResultVO(String name, String type, boolean pass, String reason) {
    }

    /**
     * 全量扫描结果。
     */
    public record ScanResult(int created, List<String> taskNos) {
    }

    /**
     * 批量复核结果。
     */
    public record BatchReviewResult(int processed, List<String> taskNos) {
    }

    /**
     * 批量 AI 质检结果。
     */
    public record BatchAiResult(int processed, List<String> taskNos) {
    }
}
```

### 6.4 配置

### 文件：yunti-backend/yunti-customer-service/src/main/resources/application.yml

``` code-block-container
server:
  port: 9093

spring:
  application:
    name: yunti-customer-service
  datasource:
    url: jdbc:postgresql://${YUNTI_DB_HOST:127.0.0.1}:${YUNTI_DB_PORT:5432}/${YUNTI_CUSTOMER_DB:customer_db}
    username: ${YUNTI_DB_USER:mac}
    password: ${YUNTI_DB_PASSWORD:}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 3000

yunti:
  auth:
    jwt-secret: ${YUNTI_JWT_SECRET:yunti-customer-service-jwt-secret-please-change-in-prod-0123456789}
  user:
    internal-base-url: ${YUNTI_USER_INTERNAL_URL:http://127.0.0.1:9091}
  file:
    storage:
      # local=本地磁盘（开发演示）；rustfs=RustFS / MinIO / S3 兼容对象存储
      type: ${YUNTI_FILE_STORAGE_TYPE:local}
      local-dir: ${YUNTI_FILE_LOCAL_DIR:/tmp/yunti-files}
      rustfs:
        endpoint: ${YUNTI_RUSTFS_ENDPOINT:127.0.0.1}
        port: ${YUNTI_RUSTFS_PORT:9000}
        access-key: ${YUNTI_RUSTFS_ACCESS_KEY:minioadmin}
        secret-key: ${YUNTI_RUSTFS_SECRET_KEY:minioadmin}
        bucket: ${YUNTI_RUSTFS_BUCKET:yunti}
        secure: ${YUNTI_RUSTFS_SECURE:false}
      max-size: ${YUNTI_FILE_MAX_SIZE:10485760}
  ai:
    # yunti-ai AI 服务地址（质检初检由 Python AI 中心执行）
    qa-base-url: ${YUNTI_AI_QA_URL:http://127.0.0.1:9100}
    # 是否打印 AI 调用的完整入参/出参（排障用；生产环境可置 false 降低日志量）
    log-payload: ${YUNTI_AI_LOG_PAYLOAD:true}

mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.slf4j.Slf4jImpl
  global-config:
    banner: false
    db-config:
      id-type: input

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

## 七、README 补充

### 文件：yunti-ai/README.md

``` code-block-container
# yunti-ai · AI 编排中心骨架

对应系统架构文档中的 **ai-center（Python AI 服务集群）**：

| 子服务 | 职责 | 关键依赖 |
| --- | --- | --- |
| Agent 服务 | 对话编排（LangGraph 状态机）、意图/情绪、转人工 | langgraph / langchain |
| RAG 服务 | 知识检索（LlamaIndex）、向量库 | llama-index |
| 质检/训练服务 | 全量质检流水线、模型微调任务 | langgraph（批处理） |
| LLM 网关 | 千问 / DeepSeek 统一接入、降级与计量 | openai / 厂商 SDK |

## 快速运行（健康检查即可用）

```bash
cd yunti-ai
bash scripts/bootstrap.sh     # 一键创建 .venv 并安装依赖
./start.sh                   # 一键启动（自动管理端口与依赖）
```

验证：`curl http://localhost:9100/api/ai/health`

## 在 IntelliJ IDEA 中直接运行（参考企业知识库 Python 后端的方案）

项目自带两个共享运行配置（用 IDEA 打开 `yunti-ai` 目录后，右上角运行配置下拉即可看到）：

|                     |            |                                                                                          |
|---------------------|------------|------------------------------------------------------------------------------------------|
| 运行配置            | 类型       | 说明                                                                                     |
| `yunti-ai (Shell)`  | Shell 脚本 | **推荐**。无需安装 Python 插件，脚本自动管理 .venv/依赖/端口，与 IDEA 解释器设置完全解耦 |
| `yunti-ai (Python)` | Python     | 备选。需要安装并启用 Python 插件，且项目解释器指向 `.venv`                               |

### 方式一：yunti-ai (Shell)——零插件、零配置（推荐）

1.  用 IntelliJ IDEA 打开 `yunti-ai` 目录；

2.  右上角运行配置选择 **yunti-ai (Shell)**，点击 ▶ 启动；

3.  脚本会自动完成：检查并释放 9100 端口 → 校验/创建 `.venv` → 校验/安装依赖 → 打印启动信息并运行服务；

4.  控制台出现 `Uvicorn running on http://127.0.0.1:9100` 后，访问 `http://127.0.0.1:9100/api/ai/health` 验证；

5.  换端口：运行配置 → Script options 填入 `--port 9200`（支持 `--host`、`--no-reload`，详见 `./start.sh --help`）。

> 等价命令行：`./start.sh` 或 `./start.sh --port 9200`。

### 方式二：yunti-ai (Python)——需要 Python 插件

1.  安装 Python 插件：`Settings → Plugins → Marketplace`，搜索 **Python**（IDEA Community 为 **Python Community Edition**），安装后重启 IDEA；

2.  运行配置已直接绑定项目 `.venv`（不依赖项目解释器设置），右上角选择 **yunti-ai (Python)**，点击 ▶ 即可启动；

3.  若 IDEA 仍提示找不到解释器：`Settings → Project → Python Interpreter → Add Interpreter → Existing`，选择 `yunti-ai/.venv/bin/python`，再运行一次。

> **常见问题：Unknown run configuration type PythonConfigurationType** 该提示只影响 **yunti-ai (Python)**（它依赖 Python 插件）。解决方式：
>
> 1.  直接使用 **yunti-ai (Shell)** 启动，完全不需要 Python 插件（推荐）；
>
> 2.  若想用 Python 配置，安装并启用 Python 插件后重启 IDEA。

> **常见问题：SDK is not defined for Run Configuration** 这是 **yunti-ai (Python)** 的解释器问题（Shell 配置不会遇到）。解决方式：
>
> 1.  使用 **yunti-ai (Shell)** 启动；
>
> 2.  如必须用 Python 配置：`File → Project Structure → Project → Python Interpreter → Add Interpreter → Existing`，选择 `yunti-ai/.venv/bin/python`，再点 ▶。

> **常见问题：ModuleNotFoundError: No module named 'uvicorn'** 说明运行用的 Python 不是 `.venv`（如系统 Python 3.10，未装任何依赖）。处理：
>
> 1.  使用 **yunti-ai (Shell)** 启动，脚本会自动使用 `.venv` 并补齐依赖（最稳）；
>
> 2.  若用 Python 配置：当前共享配置已直接绑定 `.venv`，重新加载项目后即可生效；若旧运行条目仍在，删除旧条目后再从下拉框选择新的 **yunti-ai (Python)**；
>
> 3.  也可以手动把项目解释器指向 `yunti-ai/.venv/bin/python`（`Settings → Project → Python Interpreter → Add Interpreter → Existing`）。

其他等价启动命令（任选其一，默认 9100 端口，可用 `YUNTI_AI_PORT` / `YUNTI_AI_HOST` 覆盖）：

``` code-block-container
./.venv/bin/python main.py    # 与 IDEA Python 配置入口一致
./.venv/bin/python -m ai      # 包模块方式
```

## 完整 AI 能力

安装可选依赖后可启用真实 Agent/RAG：

``` code-block-container
pip install -r requirements-ai.txt
```

当前未装 AI 依赖时，对话接口返回 **mock 回复**（代码里已留好接入点），保证骨架始终可运行。

## Java ↔ Python 通信约定（与后端对齐）

-   实时对话：Java customer-service 调 `POST /api/ai/v1/agent/chat`（后续演进 SSE 流式）；

-   批处理（训练/全量质检）：Kafka topic `ai.task.train` / `ai.task.qa`；

-   租户隔离：请求头 `X-Tenant-Code` 全链路透传校验。

## AI 质检（千问 / DeepSeek）

质检中心“发起全量质检 / 批量 AI 质检”会调用：

``` code-block-container
POST /api/ai/v1/qa/evaluate
```

配置真实大模型（配好密钥即可，`LLM_DEFAULT_PROVIDER` 不用改）：

``` code-block-container
# 千问
YUNTI_AI_QWEN_API_KEY=你的DashScope密钥
YUNTI_AI_QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
YUNTI_AI_QWEN_MODEL=qwen-plus

# 或 DeepSeek
YUNTI_AI_DEEPSEEK_API_KEY=你的DeepSeek密钥
YUNTI_AI_DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
YUNTI_AI_DEEPSEEK_MODEL=deepseek-chat
```

模型选择顺序：

1.  请求里显式传入的 `provider` / `model`；

2.  租户在「智能机器人 → 模型选择」里配置的模型（按 `ai_db` 的 `bot_setting` + `bot_model` 读取）；

3.  `YUNTI_AI_LLM_DEFAULT_PROVIDER`；

4.  以上都没有时，自动选择已配置密钥的厂商（千问优先）。

密钥可以写在环境变量里，也可以直接放到项目根目录的 `.env`（参考 `.env.example`，该文件已被 git 忽略）。 一个密钥都没配时，服务自动降级为本地规则兜底，不会导致质检流程中断，日志里会明确写明降级原因。

``` code-block-container
## 八、运行与验证
### 8.1 启动
```bash
# 先确认数据库在跑（ai_db / customer_db 都要）
psql -h 127.0.0.1 -U mac -d ai_db -c "select 1"

# 启动 AI 服务（会自动释放 9100 端口、校验依赖）
cd yunti-ai
./start.sh
```

**7.2 后端**

``` code-block-container
cd yunti-backend
mvn -pl yunti-common install -DskipTests
mvn -pl yunti-customer-service spring-boot:run # 9093
```

**7.3 前端**

``` code-block-container
cd yunti-frontend
npm run dev # http://localhost:5174
```

  

  

看到下面这行就说明日志配置生效了：

``` code-block-container
2026-09-11 19:07:44.121 | INFO  | ai.core.llm_gateway    | LLM 网关初始化 provider=mock model=- mode=mock
INFO:     Uvicorn running on http://127.0.0.1:9100
```

### 8.2 直接验证质检接口

不依赖 Java 和前端，先用 curl 打通：

``` code-block-container
curl -s -X POST http://127.0.0.1:9100/api/ai/v1/qa/evaluate \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Code: T202609050000002' \
  -H 'X-Request-Id: demo0001' \
  -d '{
    "tenantCode":"T202609050000002",
    "sessionName":"C-2077 · 赵一诺",
    "agentName":"陈思远",
    "transcript":"客户：我买的东西什么时候能到？\n客服：预计明天下午 3 点前送达，物流单号 SF1234567890。\n客户：好的，谢谢。",
    "ruleNames":["服务承诺规范","情绪安抚"],
    "rules":[
      {"name":"服务承诺规范","content":"承诺必须包含明确时间与可兑现口径"},
      {"name":"情绪安抚","content":"客户表达不满时应先致歉再安抚"}
    ]
  }'
```

配好密钥时会返回模型给的分数：

``` code-block-container
{"code":0,"message":"成功","data":{"aiScore":93,"riskLevel":1,"rules":[],"comment":"物流时效与单号均明确告知，话术规范，服务体验良好。","source":"llm-deepseek"}}
```

没配密钥时返回兜底结果，`source` 是 `fallback-rule`，属于正常现象。

### 8.3 从质检中心验证

1.  浏览器打开管理后台，用企业管理员登录；

2.  进「质检中心」，点「AI 全量质检」生成 3 条任务；

      

<img src="https://article-images.zsxq.com/Fj4uKT2pECzzYqNPMD9PKBH6NhHT" class="tiptap-image" alt="图片.png" />

  

<img src="https://article-images.zsxq.com/FsnpkaYwxMpw12LkrInlPAKSBSQ3" class="tiptap-image" alt="图片.png" />

1.  勾选任务点「批量质检」，任务会重新送检；

2.  打开任务详情，能看到 AI 评分、风险等级、命中规则和评语。

  

<img src="https://article-images.zsxq.com/FsHV4I9ZnUn5J9FhmaRm3b38QgdR" class="tiptap-image" alt="图片.png" />

### 8.4 日志长什么样

Java 侧（customer-service）：

  

<img src="https://article-images.zsxq.com/FvGyoT52nM7XGnKua6FmdHRe85Ku" class="tiptap-image" alt="图片.png" />

Python 侧（yunti-ai）：

  

<img src="https://article-images.zsxq.com/Fr9wJD1jtAvJSChW66ANEtlhhBzl" class="tiptap-image" alt="图片.png" />

同一个 trace 把 Java 和 Python 串起来，排查"这个分是怎么打出来的"很方便。

## 总结

这一篇做完，质检中心的"AI"两个字才算落地：

1.  一套 OpenAI 兼容代码同时支持千问和 DeepSeek，换厂商只改配置；

2.  模型选择有明确的优先级，租户在页面里选的模型能真正生效；

3.  提示词给了明确 schema，请求又带了 JSON 输出模式，字段名偶尔写错也能兜住；

4.  密钥没配、模型超时、网关不支持某参数，都有对应的降级与重试，质检流程不会因为 AI 挂掉而中断；

5.  入参、出参、耗时、token、评分全部有日志，Java 和 Python 用同一个 trace 串起来，排障不用靠猜；

6.  送检内容带上真实对话，AI 才有判分依据——这是"AI 质检"和"随机打分"的分界线。

前端和表结构一行没动。后面要做流式对话、知识库问答、向量检索，都可以沿用同一条链路：HTTP 进 yunti-ai，由 LLM 网关统一出网，Java 只负责业务编排。
