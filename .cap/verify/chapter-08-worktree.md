# 第 08 章本地工作树验证

- verified-at: 2026-09-21T13:31:48Z
- branch: chapter/08-enterprise-profile
- base-commit: 00f4e4d8c68332bd41f33a510f486358a2027440
- scope: 未提交工作树，仅本地验证，不代表 Server Gate

## 自动测试

- 命令：`JAVA_HOME=/Users/lilin/.sdkman/candidates/java/21.0.8-amzn PATH=/Users/lilin/.sdkman/candidates/java/21.0.8-amzn/bin:$PATH mvn test`
- 目录：`yunti-backend/`
- 结果：BUILD SUCCESS，8 个 Reactor 模块成功。
- 测试：20 项通过，0 失败，0 错误，0 跳过。
  - user-service：1 项雪花 ID 对外字符串契约。
  - tenant-service：5 项状态机、文件归属 Bearer 转发与重提行为。
  - customer-service：14 项文件类型、归属隔离、本地路径安全、RustFS 行为与补偿逻辑。

## 前端构建

- 命令：`npm run build`
- 目录：`yunti-frontend/`
- 结果：`vue-tsc -b && vite build` 成功，1699 个模块转换完成。
- 警告：主 chunk 大于 500 kB；npm 用户配置项即将弃用。两者均未导致构建失败。

## 静态检查

- `git diff --check`：通过。
- 改动范围复核：第 08 章 schema、user/tenant/customer 服务、前端及 `.cap`；未修改用户原有 `docs/design/10-22` 与 `AGENTS.md`。

## 未验证边界

- 数据库 DDL 仅生成，未执行。
- 未连接真实 PostgreSQL 或 RustFS，未做跨服务联调。
- 未启动服务，未做浏览器 E2E。
- 未 commit、未 push，因此尚不能生成绑定最终 Commit 的 `.cap/experience.md`。
