# Task Context

- intent: 根据 docs/design/08 实现完善企业信息功能.md 直接迁移第 08 章代码并完成自测
- branch: chapter/08-enterprise-profile
- head: 00f4e4d8c68332bd41f33a510f486358a2027440
- index-fingerprint: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
- worktree-fingerprint: e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
- untracked-fingerprint: 168cb8f8253ad99a842763474d2946bc44ddadd823dbb864ba5fd7e2fa4399ae
- inspected-at: 2026-09-21T12:57:54Z
- profile-used-as: index-only

## Entry points

- `docs/design/08 实现完善企业信息功能.md` — 第 08 章功能、接口和完整代码来源。
- `yunti-backend/yunti-tenant-service/src/main/java/cn/net/susan/tenant/TenantApplication.java` — 企业资料与审核申请服务入口。
- `yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/CustomerApplication.java` — 营业执照文件服务入口。
- `yunti-frontend/src/router/index.ts` — 引导页与企业信息页路由入口。

## Call chain and data flow

- 企业登录 → 前端 Bearer Token → tenant-service JWT 解析 → EnterpriseOnboardingService → tenant_db.enterprise / enterprise_review。
- 前端上传营业执照 → gateway /api/customer/** → customer-service → ObjectStorage → customer_db.file_meta。
- 企业资料提交 → tenant-service 校验申请人及营业执照归属 → 写 enterprise_review 并更新 enterprise 最新资料。
- 前端引导页 → 查询企业阶段 → PENDING_PROFILE / PENDING_REVIEW / REJECTED / APPROVED 状态展示。

## Similar implementations

- `yunti-backend/yunti-user-service/src/main/java/cn/net/susan/user/security/JwtService.java` — JWT 声明和错误契约。
- `yunti-backend/yunti-tenant-service/src/main/java/cn/net/susan/tenant/service/EnterpriseDraftService.java` — 企业草稿、雪花 ID 和事务模式。
- `yunti-frontend/src/api/user.ts` — API 类型、Mock 分流和统一请求模式。

## Tests and environment

- `cd yunti-backend && mvn test` — 后端聚合测试；当前基线无 Java 测试，需为新增核心行为补测试。
- `cd yunti-frontend && npm run build` — TypeScript 与 Vite 构建，不等于浏览器 E2E。
- 数据库 DDL 不自动执行；未经用户明确授权不写数据库。
- 浏览器联调需要 PostgreSQL、四个 Java 服务与关闭前端 Mock；本轮先执行无需数据库写入的自动自测。

## Evidence sources

- `docs/design/08 实现完善企业信息功能.md` — 章节实现原稿。
- `schema/tenant_db.sql` — 当前 tenant_db 基线。
- `yunti-backend/yunti-gateway/src/main/resources/application.yml` — /api/customer/** 路由已经存在。
- `yunti-frontend/.env.development` — 默认 VITE_USE_MOCK=true。

## External operation boundary

- environment: local
- authorization: 已授权创建第 08 章分支并开发；未授权数据库写入、启动联调服务、提交或推送
- minimum-impact: 仅修改第 08 章涉及的 schema、tenant-service、customer-service、frontend 与本地 .cap 研发产物
- recovery: 工作区改动可通过逐文件 diff 审核；不执行 destructive reset/checkout
- invalidates-on: 目标环境变化、需要执行 DDL/写库、commit/push 或管理已有进程

## Impact surface

- modify: `schema/tenant_db.sql`、`schema/customer_db.sql` — 新增 enterprise_review 与 file_meta DDL。
- modify: `yunti-backend/yunti-tenant-service/**` — 企业资料、申请记录、JWT 和接口。
- modify: `yunti-backend/yunti-customer-service/**` — 文件元数据、对象存储、鉴权和上传下载接口。
- modify: `yunti-frontend/src/**`、`yunti-frontend/vite.config.ts` — 企业引导、企业信息、API、路由和代理。
- inspect-only: `yunti-backend/yunti-gateway/**` — customer 路由已存在，不重复修改。
- out-of-scope: `docs/design/09*` 及后续章节 — 企业审核通过和创建租户留到第 09 章。
- out-of-scope: `docs/design/10*` 至 `docs/design/22*`、`AGENTS.md` — 任务开始前已有未跟踪文件，不纳入本章代码范围。

## Profile drift

- none
