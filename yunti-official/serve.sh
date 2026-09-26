#!/usr/bin/env bash
set -euo pipefail

SITE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if command -v python3 >/dev/null 2>&1; then
  exec python3 "$SITE_DIR/scripts/serve.py" "$@"
fi
echo "需要 Python 3 才能启动官网预览。" >&2
exit 1
