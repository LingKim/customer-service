"""ai_db 启动自检 + 自动补结构（Java 侧 SchemaGuard 的 Python 版）。

为什么要有它：ai_db 也是"增量脚本"演进的。脚本忘了跑，代码一跑到相关查询就抛
`column "xxx" does not exist`，报错藏在调用栈深处，很难一眼看出是"少跑了一个脚本"。
这一篇新增了 `bot_dialogue` 表和几个接待策略字段，正好是最容易漏的那一类。

启动时做两件事：
    1. **自检**：把代码依赖的表 / 列核一遍；
    2. **补结构**：缺什么就执行对应的增量脚本（脚本都是可重复执行的）。

自动补结构默认关闭；审查目标库后才可显式设置 YUNTI_AI_AUTO_MIGRATE=true。
脚本目录默认找 <仓库根>/schema，可用 YUNTI_AI_SCHEMA_DIR 覆盖。
"""

from __future__ import annotations

import logging
import os
import pathlib

from .db import connect

logger = logging.getLogger(__name__)

# 代码依赖的结构 → 来源脚本（脚本名对应 <仓库根>/schema/ 下的文件）
REQUIRED_TABLES = {
    "bot_intent": "ai_db_bot_brain.sql",
    "bot_setting": "ai_db_bot_brain.sql",
    "bot_dialogue": "ai_db_bot_brain.sql",
}

REQUIRED_COLUMNS = {
    "bot_intent.escalate": "ai_db_bot_brain.sql",
    "bot_setting.reception_enabled": "ai_db_bot_brain.sql",
    "bot_setting.transfer_on_anger": "ai_db_bot_brain.sql",
    "bot_setting.transfer_after_unresolved": "ai_db_bot_brain.sql",
    "bot_setting.transfer_keywords": "ai_db_bot_brain.sql",
    # 转接中话术：和"转人工提示"分开的那个字段
    "bot_setting.transfer_message": "ai_db_bot_brain.sql",
}

# 说明：这里每加一个字段/表，都必须在上面登记。
# 漏登记的后果是"代码要读这个列、自检却不知道它该存在" → 没人补结构 →
# 接口报 column "xxx" does not exist，而日志里自检还写着"结构齐全"，特别费解。


def ensure_schema() -> None:
    """启动自检；缺结构就补。任何异常都只记日志——结构问题不该拦住服务启动。"""
    try:
        missing = _missing()
    except Exception as exc:  # noqa: BLE001
        logger.warning("ai_db 结构自检未完成（不影响启动）：%s", exc)
        return
    if not missing:
        logger.info("ai_db 启动自检通过：机器人配置与多轮对话状态表都齐全")
        return

    logger.warning("检测到 ai_db 结构缺失：%s", "、".join(sorted(missing)))
    if not _auto_migrate():
        logger.error("已关闭自动补结构（YUNTI_AI_AUTO_MIGRATE=false），请手动执行："
                     "bash scripts/migrate-ai-db.sh")
        return

    applied: list[str] = []
    for script in sorted(set(missing.values())):
        path = _script_path(script)
        if path is None:
            logger.error("找不到增量脚本 %s，请手动执行：bash scripts/migrate-ai-db.sh", script)
            continue
        try:
            with connect() as conn:
                conn.execute(path.read_text(encoding="utf-8"))
                conn.commit()
            applied.append(script)
        except Exception as exc:  # noqa: BLE001
            logger.error("执行 %s 失败：%s（请手动执行：bash scripts/migrate-ai-db.sh）", script, exc)
            return
    if applied:
        logger.info("已自动补齐 ai_db 结构（执行的脚本：%s）", "、".join(applied))


def _missing() -> dict[str, str]:
    """核对表与列，返回 {缺失项: 来源脚本}。"""
    with connect() as conn:
        tables = {
            row["table_name"]
            for row in conn.execute(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
            ).fetchall()
        }
        columns = {
            f"{row['table_name']}.{row['column_name']}"
            for row in conn.execute(
                "SELECT table_name, column_name FROM information_schema.columns "
                "WHERE table_schema = 'public'"
            ).fetchall()
        }
    missing: dict[str, str] = {}
    for table, script in REQUIRED_TABLES.items():
        if table not in tables:
            missing[f"表 {table}"] = script
    for column, script in REQUIRED_COLUMNS.items():
        if column not in columns:
            missing[f"列 {column}"] = script
    return missing


def _auto_migrate() -> bool:
    return os.environ.get("YUNTI_AI_AUTO_MIGRATE", "false").strip().lower() not in (
        "false", "0", "no", "off")


def _script_path(script: str) -> pathlib.Path | None:
    """按几个常见位置找 schema 目录：显式配置 > 仓库根 > 当前目录。"""
    here = pathlib.Path(__file__).resolve()
    candidates = []
    configured = os.environ.get("YUNTI_AI_SCHEMA_DIR")
    if configured:
        candidates.append(pathlib.Path(configured))
    # ai/core/schema_guard.py → 仓库根是上溯三级（yunti-ai/ai/core → yunti-ai → 仓库根）
    candidates.append(here.parents[3] / "schema")
    candidates.append(here.parents[2] / "schema")
    candidates.append(pathlib.Path.cwd() / "schema")
    for directory in candidates:
        path = directory / script
        if path.is_file():
            return path
    return None


def describe() -> str:
    """给 /health 用的自检结果描述（只读，不触发补结构）。"""
    try:
        missing = _missing()
    except Exception as exc:  # noqa: BLE001
        return f"未完成（{exc}）"
    if not missing:
        return "OK"
    return "缺少：" + "、".join(sorted(missing)) + "（执行 bash scripts/migrate-ai-db.sh）"


__all__ = ["ensure_schema", "describe"]
