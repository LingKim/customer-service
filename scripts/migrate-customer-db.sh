#!/usr/bin/env bash
# 按依赖顺序执行 customer_db 增量脚本。运行前确认目标数据库并提供连接凭据。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="${DB:?请设置 DB 为已确认的目标数据库名}"
PGHOST="${PGHOST:?请设置 PGHOST 为已确认的数据库地址}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:?请设置 PGUSER 为数据库用户}"
PSQL="${PSQL:-$(command -v psql || true)}"
if [[ -z "$PSQL" ]]; then
  echo '找不到 psql，请设置 PSQL=/path/to/psql' >&2
  exit 1
fi
export PGHOST PGPORT PGUSER

SCRIPTS=(
  customer_db_security.sql
  customer_db_collab.sql
  customer_db_delivery.sql
  customer_db_qa.sql
  customer_db_routing.sql
  customer_db_realtime_qa.sql
  customer_db_qa_source.sql
  customer_db_qa_timeout.sql
  customer_db_kb.sql
  customer_db_bot_brain.sql
  customer_db_chat_image.sql
  customer_db_ticket.sql
)

echo "目标库：${PGUSER}@${PGHOST}:${PGPORT}/${DB}"
for script in "${SCRIPTS[@]}"; do
  echo "执行 ${script}"
  "$PSQL" -v ON_ERROR_STOP=1 -d "$DB" -f "${ROOT}/schema/${script}"
done
echo '增量脚本执行完成。'
