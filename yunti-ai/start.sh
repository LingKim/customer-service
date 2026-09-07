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
# Step 1: 检查端口（不停止其他项目的进程）
# ============================================================================
echo -e "${BOLD}[1/4]${NC} 检查端口 $APP_PORT ..."
if ! command -v lsof >/dev/null 2>&1; then
  echo -e "  ${RED}未找到 lsof，无法安全检查端口占用${NC}"
  exit 1
fi
if lsof -nP -iTCP:"$APP_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo -e "  ${RED}端口 $APP_PORT 已被占用；请确认下列进程是否属于 yunti-ai 后再手动停止，或使用 --port 指定其他端口：${NC}"
  lsof -nP -iTCP:"$APP_PORT" -sTCP:LISTEN
  exit 1
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
