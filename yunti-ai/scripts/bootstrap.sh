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
