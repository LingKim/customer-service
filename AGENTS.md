# 仓库开发指南

## 项目定位与交付模式

本仓库按照 `docs/design/` 中的章节顺序逐步实现“云梯智能客服”平台。设计文档是需求输入，当前源码才是运行事实。实现新章节前，必须把对应设计文档与现有接口契约、数据库结构及上一章节代码进行核对，不能默认设计稿已经落入源码。

迁移章节代码时，每完成三章内容集中测试一次，无需每章单独测试；各章节代码严格依赖上一章节，修改前必须仔细核对上一章节的实现及相关契约。

仓库以“一章一个分支”的方式保留里程碑：

- `main` 保存最初的设计基线。
- `chapter/NN-英文简述` 累积实现到对应章节。
- 只在用户明确选定的章节分支继续开发。未经授权，不得切换分支、创建新章节分支、合并旧章节或改写历史。

章节分支在迁移过程中会变化，当前分支和已实现范围以 `git branch --show-current`、`git log` 及源码为准。根目录 README 和部分服务 README 仍保留早期骨架阶段的描述，不能单独作为当前行为的证据。

## 仓库结构

- `docs/design/`：按编号排列的产品与架构章节。后续章节可能只是未来规划，并不代表功能已经实现。
- `schema/`：按数据库拆分的 PostgreSQL DDL。`user_db.sql` 管理用户，`tenant_db.sql` 管理企业审核和租户，`customer_db.sql` 管理文件和渠道，`ai_db.sql` 管理机器人配置。
- `yunti-backend/`：Java 21、Spring Boot 4、Spring Cloud、Maven 多模块后端。
  - `yunti-common`：统一响应、异常、认证、租户上下文、ID 与日志等公共能力。
  - `yunti-gateway`：响应式 API 网关，端口 `9090`。
  - `yunti-user-service`：认证和用户服务，端口 `9091`，使用 `user_db`。
  - `yunti-tenant-service`：企业资料、审核和租户服务，端口 `9092`，使用 `tenant_db`。
  - `yunti-customer-service`：营业执照文件和渠道服务，端口 `9093`，使用 `customer_db`；`yunti-billing-service`、`yunti-ops-service` 仍为骨架，端口 `9094`、`9095`。
- `yunti-frontend/`：Vue 3、TypeScript、Vite、Pinia、Vue Router、Axios、Element Plus 管理端，端口 `5173`。
- `yunti-ai/`：FastAPI AI 编排及机器人配置服务，端口 `9100`。基础依赖包含 PostgreSQL 机器人配置；`requirements-ai.txt` 用于启用可选的 LangGraph、LlamaIndex、Redis、Kafka 和模型集成。

不得编辑 `target/`、`node_modules/`、`dist/`、`.venv/`、`.pytest_cache/`、`__pycache__/`、`.idea/`、日志目录等生成产物或本地目录。

## 事实来源与契约边界

三套应用之间的公共契约必须保持一致：

- 后端接口返回 `ApiResponse<T>`，业务成功码为 `0`。前端请求封装会解包 `data`，并把非零业务码当作失败。
- 已登录的前端请求携带 `Authorization: Bearer <token>`；存在租户编码时，租户相关请求还需携带 `X-Tenant-Code`。
- AI 对话接口强制要求 `X-Tenant-Code`。新增路由、客户端、后台任务或模型调用时，不得绕过租户边界。
- 网关按前缀路由：`/api/user/**`、`/api/tenant/**`、`/api/customer/**`、`/api/billing/**`、`/api/ops/**`。服务路径与前端代理路径必须保持一致。
- 用户服务和租户服务通过配置的内部地址互相调用。修改注册、审核、JWT 声明、租户绑定或账号身份时，通常需要同时核对两个服务和前端类型。
- 用户 ID 和业务编号由应用生成。应保留 MyBatis-Plus 的显式输入 ID 策略和数据库约束，不得擅自改为数据库自增 ID。

前端默认配置为 `VITE_USE_MOCK=true`。页面在 Mock 模式下可用，并不能证明网关、数据库或跨服务联调正常。所有浏览器或 E2E 结果都必须说明当时是否启用了 Mock。

## 后端开发约定

沿用 `cn.net.susan.<service>` 包结构，Controller 保持轻量。业务判断和事务放在 Service，持久化放在 Mapper 接口及 XML，跨服务公共能力放在 `yunti-common`，服务专属契约留在所属模块。

遵循现有 Java 风格：四空格缩进、左大括号不换行、语义清晰的类型和命名、构造器注入；适合不可变结构时使用 DTO record；在请求边界使用 Jakarta Validation；领域错误统一使用 `BizException` 和 `ResultCode`。查询和更新必须保留软删除条件与租户条件。

配置写入 `application.yml`，并提供环境变量覆盖。严禁提交真实密码、Token、模型密钥、租户凭据或生产地址。仓库中的 JWT 密钥和演示开关仅供本地使用：生产环境必须覆盖 `YUNTI_JWT_SECRET`，关闭验证码调试和模拟审核。

后端常用命令，在 `yunti-backend/` 中执行：

