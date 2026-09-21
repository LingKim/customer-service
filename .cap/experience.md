---
schema: cap-experience/v1
title: 企业开通跨服务文件归属与审核版本并发边界
task-id:
source-commit: 5eefa70565135372d5446cfab61c78dd4a3a1d02
---

## 复用触发与检索线索 / Reuse triggers and retrieval cues
- 触发：当未来迁移企业资料、附件上传、提交审核或雪花 ID 前端契约时。
- 关键词：EnterpriseOnboardingService、EnterpriseLicenseFileService、文件归属、Bearer Token、version_no、雪花 ID 字符串。
- 症状：内部归属接口可传任意 ownerId、浏览器收到超出安全整数范围的 number、并发重提产生相同审核版本。

## 问题与根因 / Problem and cause
- 问题：逐段照搬章节代码会留下跨用户文件探测、前后端 ID 契约不一致和审核版本竞态。
- 根因：调用方参数被当作身份，Java long 直接暴露为 JSON number，`MAX(version_no) + 1` 未与企业级锁绑定。
- 失败做法：不要仅靠内部 URL 命名保护接口，不要只修改 TypeScript 类型掩盖运行时 number，也不要只依赖唯一约束把并发冲突暴露成数据库异常。

## 决策与行动 / Decision and actions
- 决策：当 tenant-service 调用 customer-service 校验附件时，必须转发当前 Bearer Token，并由 customer-service 从 JWT 主体派生 owner，因为 ownerId 不能由调用者声明。
- 行动 1：所有对外雪花 ID DTO 使用 String；JWT 内部身份模型仍保留 long。
- 行动 2：审核提交事务先锁定 enterprise 行，再读取最新审核和计算下一版本号；数据库唯一约束继续作为最终防线。

## 实现锚点与不变量 / Implementation anchors and invariants
- 入口：`EnterpriseOnboardingController.submit`、`EnterpriseLicenseFileController.validateOwnership`、企业引导页。
- 改动点：`yunti-backend/yunti-tenant-service/**`、`yunti-backend/yunti-customer-service/**`、`yunti-backend/yunti-user-service/**`、`yunti-frontend/src/types/index.ts`、`schema/*.sql`。
- 不变量：企业用户只能读取本人或有效租户文件；审核中和已通过资料不可直接修改；对外雪花 ID 不得返回 JSON number；同一企业审核版本单调递增。

## 验证配方 / Verification recipe
- 验证动作：JDK 21 下执行后端聚合 `mvn test`，前端执行 `npm run build`，并执行 `git diff --check`。
- 通过信号：8 个 Maven 模块成功，20 项测试零失败；vue-tsc 与 Vite 成功；Bearer 转发测试请求中没有 ownerId 查询参数。
- 失败信号：内部归属请求仍出现 ownerId、公开 DTO accessor 为 long、并发提交路径未调用加锁查询，或构建出现 number/string 类型错误。

## 适用前提 / Preconditions
- Spring Boot 4、Java 21、PostgreSQL、MyBatis-Plus，JWT 由 user-service、tenant-service 和 customer-service 共用签名密钥。

## 禁用场景 / Do not use when
- 服务间已改为独立服务身份或 mTLS 时，不应直接照搬用户 Bearer Token 转发方案。
- 审核版本改为数据库序列或独立版本服务时，不应继续使用 enterprise 行锁方案。

## 失效信号 / Invalidation signals
- 失效信号：JWT claims、企业状态枚举、文件所有权模型、审核版本生成方式或前端 ID 协议发生变化。
- 复核位置：`docs/design/08 实现完善企业信息功能.md`、三个服务的控制器/服务层、`schema/tenant_db.sql` 和 `yunti-frontend/src/types/index.ts`。

## 证据与结果 / Evidence and outcome
- 证据：commit:5eefa70565135372d5446cfab61c78dd4a3a1d02
- 证据：.cap/verify/summary.md
- 结果：第 08 章迁移完成，后端 20 项测试与前端生产构建通过，严重归属越权、ID 精度和审核版本竞态已收敛。
