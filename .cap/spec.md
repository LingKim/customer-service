# Spec: 第 08 章完善企业信息

> Date: 2026-09-21
> Status: approved
> Target surface(s): backend-tenant, backend-customer, web-frontend, data-schema
> Active roles (anticipated): server-dev, client-dev, design, big-data, qa, architect
> Verify checks (anticipated): logic, journey:OpenAPI, journey:Web

## 1. 问题 / 目标

直接迁移 `docs/design/08 实现完善企业信息功能.md`：企业用户完善资料、上传营业执照、提交审核并查看状态。

## 2. 非目标

- 不实现第 09 章平台审核通过、创建租户和驳回操作。
- 不执行数据库 DDL，不创建演示数据，不提交或推送。
- 不把后续未跟踪设计文档纳入本章。

## 3. 现状摘要

第 07 章已完成注册登录、企业草稿和双库基础。Gateway 已有 customer 路由；customer-service 只有骨架。前端默认 Mock 登录，暂无 Java/前端业务测试。

## 4. 方案与决策

- 以第 08 章完整代码为主体，按当前源码增量迁移，不重复创建已有骨架或网关路由。
- 新增文档遗漏的 `schema/customer_db.sql`。
- 修正文档中的严重安全缺陷：营业执照上传/读取必须绑定当前用户；企业提交必须校验文件归属；企业审核中或已通过时不得绕过审核直接修改主数据。
- 所有新增雪花 ID 对前端以字符串传输。
- 默认本地存储；RustFS 仅在显式启用并提供凭据时使用。

## 5. 设计

tenant-service 管企业资料与审核申请，customer-service 管文件元数据和对象存储；两者通过受保护的内部文件归属校验接口衔接。前端沿用现有深色侧栏、白色内容区与 Element Plus 表单/步骤组件。

## 6. 怎么算 done

- 新增核心后端行为有自动化测试，定向测试和 Maven 聚合测试通过。
- 前端 `npm run build` 通过。
- `git diff --check` 通过。
- 明确记录未执行的 DDL、真实对象存储和浏览器 E2E 边界。

## 7. Eval 契约

N/A — 本章不修改 AI 模型、Prompt 或策略。

## 7b. 设计契约

沿用现有 Element Plus 管理端视觉与第 08 章页面结构；本章不重新设计品牌系统。

## 8. Deferred Ideas

- 第 09 章企业审核与创建租户。Why：完成审核闭环。Trigger：第 08 章迁移与自测完成。Breadcrumbs：`docs/design/09 实现企业审核&创建租户功能.md`。
- 病毒扫描、PDF 内容净化与对象存储服务端加密。Why：生产文件安全。Trigger：进入生产化或真实对象存储验收。Breadcrumbs：customer-service 文件存储层。

## 9. Canonical refs

- `docs/design/08 实现完善企业信息功能.md`
- `docs/design/07 实现注册和登录前后端完整功能（一）.md`
- `docs/design/07 实现注册和登录前后端完整功能（二）.md`
