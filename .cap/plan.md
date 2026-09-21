# Plan: 第 08 章完善企业信息

## Wave 1 — 后端与数据（可并行，写集不重叠）

### Phase A — tenant-service
- files: `schema/tenant_db.sql`, `yunti-backend/yunti-tenant-service/**`
- 先补企业状态、重复提交、资料修改限制的失败测试，再迁移企业资料与审核申请代码。

### Phase B — customer-service
- files: `schema/customer_db.sql`, `yunti-backend/yunti-customer-service/**`
- 先补文件类型、归属隔离、路径安全和配置行为测试，再迁移文件元数据与对象存储代码。

### Phase C — frontend
- files: `yunti-frontend/vite.config.ts`, `yunti-frontend/src/**`
- 迁移企业 API、引导页、企业信息页、退出确认组件和路由；保持雪花 ID 字符串契约。

## Wave 2 — 集成收敛

- 对齐 tenant/customer 内部文件归属校验契约。
- 复核 Gateway 现有路由，不产生重复改动。
- 检查 Mock 模式与真实接口的边界说明。

## Wave 3 — 自测

- 运行新增后端定向测试与 `cd yunti-backend && mvn test`。
- 运行 `cd yunti-frontend && npm run build`。
- 运行 `git diff --check`，审核改动清单与未验证边界。
