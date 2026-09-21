# Project Profile: 云梯智能客服

harness-mode: server

tech-stack: [java-21, spring-boot-4.0.3, spring-cloud-2025.1.2, maven, postgresql, vue-3, typescript, vite, pinia, element-plus, python, fastapi]
test-commands: { unit: "cd yunti-backend && mvn test；cd yunti-ai && ./.venv/bin/python -m pytest", coverage: "none — 未配置覆盖率工具或阈值", e2e: "none — 未配置浏览器或跨服务 E2E 套件", typecheck: "cd yunti-frontend && npm run build（包含 vue-tsc）", build: "cd yunti-backend && mvn clean package -DskipTests；cd yunti-frontend && npm run build" }

## Verification environment

- runtime: JDK 21 + system Maven；Node/npm；Python project venv
- execution-zone: local
- package-registry: public
- credential-refs: [YUNTI_DB_PASSWORD, YUNTI_JWT_SECRET, optional model/provider environment variables]
- network-endpoints: [PostgreSQL 5432, local services 9090-9095, frontend 5173, AI service 9100]
- composable-services: [PostgreSQL；Redis/Kafka only reserved in AI configuration]
- enterprise-services: [none]
- confirmed-gaps: [Java 无业务测试源码, 前端无测试/lint/E2E 脚本, Pytest 未进入 requirements.txt, 无 CI/CD 与部署清单, 数据库迁移无自动执行器]
- authoritative-stage: local-skills

## Tech stack

| 层 | 技术 | 选它的原因 |
|----|------|-----------|
| API 网关 | Spring Cloud Gateway WebFlux | 统一按业务前缀把请求转发到 9091-9095 服务 |
| Java 服务 | Java 21、Spring Boot 4、Maven 多模块 | 共享 common 模块并保持各业务服务可独立启动 |
| 数据访问 | PostgreSQL、MyBatis-Plus、Mapper XML | user_db 与 tenant_db 分库，应用生成业务 ID |
| Web 管理端 | Vue 3、TypeScript、Vite、Pinia、Element Plus | 管理后台、路由守卫、统一请求和状态管理 |
| AI 服务 | Python、FastAPI、Pydantic、Uvicorn | 提供健康检查与 AI 编排 HTTP 边界 |
| 可选 AI 能力 | LangGraph、LlamaIndex、Redis、Kafka、OpenAI SDK | 仅在 requirements-ai.txt 中预留，当前不是已接通能力 |

## Surface map

