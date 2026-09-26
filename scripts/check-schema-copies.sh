#!/usr/bin/env bash
# 核对"增量脚本的运行期副本"是否和根目录schema/一致。
#
# 为什么要有这个检查：
#   根目录 schema/ 是权威版本，customer-service 启动时执行的却是
#   yunti-customer-service/src/main/resources/schema/ 下的副本。
#   改脚本时只改根目录、忘了同步副本，启动自检会发现"少一列"，
#   却因为找不到脚本补不上，最后在业务查询里炸成 column "xxx" does not exist，
#   报错点离真正原因隔着好几层调用栈。
#
# 用法：
#   bash scripts/check-schema-copies.sh        # 不一致就退出码 1
#   bash scripts/sync-schema-copies.sh         # 需要同步时（见下）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="${ROOT}/yunti-backend/yunti-customer-service/src/main/resources/schema"

fail=0
for src in "${ROOT}"/schema/customer_db_*.sql; do
  name="$(basename "${src}")"
  target="${DEST}/${name}"
  if [ ! -f "${target}" ]; then
    echo "  ✗ 运行期缺副本：${name}"
    fail=1
  elif ! cmp -s "${src}" "${target}"; then
    echo "  ✗ 副本与权威版本不一致：${name}"
    fail=1
  else
    echo "  ✓ ${name}"
  fi
done

if [ "${fail}" -ne 0 ]; then
  echo
  echo "请同步（根目录 schema/ 是权威版本）："
  echo "  cp schema/customer_db_*.sql ${DEST}/"
  exit 1
fi
echo
echo "全部一致：运行期增量脚本副本与 schema/ 权威版本同步。"