```bash
mvn test
mvn clean package -DskipTests
mvn -pl yunti-user-service -am test
java -jar yunti-user-service/target/yunti-user-service-1.0.0-SNAPSHOT.jar
```

文档修改或范围很小的改动默认不运行 Maven 构建；只有用户明确要求验证，或改动涉及必须编译验证的契约时才运行。

## 前端开发约定

新增页面和组件默认使用 Vue 3 Composition API 与 `<script setup lang="ts">`，除非相邻文件已经建立了其他模式。保持 TypeScript 严格模式、两空格缩进、单引号和当前尾逗号风格。

职责划分如下：

- API 传输和接口函数放在 `src/api/`。
- 公共请求/响应类型放在 `src/types/`。
- 会话和用户状态放在 Pinia Store。
- Token 与租户信息持久化放在 `src/utils/auth.ts`。
- 路由和访问控制放在 `src/router/index.ts`。
- 页面组合放在 `src/views/`，公共页面框架放在 `src/layouts/`。

除非协议确实需要独立行为，否则不得绕过 `src/api/request.ts` 新建临时 Axios 实例。修改登录流程时，要保留业务响应解包、错误传播、认证头、租户头和重定向语义。

前端常用命令，在 `yunti-frontend/` 中执行：

```bash
npm install
npm run dev
npm run build
npm run preview
```

当前没有前端测试脚本。`npm run build` 会执行 TypeScript 检查和 Vite 生产构建，但不能证明接口联调或浏览器交互正确。

## AI 服务开发约定

FastAPI 应用创建放在 `ai/main.py`，HTTP 契约放在 `ai/api/`，编排逻辑放在 `ai/agents/`，模型提供方逻辑放在 `ai/core/`，检索逻辑放在 `ai/rag/`，批处理逻辑放在 `ai/services/` 或 `ai/workers/`，环境配置放在 `ai/config.py`。

使用类型标注，在 API 边界使用 Pydantic 模型，通过依赖注入传递请求上下文，并在当前请求链需要时沿用基于 `ContextVar` 的租户上下文。不得让租户状态跨请求泄漏，也不得在缺少明确租户编码时执行租户级检索。

未安装可选 AI 依赖时，基础版本会有意回退到 Mock 对话。不得把 Mock 回复描述为真实的千问、DeepSeek、LangGraph 或 RAG 结果。

AI 服务常用命令，在 `yunti-ai/` 中执行：

```bash
bash scripts/bootstrap.sh
./start.sh
./.venv/bin/python -m pytest
./.venv/bin/python -m pytest tests/test_health.py
```

`requirements.txt` 当前没有声明 Pytest，因此全新环境执行初始化脚本只会安装运行依赖。宣称测试可复现前，要么使用已经安装 Pytest 的环境，要么新增经过审核的开发/测试依赖清单。

`start.sh` 会在 `9100` 端口被占用时拒绝启动，不会擅自停止其他进程。必须保留这一安全行为；可以改用其他端口，或在管理无关进程前征得用户同意。

## 数据库与环境安全

`schema/*.sql` 对应四个独立数据库，不能当作同一个 Schema。Java 实体、Mapper XML、校验规则和 SQL 约束必须同步维护。诊断时可以使用已经提供的凭据执行只读查询；任何插入、更新、删除、DDL、测试数据准备或清理操作，都必须先得到用户明确授权，并确认目标不是生产环境。

修改 SQL 文件或应用编译成功，都不能证明数据库迁移已经完成。交付说明必须分别报告 DDL 是否经过审查、是否实际执行，以及是否在目标数据库核验。

## 测试与验证

根据改动面选择匹配的证据：

- 后端逻辑：运行所属模块及其依赖的定向 Maven 测试。
- 前端类型和构建：运行 `npm run build`。
- AI 接口行为：运行定向 Pytest；当前自动化测试覆盖健康检查和机器人配置入口的租户身份拒绝路径。
- 跨服务改动：启动实际需要的服务，验证网关路由、业务响应码、数据持久化和异常路径。
- 用户可见流程：使用真实浏览器，并记录是否启用了 Mock。
- SQL 改动：获得授权后，在指定数据库同时核验表结构和代表性查询。

`git diff --check`、HTTP 200、构建成功、截图或单个正常样本，都不能单独视为完整验收。必须说明实际执行了什么、哪些通过、使用了什么模式和数据，以及还有哪些范围未验证。默认不运行构建、测试、服务、浏览器自动化或数据库写操作，除非用户明确要求验证。

## 修改范围与 Git 纪律

保留工作区中与当前任务无关的改动。编辑前先检查 `git status`；如果已有改动与本次目标路径重叠，必须先与用户确认归属。严禁使用 `git add -A`、破坏性的 reset/checkout、自动 stash 或大范围格式化。

未经用户明确要求，不得 commit 或 push。需要提交时，章节改动应保持聚焦，并沿用历史中的英文祈使句提交标题，例如 `Add tenant approval flow`。Pull Request 需要说明章节、受影响的应用和数据库、实际运行的验证命令、Mock 或真实联调模式、配置假设及未验证边界。