- backend-common: globs[ yunti-backend/yunti-common/** ] roles[server-dev] checks[logic]
- backend-gateway: globs[ yunti-backend/yunti-gateway/** ] roles[server-dev] checks[logic, journey:OpenAPI]
- backend-user: globs[ yunti-backend/yunti-user-service/** ] roles[server-dev] checks[logic, journey:OpenAPI]
- backend-tenant: globs[ yunti-backend/yunti-tenant-service/** ] roles[server-dev] checks[logic, journey:OpenAPI]
- backend-customer: globs[ yunti-backend/yunti-customer-service/** ] roles[server-dev] checks[logic, journey:OpenAPI]
- backend-support-services: globs[ yunti-backend/yunti-billing-service/**, yunti-backend/yunti-ops-service/** ] roles[server-dev] checks[logic, journey:OpenAPI]
- web-frontend: globs[ yunti-frontend/** ] roles[client-dev, design] checks[logic, journey:Web]
- ai-service: globs[ yunti-ai/** ] roles[server-dev, qa] checks[logic, model, journey:OpenAPI]
- data-schema: globs[ schema/** ] roles[big-data] checks[logic]
- product-design-docs: globs[ docs/design/**, README.md ] roles[qa] checks[logic]
- agent-guidance: globs[ AGENTS.md, .cap/** ] roles[ai-readiness, qa] checks[logic]

## Conventions

- 设计文档是需求输入，当前源码和配置是运行事实；根 README 与后端 README 存在骨架阶段的过期描述。
- 仓库按 chapter/NN-英文简述 分支逐章累积实现；未经授权不跨章混改或改写历史。
- Java 使用四空格、构造器注入、Service 事务、Mapper/XML 持久化、ApiResponse<T> 统一响应和 BizException 领域错误。
- Vue/TypeScript 使用两空格、单引号、无分号、严格类型；API 统一经过 src/api/request.ts。
- Python 使用四空格、类型标注、Pydantic 边界模型和 FastAPI Depends。
- Authorization Bearer Token 与 X-Tenant-Code 是跨前后端契约；前端 Mock 模式不能作为真实联调证据。
- 雪花 ID 对前端以字符串传递，避免超过 JavaScript 安全整数范围。
- 数据库写入、DDL、测试数据准备与清理必须先获用户授权并确认非生产目标。
- 默认不运行 build、测试、服务、浏览器和数据库写入；未经授权不 commit 或 push。

## Entry points

- 后端聚合与模块清单：yunti-backend/pom.xml
- 网关入口：yunti-backend/yunti-gateway/src/main/java/cn/net/susan/gateway/GatewayApplication.java
- 网关路由：yunti-backend/yunti-gateway/src/main/resources/application.yml
- 用户服务入口：yunti-backend/yunti-user-service/src/main/java/cn/net/susan/user/UserApplication.java
- 租户服务入口：yunti-backend/yunti-tenant-service/src/main/java/cn/net/susan/tenant/TenantApplication.java
- 客服服务入口：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/CustomerApplication.java
- 前端入口：yunti-frontend/src/main.ts
- 前端路由：yunti-frontend/src/router/index.ts
- 前端开发代理：yunti-frontend/vite.config.ts
- AI ASGI 入口：yunti-ai/ai/main.py
- AI 命令入口：yunti-ai/ai/__main__.py
- 数据库基线：schema/user_db.sql、schema/tenant_db.sql

## Known risks

- Java 当前没有 src/test 业务测试；mvn test 成功最多证明编译和“无测试失败”，不能证明注册、JWT、跨服务事务或数据库行为。
- 前端无单测、组件测试、浏览器 E2E、lint/format gate；npm run build 不能证明交互和接口联调。
- AI 仅有健康检查测试，且 Pytest 不在基础依赖清单中。
- user-service 与 tenant-service 已连接 PostgreSQL，但 README 仍写“不连接数据库”。
- customer、billing、ops 当前主要是骨架；Redis、Kafka、LangGraph、LlamaIndex 和模型 SDK 只是预留。
- 环境中存在 target、node_modules、dist、.venv、缓存等大体量生成目录，必须依靠模块 .gitignore 排除噪声。
- 无 CI、容器、部署清单、迁移执行器、覆盖率阈值、静态分析或安全扫描。
- JWT 共享密钥、企业资料、营业执照和文件系统操作属于安全敏感面，必须验证鉴权、对象归属、输入内容和跨租户隔离。

## AI-readiness

- health-score: 6/10
- strengths: [根 AGENTS.md 已给出项目边界, Java/TypeScript 类型信息较完整, 三套应用有 scoped build/run 命令, 生成目录均有模块级忽略规则]
- gaps: [无 CLAUDE.md/AGENTS.md 分层级联, 后端与前端自动化测试严重不足, 无 domain-aware check 和 CI, Python 无静态类型/格式工具, README 部分过期, 无统一测试依赖清单]

## Deploy

- target-type: 未知
- config 位置: none — 未检测到 Dockerfile、Compose、Kubernetes、Helm、CI workflow 或部署脚本
- 环境: unknown
- 密钥来源: application.yml 中的环境变量引用；未发现 Secret Manager 配置

## Evolution log

> 演进史见同目录 `EVOLUTION.md`；当前尚未建立已退场特性的演进记录。
