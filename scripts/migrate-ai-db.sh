#!/usr/bin/env bash
# 把 ai_db 的增量脚本按顺序跑一遍（每个脚本都是"可重复执行"的，多跑无副作用）。
#
# ai_db 是机器人自己的库（意图、机器人设置、模型、多轮对话状态），
# 归 yunti-ai 写。客户库（customer_db）的增量脚本在 migrate-customer-db.sh 里。
#
# 用法：
#   bash scripts/migrate-ai-db.sh
#   DB=ai_db PGHOST=... PGUSER=... bash scripts/migrate-ai-db.sh
#
# 什么时候要跑：接口报 column "xxx" does not exist / relation "xxx" does not exist，
# 或者「智能机器人」页面保存接待策略时报错。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="${DB:?请设置 DB 为已确认的 AI 数据库名}"
PGHOST="${PGHOST:?请设置 PGHOST 为已确认的数据库地址}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:?请设置 PGUSER 为数据库用户}"

# 找不到 psql 就试着用 Postgres.app 自带的那份
PSQL="${PSQL:-$(command -v psql || true)}"
if [ -z "$PSQL" ] && [ -x "/Applications/Postgres.app/Contents/Versions/latest/bin/psql" ]; then
  PSQL="/Applications/Postgres.app/Contents/Versions/latest/bin/psql"
fi
if [ -z "$PSQL" ]; then
  echo "找不到 psql，请设置 PSQL=/path/to/psql 后重试" >&2
  exit 1
fi

export PGHOST PGPORT PGUSER

# 顺序按依赖排：先有机器人设置（bot_setting），再补接待策略与对话状态
SCRIPTS=(
  ai_db_bot_brain.sql
)

echo "目标库：${PGUSER}@${PGHOST}:${PGPORT}/${DB}"
for script in "${SCRIPTS[@]}"; do
  printf '  → %-32s' "$script"
  "$PSQL" -q -v ON_ERROR_STOP=1 -d "$DB" -f "${ROOT}/schema/${script}" > /tmp/migrate-ai-$$.log 2>&1 \
    && echo "完成" \
    || { echo "失败"; tail -10 /tmp/migrate-ai-$$.log; rm -f /tmp/migrate-ai-$$.log; exit 1; }
done
rm -f /tmp/migrate-ai-$$.log

echo
echo "全部完成。核对一下关键结构："
"$PSQL" -d "$DB" -c "select tenant_code, reception_enabled, transfer_on_anger, transfer_after_unresolved
                       from bot_setting where is_deleted = false;"
"$PSQL" -d "$DB" -c "select name, escalate, hit_count from bot_intent where is_deleted = false order by id;"
