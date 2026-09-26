# schema（运行期副本）

这里保存仓库根目录 `schema/` 中增量脚本的运行期副本。`SchemaGuard` 仅检查
`customer_db`，默认不执行 DDL；`ai_db_bot_brain.sql` 属于独立的 AI 库，
由 `scripts/migrate-ai-db.sh` 单独管理，不得在客户库运行。

- 权威版本在根目录 `schema/`，改脚本请改那边，然后同步到这里。
- 增量脚本设计为可重复执行，仍需先审查目标库与影响范围。
- 启动时只读检查结构；默认不自动执行 DDL。
- 只有确认目标数据库后显式设置 `--yunti.schema.auto-migrate=true` 才会自动补结构。
