# schema（运行期副本）

这里的脚本是仓库根目录 `schema/` 里 customer_db 增量脚本的**副本**，
用途只有一个：customer-service 启动时做结构自检，发现缺表缺列就直接执行对应脚本（见 `SchemaGuard`）。

- 权威版本在根目录 `schema/`，改脚本请改那边，然后同步到这里。
- 全部脚本都是"可重复执行"的，跑多次不会有副作用。
- 启动时只读检查结构；默认不自动执行 DDL。
- 只有确认目标数据库后显式设置 `--yunti.schema.auto-migrate=true` 才会自动补结构。
