# cap-flow State: 第 08 章完善企业信息

stage: test
status: passed
updated: 2026-09-21T13:31:48Z
verify-checks: [logic, journey:OpenAPI, journey:Web]
branch: chapter/08-enterprise-profile
branch-purpose: feature/chapter-08-enterprise-profile
base-branch: chapter/07-registration-login
base-commit: 00f4e4d8c68332bd41f33a510f486358a2027440
worktree: /Users/lilin/Desktop/AI系列/customer-service
work-type: feature
source-leaf: (none)
complexity: L4

## External operation authorization
- environment: local
- scope: 创建第 08 章分支并迁移源码、自测；不含写库、commit、push
- granted-by: 用户当前会话明确授权
- invalidates-on: 环境、分支、Commit 或外部写操作范围变化

## Gates passed
- [x] map：PROFILE.md 已按当前仓库建立；surface-map 等待后续维护确认，不阻塞用户明确的直接迁移
- [x] spec：用户明确要求直接按章节完整代码迁移并自测
- [x] git：chapter/08-enterprise-profile 已从第 07 章基线创建
- [x] tests written：user/tenant/customer 共 20 项
- [x] implementation green：第 08 章后端、前端与 schema 已迁移
- [x] self-test passed：JDK 21 Maven 聚合测试、前端构建、diff check

## Active roles
- server-dev
- client-dev
- design
- big-data
- qa
- architect

## Changed-files snapshot
- .cap/PROFILE.md
- .cap/task-context.md
- .cap/spec.md
- .cap/plan.md
- .cap/STATE.md
- .cap/experience.md
- .cap/verify/chapter-08-worktree.md
- .cap/verify/summary.md
- schema/tenant_db.sql
- schema/customer_db.sql
- yunti-backend/yunti-user-service/**
- yunti-backend/yunti-tenant-service/**
- yunti-backend/yunti-customer-service/**
- yunti-frontend/src/**
- yunti-frontend/vite.config.ts

## Commit scope
- include: 第 08 章 schema、tenant-service、customer-service、frontend 代码与必要 .cap 研发产物
- confirm: AGENTS.md 是否随章节提交
- exclude: docs/design/10-22 未跟踪文件、生成物、缓存、日志、密钥和本机配置

## Decisions log
- 2026-09-21 用户要求按 docs 章节中的完整代码直接迁移并自测，不再等待规格逐节批准。
- 2026-09-21 严重越权与跨租户问题不照抄文档，迁移时按当前安全边界修正。
- 2026-09-21 第 09 章企业审核和创建租户不进入本章范围。
- 2026-09-21 内部文件归属接口不再接受 ownerId，以转发 JWT 主体作为唯一归属依据。
- 2026-09-21 登录、注册和 me 的对外雪花 ID 统一为字符串。
- 2026-09-21 审核提交通过 enterprise 行锁串行化版本号计算。

## Next action
-> 推送 chapter/08-enterprise-profile 到 origin；不执行 DDL
