---
title: "03 SaaS智能客服系统数据库表设计"
source: "https://articles.zsxq.com/id_p6z9w32u7cvd.html"
author:
  - "[[苏三]]"
published:
created: 2026-09-06
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

## 1\. 设计规范（依据阿里 Java 开发手册）

### 1.1 命名规范

| 对象 | 规范 | 示例 |
| --- | --- | --- |
| 表名 | 小写字母 + 下划线，不用复数，不数字开头，不超过 30 字符 | `session_message` 、 `kb_document` |
| 字段名 | 小写字母 + 下划线，语义清晰 | `tenant_code` 、 `session_no` |
| 主键 | 统一 `id` ，BIGINT，雪花算法生成，不自动增长（PG 无显示宽度概念） | `id` （19 位雪花 ID） |
| 布尔字段 | 以 `is_` 开头，BOOLEAN | `is_deleted` 、 `is_enabled` |
| 枚举字段 | 有限且稳定的枚举用 `SMALLINT` 码值存储（1、2、3…），配合 `CHECK` 约束与枚举字典；标识符/可扩展字段用 VARCHAR | `status SMALLINT NOT NULL DEFAULT 1 CHECK (status IN (1,2,3))` 、 `provider VARCHAR(32)` |
| 租户编码 | 定长 `VARCHAR(16)` + `CHECK (char_length(tenant_code)=16)` 强制；格式 `T` + 8 位日期 + 4 位序号（如 `T202608280001` ），由租户服务统一生成，禁止手动编造。 **注意：PostgreSQL 不建议用 CHAR(n)，bpchar 会补尾随空格、ORM 易踩坑** | `tenant_code VARCHAR(16) NOT NULL` |
| 唯一索引 | `uk_字段名` | `uk_tenant_session_no` |
| 普通索引 | `idx_字段名` | `idx_tenant_create_time` |
| 金额 | NUMERIC(12,2)，禁止 float/double（PG 中 DECIMAL 是 NUMERIC 别名，统一用 NUMERIC） | `amount_numeric` |
| 时间 | TIMESTAMP | `create_time` |
| 字符集 | UTF8（PostgreSQL 默认，无需显式指定） | 建库时默认 |
| 引擎 | 无（PostgreSQL 无存储引擎概念，默认堆表） | — |

> **主键生成策略（雪花算法）** ：主键统一为 `BIGINT` ，由 **应用层雪花算法** 生成（64 位 = 时间戳 + 机房/机器标识 + 序列号），不依赖数据库自增。原因：① 微服务多实例部署无法依赖单库自增；② 分库分表后仍需全局唯一；③ 雪花 ID 整体递增，利于索引和归档。说明：PostgreSQL 不支持 MySQL 的 `BIGINT(20)` 显示宽度写法， `BIGINT` 即 8 字节，完全容纳 19 位雪花 ID；禁止在数据库层回退自增，避免跨实例冲突。

### 1.2 强制约束（阿里规约）

1. **禁止外键** ：所有外键概念在应用层实现，数据库不建外键；
2. **逻辑删除** ：统一 `is_deleted` 字段，不物理删除（审计数据除外）；
3. **禁止存储过程/触发器** （业务逻辑不落库）；
4. **必备公共字段** ： `id` 、 `create_time` 、 `update_time` 、 `creator` 、 `editor` 、 `is_deleted` ；
5. **字段必须有注释** ；
6. \*\*禁止 \*\* `SELECT *` ，按需取列；
7. **text 内容不建索引** ：PostgreSQL 无 MySQL 的前缀索引概念；TEXT 全文检索走 OpenSearch，需要精确检索的短文本用 VARCHAR 并建索引；
8. **多租户表必须带** `tenant_code` **并建复合索引** （对应多租户隔离方案）；
9. **枚举字段类型规范** ：状态/类型/结果等 **有限且稳定的枚举一律用** `SMALLINT` **码值存储** （1、2、3…，范围足够），并通过 `CHECK` 约束保证数据库层合法值； **禁止用裸** `VARCHAR` **存枚举，不使用** `ENUM` **类型，禁止用** `BOOLEAN` **存枚举** ；标识符/可扩展字段（供应商、区块标识、套餐编码、会话来源等）保留 `VARCHAR` 并在注释中说明；
10. **枚举值一律用英文大写常量** ，注释只写中文含义，完整对照见附录 16「字段枚举字典」；禁止用中文作为存储值。

> **为什么用 SMALLINT 码值而不用 ENUM/VARCHAR** ： `ENUM` 类型扩展枚举必须改表结构、排序按定义顺序、ORM 映射容易踩坑，维护成本高；裸 `VARCHAR` 无任何数据库约束。 `SMALLINT` 码值存储小、索引小、比较快，扩展枚举只需新增码值并登记字典，不改表结构；合法性由 `CHECK` 约束兜底，可读性由注释 + 附录字典保证。

### 1.3 公共字段模板

```sql
"id"          BIGINT      NOT NULL,                             -- 主键ID（雪花算法生成，应用层分配）
"tenant_code" VARCHAR(16) NOT NULL,                             -- 租户编码（全局隔离键，平台账号为 PLATFORM）
"create_time" TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,   -- 创建时间
"update_time" TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,   -- 更新时间（应用层维护）
"creator"     VARCHAR(64) DEFAULT NULL,                         -- 创建人
"editor"      VARCHAR(64) DEFAULT NULL,                         -- 更新人
"is_deleted"  BOOLEAN     NOT NULL DEFAULT FALSE,               -- 逻辑删除
PRIMARY KEY ("id")
```

> 除全局表（权限、审计等）外，业务表均带 `tenant_code` 并按 `(tenant_code, 业务字段)` 建复合索引；字段注释通过 `COMMENT ON COLUMN` 语句维护（见各表 DDL）。

> **公共字段裁剪说明** ：追加型/流水表（审计、登录记录、事件、流水、质检复核、AI 计量、通知）与纯关联表（角色权限、用户角色、客户标签）只追加、不修改，可省略 `update_time / creator / editor / is_deleted` ；其余业务表按模板全量保留。

### 1.4 设计总则（一线大厂标准）

| 原则 | 落地要求 |
| --- | --- |
| 规范性 | 命名、类型、索引、注释全部遵循统一规约，机器可检查（CI 插件扫描） |
| 隔离性 | 业务表强制 `tenant_code` + RLS，落实多租户隔离方案（见 [多租户数据隔离方案](http://./multi-tenant-isolation.md) ） |
| 可扩展性 | 状态字段用语义化枚举，预留扩展位；核心表预留 `ext_json` 扩展字段 |
| 可追溯性 | 必备 `create_time / update_time / creator / editor` ；审计表只追加 |
| 一致性 | 金额 NUMERIC、时间 TIMESTAMP、布尔 `is_` ，禁止隐式类型转换 |
| 可维护性 | 禁止外键/触发器/存储过程，逻辑删除，变更走迁移脚本 |

---

## 2\. 表清单总览

| 服务 | 库 | 表 | 用途 |
| --- | --- | --- | --- |
| user-service 用户权限 | user\_db | sys\_user / tenant\_member / sys\_role / sys\_permission / sys\_role\_permission / sys\_user\_role / login\_log / member\_invite | 认证、账号、双轨权限、登录记录、个人中心、成员邀请 |
| tenant-service 租户 | tenant\_db | tenant /   enterprise / enterprise\_review | 企业主数据、租户生命周期、企业审核（提交/审核/驳回/重提）、套餐状态 |
| customer-service 客服核心 | customer\_db | channel / channel\_key / skill\_group / channel\_test\_log / session / session\_message / session\_event / customer / customer\_tag / customer\_order / ticket / ticket\_event / quick\_reply / skill\_group\_member / csat\_record / tenant\_config / notification / notification\_read / kb\_category / kb\_document / kb\_version / kb\_audit / qa\_task / qa\_rule / qa\_review / file\_meta | 渠道、会话、客户、订单、工单、快捷回复、技能组坐席、满意度、租户配置、知识库、质检任务、消息中心、文件 |
| billing-service 计费 | billing\_db | plan / billing\_order / payment\_record / invoice\_record / payment\_config | 套餐、订单、支付、发票、支付配置 |
| ops-service 运营 | ops\_db | site\_config / announcement / audit\_log / api\_key / data\_export / customer\_success / register\_config | 官网配置、公告、审计、开放API密钥、导出审批、客户成功、注册策略 |
| ai-center（Python） | ai\_db | bot\_intent / bot\_model / bot\_version / training\_job / ai\_usage\_record | 机器人配置、训练、AI 计量（另含向量库） |

> gateway-service 不拥有业务库，只做请求路由与实时连接。

### 2.1 数据库按微服务划分（Database Per Service）

**原则** ：

1. **一服务一库** ：每个服务独占自己的数据库（schema），禁止其他服务直连访问；
2. **只走 API / 事件** ：服务间数据共享通过接口或 Kafka 事件，禁止跨库 join、不建外键（延续本规范）；
3. **跨库事务用 Saga/事件补偿** ：如「下单 → 支付 → 开通租户」分步完成，失败回滚补偿；
4. **必要字段快照** ：引用他服务数据时冗余快照字段（如坐席姓名、套餐名），本地查询不依赖远程；
5. **多租户隔离不变** ：每个业务库的表仍带 `tenant_code` + RLS（见 [多租户数据隔离方案](http://./multi-tenant-isolation.md) ）；
6. **容量分离** ：customer\_db 数据量最大（会话/消息），可独立扩缩并采用 PostgreSQL 原生 **按月分区** （PARTITION BY RANGE）；其他库体量小、独立演进。

**跨库协作清单** ：

| 业务 | 涉及库 | 协作方式 |
| --- | --- | --- |
| 租户开通 | tenant\_db ↔ billing\_db | billing 下单支付 → 事件通知 → tenant 更新套餐/到期时间 |
| 会话引用坐席/客户 | customer\_db ↔ user\_db | customer 冗余坐席姓名快照，user 变更发事件同步 |
| 质检计算 | customer\_db ↔ ai-center | customer 发任务 → ai-center 计算 → 回调写回质检结果 |
| AI 监控 | ai\_db ↔ ops\_db | ai-center 写计量表，ops 通过 API/事件聚合展示 |
| 套餐引用 | tenant\_db ↔ billing\_db | tenant.plan\_id 仅存引用，套餐详情走 billing API |

### 2.2 产品原型/架构模块 → 数据表映射矩阵

| 原型页面（模块） | 架构服务 | 核心数据表 |
| --- | --- | --- |
| 登录 / 注册引导 / 个人中心 | user-service | sys\_user / tenant\_member / sys\_role / sys\_permission / sys\_role\_permission / sys\_user\_role / login\_log |
| 系统设置（成员邀请） | user-service | member\_invite |
| 企业审核 / 租户管理 / 版本升级 | tenant-service | enterprise / tenant / enterprise\_review（套餐详情走 billing API） |
| 渠道接入 / 渠道演示 | customer-service | channel / channel\_key / skill\_group / channel\_test\_log |
| 在线客服工作台 / 数据概览 | customer-service | session / session\_message / session\_event / customer / customer\_tag / quick\_reply / customer\_order / csat\_record |
| 系统设置（路由与技能组） | customer-service | skill\_group\_member |
| 系统设置（通知 / 自动化工作流 / 安全） | customer-service | tenant\_config |
| 智能机器人 / 模型训练中心 | ai-center | bot\_intent / bot\_model / bot\_version / training\_job / ai\_usage\_record |
| 知识库 | customer-service | kb\_category / kb\_document / kb\_version / kb\_audit |
| 工单管理 / 支持工单 | customer-service | ticket / ticket\_event |
| 质检中心 | customer-service | qa\_task / qa\_rule / qa\_review |
| 消息中心 | customer-service | notification / notification\_read |
| 我的订单 / 计费账单 / 套餐 | billing-service | plan / billing\_order / payment\_record / invoice\_record |
| 平台设置（支付配置） | billing-service | payment\_config |
| 官网配置 / 公告 / 审计日志 / 安全中心 | ops-service | site\_config / announcement / audit\_log |
| 安全中心（API 密钥 / 导出审批） | ops-service | api\_key / data\_export |
| 客户成功 | ops-service | customer\_success |
| 平台设置（注册策略） | ops-service | register\_config |
| AI 监控 | ops-service（页面）+ ai-center（数据） | ai\_usage\_record（ai\_db，ops 通过 API 读取） |
| 文件（头像/附件/导出） | customer-service | file\_meta |

### 2.3 多租户隔离落地说明

本设计的隔离落地与 [多租户数据隔离方案](http://./multi-tenant-isolation.md) 严格对齐：

1. **tenant\_code 全覆盖** ：除纯全局表（sys\_permission、plan、announcement、audit\_log 等）外，所有业务表均带 `tenant_code` ；
2. **RLS 兜底** ：PostgreSQL 上所有租户表开启行级安全策略（建表后统一迁移脚本执行）；
3. **复合索引** ：所有租户表默认 `(tenant_code, 业务字段)` 索引，禁止无租户前缀的裸索引；
4. **公共字段** ： `id / tenant_code / create_time / update_time / creator / editor / is_deleted` 为模板，全表统一；
5. **权限关联表** ：角色权限表按 `tenant_code` （平台角色为 PLATFORM）隔离平台与企业两套体系。

---

## 3\. 账号与租户域（所属库：user\_db + tenant\_db）

### 3.1 enterprise 企业表（所属库：tenant\_db）

> 企业主数据（工商资料），低频稳定；一个企业可对应多个租户（多品牌/多环境）。审核状态在此维护，租户只存产品运行时信息。

```sql
CREATE TABLE "enterprise" (
  "id" BIGINT NOT NULL,
  "enterprise_code" VARCHAR(16) NOT NULL,
  "tenant_code" VARCHAR(16) DEFAULT NULL,
  "company_name" VARCHAR(128) NOT NULL,
  "industry" VARCHAR(32) DEFAULT NULL,
  "scale" VARCHAR(32) DEFAULT NULL,
  "license_no" VARCHAR(64) DEFAULT NULL,
  "register_address" VARCHAR(255) DEFAULT NULL,
  "legal_person" VARCHAR(64) DEFAULT NULL,
  "license_file_id" BIGINT DEFAULT NULL,
  "contact_name" VARCHAR(64) DEFAULT NULL,
  "contact_phone" VARCHAR(20) DEFAULT NULL,
  "contact_email" VARCHAR(128) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_enterprise_status CHECK ("status" IN (1,2,3,4)),
  CONSTRAINT ck_enterprise_code_len CHECK (char_length("enterprise_code") = 16),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_enterprise_code" UNIQUE ("enterprise_code")
);
COMMENT ON TABLE "enterprise" IS '企业表（企业主数据）';
COMMENT ON COLUMN "enterprise"."enterprise_code" IS '企业编码（对外，定长 16，如 E202608280001）';
COMMENT ON COLUMN "enterprise"."tenant_code" IS '租户编码（审核通过创建租户后回填，可空；未开通前企业数据仅申请人可见）';
COMMENT ON COLUMN "enterprise"."company_name" IS '企业名称';
COMMENT ON COLUMN "enterprise"."industry" IS '所属行业';
COMMENT ON COLUMN "enterprise"."scale" IS '团队规模';
COMMENT ON COLUMN "enterprise"."license_no" IS '营业执照号';
COMMENT ON COLUMN "enterprise"."register_address" IS '注册地址';
COMMENT ON COLUMN "enterprise"."legal_person" IS '法人代表';
COMMENT ON COLUMN "enterprise"."license_file_id" IS '营业执照附件文件ID（file_meta）';
COMMENT ON COLUMN "enterprise"."contact_name" IS '管理员姓名';
COMMENT ON COLUMN "enterprise"."contact_phone" IS '联系电话（脱敏存储）';
COMMENT ON COLUMN "enterprise"."contact_email" IS '企业邮箱';
COMMENT ON COLUMN "enterprise"."status" IS '状态码：1-待审核、2-正常、3-已驳回、4-已注销';
CREATE INDEX "idx_enterprise_status_create_time" ON "enterprise" ("status", "create_time");
```

### 3.2 tenant 租户表（所属库：tenant\_db）

> 产品租户实例：只存运行时信息（套餐、状态、到期、隔离模式），企业资料通过 enterprise\_id 关联企业主数据；一个企业可有多个租户。

```sql
CREATE TABLE "tenant" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "enterprise_id" BIGINT NOT NULL,
  "plan_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "expire_time" TIMESTAMP DEFAULT NULL,
  "isolation_mode" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_status CHECK ("status" IN (1,2,3,4)),
  CONSTRAINT ck_tenant_isolation_mode CHECK ("isolation_mode" IN (1,2,3)),
  CONSTRAINT ck_tenant_code_len CHECK (char_length("tenant_code") = 16),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_code" UNIQUE ("tenant_code")
);
COMMENT ON TABLE "tenant" IS '租户表';
COMMENT ON COLUMN "tenant"."tenant_code" IS '租户编码（对外编码，定长 16，如 T202608280001）';
COMMENT ON COLUMN "tenant"."enterprise_id" IS '关联企业ID（enterprise 表）';
COMMENT ON COLUMN "tenant"."plan_id" IS '当前套餐ID';
COMMENT ON COLUMN "tenant"."status" IS '状态码：1-正常、2-冻结、3-到期、4-注销';
COMMENT ON COLUMN "tenant"."expire_time" IS '套餐到期时间';
COMMENT ON COLUMN "tenant"."isolation_mode" IS '隔离模式码：1-共享库、2-独立Schema、3-独立实例';
CREATE INDEX "idx_tenant_enterprise" ON "tenant" ("enterprise_id");
CREATE INDEX "idx_tenant_status_create_time" ON "tenant" ("status", "create_time");
```

### 3.3 enterprise\_review 企业审核表（所属库：tenant\_db）

> 支撑「注册 → 提交资料 → 审核通过/驳回 → 重新提交」完整流程；一个租户可有多条申请记录（version\_no 递增），审核通过后同步更新 tenant.status。

```sql
CREATE TABLE "enterprise_review" (
  "id" BIGINT NOT NULL,
  "enterprise_id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) DEFAULT NULL,
  "apply_no" VARCHAR(32) NOT NULL,
  "version_no" SMALLINT NOT NULL DEFAULT 1,
  "applicant_id" BIGINT NOT NULL,
  "company_name" VARCHAR(128) NOT NULL,
  "industry" VARCHAR(32) DEFAULT NULL,
  "scale" VARCHAR(32) DEFAULT NULL,
  "contact_name" VARCHAR(64) NOT NULL,
  "contact_phone" VARCHAR(20) DEFAULT NULL,
  "contact_email" VARCHAR(128) DEFAULT NULL,
  "license_no" VARCHAR(64) DEFAULT NULL,
  "register_address" VARCHAR(255) DEFAULT NULL,
  "legal_person" VARCHAR(64) DEFAULT NULL,
  "license_file_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "reviewer_id" BIGINT DEFAULT NULL,
  "review_time" TIMESTAMP DEFAULT NULL,
  "reject_reason" VARCHAR(512) DEFAULT NULL,
  "submit_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_enterprise_review_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_enterprise_review_enterprise_version" UNIQUE ("enterprise_id", "version_no")
);
COMMENT ON TABLE "enterprise_review" IS '企业审核表';
COMMENT ON COLUMN "enterprise_review"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "enterprise_review"."enterprise_id" IS '关联企业ID（enterprise 表，申请主体）';
COMMENT ON COLUMN "enterprise_review"."tenant_code" IS '租户编码（审核通过创建租户后回填，可空）';
COMMENT ON COLUMN "enterprise_review"."apply_no" IS '申请编号';
COMMENT ON COLUMN "enterprise_review"."version_no" IS '申请版本（驳回重提递增）';
COMMENT ON COLUMN "enterprise_review"."applicant_id" IS '申请人用户ID';
COMMENT ON COLUMN "enterprise_review"."company_name" IS '企业名称';
COMMENT ON COLUMN "enterprise_review"."industry" IS '所属行业';
COMMENT ON COLUMN "enterprise_review"."scale" IS '团队规模';
COMMENT ON COLUMN "enterprise_review"."contact_name" IS '管理员姓名';
COMMENT ON COLUMN "enterprise_review"."contact_phone" IS '联系电话（脱敏存储）';
COMMENT ON COLUMN "enterprise_review"."contact_email" IS '企业邮箱';
COMMENT ON COLUMN "enterprise_review"."license_no" IS '营业执照号';
COMMENT ON COLUMN "enterprise_review"."register_address" IS '注册地址';
COMMENT ON COLUMN "enterprise_review"."legal_person" IS '法人代表';
COMMENT ON COLUMN "enterprise_review"."license_file_id" IS '营业执照附件文件ID（file_meta）';
COMMENT ON COLUMN "enterprise_review"."status" IS '状态码：1-待审核、2-已通过、3-已驳回';
COMMENT ON COLUMN "enterprise_review"."reviewer_id" IS '审核人用户ID';
COMMENT ON COLUMN "enterprise_review"."review_time" IS '审核时间';
COMMENT ON COLUMN "enterprise_review"."reject_reason" IS '驳回原因';
COMMENT ON COLUMN "enterprise_review"."submit_time" IS '提交时间';
CREATE INDEX "idx_enterprise_review_tenant_status" ON "enterprise_review" ("tenant_code", "status");
```

### 3.4 sys\_user 用户表（平台 + 企业统一账号）

```sql
CREATE TABLE "sys_user" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16)  NOT NULL,
  "user_no" VARCHAR(32)  NOT NULL,
  "name" VARCHAR(64)  NOT NULL,
  "phone" VARCHAR(20)  DEFAULT NULL,
  "email" VARCHAR(128) DEFAULT NULL,
  "password" VARCHAR(128) DEFAULT NULL,
  "avatar" VARCHAR(512) DEFAULT NULL,
  "user_type" SMALLINT  NOT NULL,
  "status" SMALLINT  NOT NULL DEFAULT 1,
  "last_login_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64)  DEFAULT NULL,
  "editor" VARCHAR(64)  DEFAULT NULL,
  "is_deleted" BOOLEAN   NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_user_user_type CHECK ("user_type" IN (1,2)),
  CONSTRAINT ck_sys_user_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_user_no" UNIQUE ("user_no")
);
COMMENT ON TABLE "sys_user" IS '用户表';
COMMENT ON COLUMN "sys_user"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_user"."tenant_code" IS '主归属租户，平台账号为 PLATFORM';
COMMENT ON COLUMN "sys_user"."user_no" IS '用户编号（对外）';
COMMENT ON COLUMN "sys_user"."name" IS '姓名';
COMMENT ON COLUMN "sys_user"."phone" IS '手机号（脱敏存储）';
COMMENT ON COLUMN "sys_user"."email" IS '邮箱';
COMMENT ON COLUMN "sys_user"."password" IS '密码哈希（BCrypt），SSO 账号可为空';
COMMENT ON COLUMN "sys_user"."avatar" IS '头像URL';
COMMENT ON COLUMN "sys_user"."user_type" IS '类型码：1-平台账号、2-企业账号';
COMMENT ON COLUMN "sys_user"."status" IS '状态码：1-正常、2-停用、3-锁定';
COMMENT ON COLUMN "sys_user"."last_login_time" IS '最近登录时间';
CREATE INDEX "idx_sys_user_tenant_status" ON "sys_user" ("tenant_code", "status");
```

### 3.5 tenant\_member 企业成员关系表

```sql
CREATE TABLE "tenant_member" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "join_time" TIMESTAMP DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN  NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_member_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_user" UNIQUE ("tenant_code", "user_id")
);
COMMENT ON TABLE "tenant_member" IS '企业成员关系表';
COMMENT ON COLUMN "tenant_member"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "tenant_member"."tenant_code" IS '租户编码';
COMMENT ON COLUMN "tenant_member"."user_id" IS '用户ID';
COMMENT ON COLUMN "tenant_member"."join_time" IS '加入时间';
COMMENT ON COLUMN "tenant_member"."status" IS '状态码：1-在职、2-已退出';
CREATE INDEX "idx_tenant_member_user_id" ON "tenant_member" ("user_id");
```

### 3.6 角色权限四表（双轨 RBAC）

```sql
CREATE TABLE "sys_role" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "role_code" VARCHAR(64) NOT NULL,
  "role_name" VARCHAR(64) NOT NULL,
  "role_type" SMALLINT NOT NULL,
  "is_fixed" BOOLEAN  NOT NULL DEFAULT FALSE,
  "is_enabled" BOOLEAN  NOT NULL DEFAULT TRUE,
  "remark" VARCHAR(255) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN  NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_role_role_type CHECK ("role_type" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_role_code" UNIQUE ("tenant_code", "role_code")
);
COMMENT ON TABLE "sys_role" IS '角色表';
COMMENT ON COLUMN "sys_role"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_role"."tenant_code" IS '租户编码（平台角色为 PLATFORM）';
COMMENT ON COLUMN "sys_role"."role_code" IS '角色编码';
COMMENT ON COLUMN "sys_role"."role_name" IS '角色名称';
COMMENT ON COLUMN "sys_role"."role_type" IS '类型码：1-平台角色、2-企业角色';
COMMENT ON COLUMN "sys_role"."is_fixed" IS '是否内置固定角色';
COMMENT ON COLUMN "sys_role"."is_enabled" IS '是否启用';
COMMENT ON COLUMN "sys_role"."remark" IS '备注';

CREATE TABLE "sys_permission" (
  "id" BIGINT NOT NULL,
  "perm_code" VARCHAR(64)  NOT NULL,
  "perm_name" VARCHAR(64)  NOT NULL,
  "perm_type" SMALLINT  NOT NULL,
  "parent_id" BIGINT DEFAULT 0,
  "sort_no" INT DEFAULT 0,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_permission_perm_type CHECK ("perm_type" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_perm_code" UNIQUE ("perm_code")
);
COMMENT ON TABLE "sys_permission" IS '权限点/菜单表';
COMMENT ON COLUMN "sys_permission"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_permission"."perm_code" IS '权限编码（对应菜单/功能）';
COMMENT ON COLUMN "sys_permission"."perm_name" IS '权限名称';
COMMENT ON COLUMN "sys_permission"."perm_type" IS '类型码：1-菜单、2-按钮、3-接口';
COMMENT ON COLUMN "sys_permission"."parent_id" IS '父权限ID';
COMMENT ON COLUMN "sys_permission"."sort_no" IS '排序';

CREATE TABLE "sys_role_permission" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "role_id" BIGINT NOT NULL,
  "perm_id" BIGINT NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_role_perm" UNIQUE ("role_id", "perm_id")
);
COMMENT ON TABLE "sys_role_permission" IS '角色权限关联表';
COMMENT ON COLUMN "sys_role_permission"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_role_permission"."tenant_code" IS '租户编码（平台角色为 PLATFORM）';
COMMENT ON COLUMN "sys_role_permission"."role_id" IS '角色ID';
COMMENT ON COLUMN "sys_role_permission"."perm_id" IS '权限ID';
CREATE INDEX "idx_sys_role_permission_perm_id" ON "sys_role_permission" ("perm_id");

CREATE TABLE "sys_user_role" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "role_id" BIGINT NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_user_role" UNIQUE ("tenant_code", "user_id", "role_id")
);
COMMENT ON TABLE "sys_user_role" IS '用户角色关联表';
COMMENT ON COLUMN "sys_user_role"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "sys_user_role"."tenant_code" IS '租户编码';
COMMENT ON COLUMN "sys_user_role"."user_id" IS '用户ID';
COMMENT ON COLUMN "sys_user_role"."role_id" IS '角色ID';
CREATE INDEX "idx_sys_user_role_role_id" ON "sys_user_role" ("role_id");
```

### 3.7 login\_log 登录记录表

```sql
CREATE TABLE "login_log" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "login_type" SMALLINT NOT NULL,
  "ip" VARCHAR(64) DEFAULT NULL,
  "device" VARCHAR(128) DEFAULT NULL,
  "browser" VARCHAR(64) DEFAULT NULL,
  "region" VARCHAR(64) DEFAULT NULL,
  "result" SMALLINT NOT NULL,
  "login_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_login_log_login_type CHECK ("login_type" IN (1,2,3)),
  CONSTRAINT ck_login_log_result CHECK ("result" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "login_log" IS '登录记录表';
COMMENT ON COLUMN "login_log"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "login_log"."tenant_code" IS '租户编码';
COMMENT ON COLUMN "login_log"."user_id" IS '用户ID';
COMMENT ON COLUMN "login_log"."login_type" IS '登录方式码：1-密码、2-企业微信SSO、3-飞书SSO';
COMMENT ON COLUMN "login_log"."ip" IS '登录IP';
COMMENT ON COLUMN "login_log"."device" IS '设备信息';
COMMENT ON COLUMN "login_log"."browser" IS '浏览器';
COMMENT ON COLUMN "login_log"."region" IS '登录地区';
COMMENT ON COLUMN "login_log"."result" IS '结果码：1-成功、2-失败、3-异常';
COMMENT ON COLUMN "login_log"."login_time" IS '登录时间';
CREATE INDEX "idx_login_log_tenant_user_time" ON "login_log" ("tenant_code", "user_id", "login_time");
```

---

## 4\. 渠道与配置域（所属库：customer\_db）

### 4.1 channel 渠道表

```sql
CREATE TABLE "channel" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" VARCHAR(32) NOT NULL,
  "channel_type" SMALLINT NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "desc" VARCHAR(255) DEFAULT NULL,
  "skill_group_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 2,
  "stage" SMALLINT NOT NULL DEFAULT 1,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_channel_channel_type CHECK ("channel_type" IN (1,2,3,4,5,6,7)),
  CONSTRAINT ck_channel_status CHECK ("status" IN (1,2)),
  CONSTRAINT ck_channel_stage CHECK ("stage" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_channel" UNIQUE ("tenant_code", "channel_id")
);
COMMENT ON TABLE "channel" IS '渠道表';
COMMENT ON COLUMN "channel"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "channel"."channel_id" IS '渠道ID（SDK 用）';
COMMENT ON COLUMN "channel"."channel_type" IS '类型码：1-网站、2-公众号、3-小程序、4-App、5-400热线、6-邮件、7-自定义';
COMMENT ON COLUMN "channel"."name" IS '渠道名称';
COMMENT ON COLUMN "channel"."desc" IS '描述';
COMMENT ON COLUMN "channel"."skill_group_id" IS '绑定技能组ID';
COMMENT ON COLUMN "channel"."status" IS '状态码：1-启用、2-停用';
COMMENT ON COLUMN "channel"."stage" IS '阶段码：1-未配置、2-待上线、3-运行中';
```

### 4.2 channel\_key 渠道密钥表

```sql
CREATE TABLE "channel_key" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "app_key" VARCHAR(64) NOT NULL,
  "key_type" SMALLINT NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "rotated_at" TIMESTAMP DEFAULT NULL,
  "expire_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_channel_key_key_type CHECK ("key_type" IN (1,2)),
  CONSTRAINT ck_channel_key_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_channel_key" UNIQUE ("tenant_code", "channel_id", "app_key")
);
COMMENT ON TABLE "channel_key" IS '渠道密钥表';
COMMENT ON COLUMN "channel_key"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "channel_key"."channel_id" IS '渠道ID';
COMMENT ON COLUMN "channel_key"."app_key" IS '密钥（加密存储）';
COMMENT ON COLUMN "channel_key"."key_type" IS '类型码：1-渠道密钥、2-租户主密钥';
COMMENT ON COLUMN "channel_key"."status" IS '状态码：1-生效、2-已吊销';
COMMENT ON COLUMN "channel_key"."rotated_at" IS '最近轮换时间';
COMMENT ON COLUMN "channel_key"."expire_time" IS '过期时间';
```

### 4.3 skill\_group 技能组表

```sql
CREATE TABLE "skill_group" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "description" VARCHAR(255) DEFAULT NULL,
  "is_default" BOOLEAN NOT NULL DEFAULT FALSE,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_name" UNIQUE ("tenant_code", "name")
);
COMMENT ON TABLE "skill_group" IS '技能组表';
COMMENT ON COLUMN "skill_group"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "skill_group"."name" IS '技能组名称';
COMMENT ON COLUMN "skill_group"."description" IS '说明';
COMMENT ON COLUMN "skill_group"."is_default" IS '是否默认';
```

### 4.4 channel\_test\_log 渠道联调记录表

```sql
CREATE TABLE "channel_test_log" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "test_result" SMALLINT NOT NULL,
  "latency_ms" INT DEFAULT NULL,
  "summary" VARCHAR(255) DEFAULT NULL,
  "test_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_channel_test_log_test_result CHECK ("test_result" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "channel_test_log" IS '渠道联调记录表';
COMMENT ON COLUMN "channel_test_log"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "channel_test_log"."channel_id" IS '渠道ID';
COMMENT ON COLUMN "channel_test_log"."test_result" IS '结果码：1-通过、2-失败';
COMMENT ON COLUMN "channel_test_log"."latency_ms" IS '延迟（毫秒）';
COMMENT ON COLUMN "channel_test_log"."summary" IS '检查摘要';
COMMENT ON COLUMN "channel_test_log"."test_time" IS '测试时间';
CREATE INDEX "idx_channel_test_log_tenant_channel_time" ON "channel_test_log" ("tenant_code", "channel_id", "test_time");
```

---

## 5\. 客服业务域（所属库：customer\_db）

### 5.1 session 会话表

```sql
CREATE TABLE "session" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_no" VARCHAR(40) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "skill_group_id" BIGINT DEFAULT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "intent" VARCHAR(64) DEFAULT NULL,
  "emotion" VARCHAR(20) DEFAULT NULL,
  "source" VARCHAR(20) DEFAULT NULL,
  "start_time" TIMESTAMP NOT NULL,
  "end_time" TIMESTAMP DEFAULT NULL,
  "csat_score" SMALLINT DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_session_status CHECK ("status" IN (1,2,3,4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_session_no" UNIQUE ("tenant_code", "session_no")
);
COMMENT ON TABLE "session" IS '会话表';
COMMENT ON COLUMN "session"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "session"."session_no" IS '会话编号';
COMMENT ON COLUMN "session"."channel_id" IS '来源渠道ID';
COMMENT ON COLUMN "session"."customer_id" IS '客户ID';
COMMENT ON COLUMN "session"."status" IS '状态码：1-排队中、2-机器人接待、3-人工接待、4-已结束';
COMMENT ON COLUMN "session"."skill_group_id" IS '技能组ID';
COMMENT ON COLUMN "session"."agent_id" IS '当前坐席用户ID';
COMMENT ON COLUMN "session"."intent" IS '当前意图';
COMMENT ON COLUMN "session"."emotion" IS '情绪标签';
COMMENT ON COLUMN "session"."source" IS '来源：微信、App、网站等渠道';
COMMENT ON COLUMN "session"."start_time" IS '开始时间';
COMMENT ON COLUMN "session"."end_time" IS '结束时间';
COMMENT ON COLUMN "session"."csat_score" IS '满意度评分1-5';
CREATE INDEX "idx_session_tenant_status_time" ON "session" ("tenant_code", "status", "create_time");
CREATE INDEX "idx_session_tenant_customer" ON "session" ("tenant_code", "customer_id");
```

### 5.2 session\_message 会话消息表

```sql
CREATE TABLE "session_message" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "msg_no" VARCHAR(40) NOT NULL,
  "msg_type" SMALLINT NOT NULL,
  "sender_type" SMALLINT NOT NULL,
  "sender_id" BIGINT DEFAULT NULL,
  "content" TEXT,
  "ref_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "send_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_session_message_msg_type CHECK ("msg_type" IN (1,2,3,4,5)),
  CONSTRAINT ck_session_message_sender_type CHECK ("sender_type" IN (1,2,3,4)),
  CONSTRAINT ck_session_message_status CHECK ("status" IN (1,2,3,4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_msg_no" UNIQUE ("tenant_code", "msg_no")
);
COMMENT ON TABLE "session_message" IS '会话消息表';
COMMENT ON COLUMN "session_message"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "session_message"."session_id" IS '会话ID';
COMMENT ON COLUMN "session_message"."msg_no" IS '消息编号（客户端幂等键）';
COMMENT ON COLUMN "session_message"."msg_type" IS '类型码：1-文本、2-图片、3-卡片、4-事件、5-系统';
COMMENT ON COLUMN "session_message"."sender_type" IS '发送方码：1-客户、2-坐席、3-机器人、4-系统';
COMMENT ON COLUMN "session_message"."sender_id" IS '发送人ID';
COMMENT ON COLUMN "session_message"."content" IS '消息内容（JSON，含图片/卡片结构）';
COMMENT ON COLUMN "session_message"."ref_id" IS '引用消息ID';
COMMENT ON COLUMN "session_message"."status" IS '状态码：1-已发送、2-已送达、3-已读、4-失败';
COMMENT ON COLUMN "session_message"."send_time" IS '发送时间';
CREATE INDEX "idx_session_message_tenant_session_time" ON "session_message" ("tenant_code", "session_id", "send_time");
```

### 5.3 session\_event 会话事件表

```sql
CREATE TABLE "session_event" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "event_type" SMALLINT NOT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "from_value" VARCHAR(64) DEFAULT NULL,
  "to_value" VARCHAR(64) DEFAULT NULL,
  "remark" VARCHAR(255) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_session_event_event_type CHECK ("event_type" IN (1,2,3,4,5)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "session_event" IS '会话事件表';
COMMENT ON COLUMN "session_event"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "session_event"."session_id" IS '会话ID';
COMMENT ON COLUMN "session_event"."event_type" IS '事件类型码：1-转接、2-升级、3-分配、4-关闭、5-超时';
COMMENT ON COLUMN "session_event"."operator_id" IS '操作人ID';
COMMENT ON COLUMN "session_event"."from_value" IS '来源值（原坐席/原技能组）';
COMMENT ON COLUMN "session_event"."to_value" IS '目标值';
COMMENT ON COLUMN "session_event"."remark" IS '备注';
COMMENT ON COLUMN "session_event"."event_time" IS '事件时间';
CREATE INDEX "idx_session_event_tenant_session" ON "session_event" ("tenant_code", "session_id", "event_time");
```

### 5.4 customer 客户表

```sql
CREATE TABLE "customer" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_no" VARCHAR(32) NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "phone" VARCHAR(20) DEFAULT NULL,
  "level" SMALLINT NOT NULL DEFAULT 1,
  "channel" VARCHAR(20) DEFAULT NULL,
  "orders_count" INT NOT NULL DEFAULT 0,
  "total_value" NUMERIC(12,2) NOT NULL DEFAULT 0.00,
  "points" INT NOT NULL DEFAULT 0,
  "csat" NUMERIC(5,2) DEFAULT NULL,
  "sentiment" SMALLINT DEFAULT NULL,
  "last_active" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_level CHECK ("level" IN (1,2,3,4,5)),
  CONSTRAINT ck_customer_sentiment CHECK ("sentiment" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_customer_no" UNIQUE ("tenant_code", "customer_no")
);
COMMENT ON TABLE "customer" IS '客户表';
COMMENT ON COLUMN "customer"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "customer"."customer_no" IS '客户编号';
COMMENT ON COLUMN "customer"."name" IS '客户姓名/昵称';
COMMENT ON COLUMN "customer"."phone" IS '手机号（脱敏）';
COMMENT ON COLUMN "customer"."level" IS '会员等级码：1-普通、2-银卡、3-金卡、4-铂金、5-企业';
COMMENT ON COLUMN "customer"."channel" IS '常用渠道';
COMMENT ON COLUMN "customer"."orders_count" IS '订单数';
COMMENT ON COLUMN "customer"."total_value" IS '累计消费';
COMMENT ON COLUMN "customer"."points" IS '会员积分';
COMMENT ON COLUMN "customer"."csat" IS '满意度均值';
COMMENT ON COLUMN "customer"."sentiment" IS '情绪标签码：1-正面、2-负面、3-中性';
COMMENT ON COLUMN "customer"."last_active" IS '最近活跃时间';
CREATE INDEX "idx_customer_tenant_level" ON "customer" ("tenant_code", "level");
```

### 5.5 customer\_tag 客户标签表

```sql
CREATE TABLE "customer_tag" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_id" BIGINT NOT NULL,
  "tag_name" VARCHAR(32) NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_customer_tag" UNIQUE ("tenant_code", "customer_id", "tag_name")
);
COMMENT ON TABLE "customer_tag" IS '客户标签表';
COMMENT ON COLUMN "customer_tag"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "customer_tag"."customer_id" IS '客户ID';
COMMENT ON COLUMN "customer_tag"."tag_name" IS '标签名';
```

### 5.6 ticket 工单表（含支持工单）

```sql
CREATE TABLE "ticket" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "ticket_no" VARCHAR(40) NOT NULL,
  "ticket_type" SMALLINT NOT NULL DEFAULT 1,
  "title" VARCHAR(128) NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "desc" TEXT,
  "priority" SMALLINT NOT NULL DEFAULT 2,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "assignee_id" BIGINT DEFAULT NULL,
  "group_id" BIGINT DEFAULT NULL,
  "source_session_id" BIGINT DEFAULT NULL,
  "sla_deadline" TIMESTAMP DEFAULT NULL,
  "sla_state" SMALLINT DEFAULT 1,
  "close_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_ticket_ticket_type CHECK ("ticket_type" IN (1,2)),
  CONSTRAINT ck_ticket_priority CHECK ("priority" IN (1,2,3,4)),
  CONSTRAINT ck_ticket_status CHECK ("status" IN (1,2,3,4)),
  CONSTRAINT ck_ticket_sla_state CHECK ("sla_state" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_ticket_no" UNIQUE ("tenant_code", "ticket_no")
);
COMMENT ON TABLE "ticket" IS '工单表';
COMMENT ON COLUMN "ticket"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "ticket"."ticket_no" IS '工单号';
COMMENT ON COLUMN "ticket"."ticket_type" IS '类型码：1-企业内部工单、2-平台支持工单';
COMMENT ON COLUMN "ticket"."customer_id" IS '客户ID（快照，分库冗余，便于按客户查工单）';
COMMENT ON COLUMN "ticket"."title" IS '工单主题';
COMMENT ON COLUMN "ticket"."desc" IS '问题描述';
COMMENT ON COLUMN "ticket"."priority" IS '优先级码：1-低、2-中、3-高、4-紧急';
COMMENT ON COLUMN "ticket"."status" IS '状态码：1-待处理、2-处理中、3-已解决、4-已关闭';
COMMENT ON COLUMN "ticket"."assignee_id" IS '处理人ID';
COMMENT ON COLUMN "ticket"."group_id" IS '处理技能组ID';
COMMENT ON COLUMN "ticket"."source_session_id" IS '来源会话ID';
COMMENT ON COLUMN "ticket"."sla_deadline" IS 'SLA 截止时间';
COMMENT ON COLUMN "ticket"."sla_state" IS 'SLA 状态码：1-正常、2-预警、3-超时';
COMMENT ON COLUMN "ticket"."close_time" IS '关闭时间';
CREATE INDEX "idx_ticket_tenant_status_time" ON "ticket" ("tenant_code", "status", "create_time");
CREATE INDEX "idx_ticket_tenant_assignee" ON "ticket" ("tenant_code", "assignee_id");
```

### 5.7 ticket\_event 工单流转记录表

```sql
CREATE TABLE "ticket_event" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "ticket_id" BIGINT NOT NULL,
  "event_type" SMALLINT NOT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "content" VARCHAR(512) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ticket_event_event_type CHECK ("event_type" IN (1,2,3,4,5,6)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "ticket_event" IS '工单流转记录表';
COMMENT ON COLUMN "ticket_event"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "ticket_event"."ticket_id" IS '工单ID';
COMMENT ON COLUMN "ticket_event"."event_type" IS '事件码：1-创建、2-分配、3-回复、4-升级、5-关闭、6-重开';
COMMENT ON COLUMN "ticket_event"."operator_id" IS '操作人ID';
COMMENT ON COLUMN "ticket_event"."content" IS '事件内容';
CREATE INDEX "idx_ticket_event_tenant_ticket" ON "ticket_event" ("tenant_code", "ticket_id", "event_time");
```

### 5.8 notification / notification\_read 消息中心表

```sql
CREATE TABLE "notification" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "notify_type" SMALLINT NOT NULL,
  "title" VARCHAR(128) NOT NULL,
  "content" TEXT,
  "link_view" VARCHAR(64) DEFAULT NULL,
  "target_type" SMALLINT DEFAULT 1,
  "target_id" BIGINT DEFAULT NULL,
  "publish_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_notification_notify_type CHECK ("notify_type" IN (1,2,3,4,5,6)),
  CONSTRAINT ck_notification_target_type CHECK ("target_type" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "notification" IS '消息表';
COMMENT ON COLUMN "notification"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "notification"."notify_type" IS '类型码：1-系统、2-工单、3-审核、4-质检、5-公告、6-账单';
COMMENT ON COLUMN "notification"."title" IS '标题';
COMMENT ON COLUMN "notification"."content" IS '内容';
COMMENT ON COLUMN "notification"."link_view" IS '跳转页面标识';
COMMENT ON COLUMN "notification"."target_type" IS '目标码：1-用户、2-企业';
COMMENT ON COLUMN "notification"."target_id" IS '目标ID';
COMMENT ON COLUMN "notification"."publish_time" IS '发布时间';
CREATE INDEX "idx_notification_tenant_type_time" ON "notification" ("tenant_code", "notify_type", "publish_time");

CREATE TABLE "notification_read" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "notify_id" BIGINT NOT NULL,
  "user_id" BIGINT NOT NULL,
  "read_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_notify_user" UNIQUE ("tenant_code", "notify_id", "user_id")
);
COMMENT ON TABLE "notification_read" IS '消息已读表';
COMMENT ON COLUMN "notification_read"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "notification_read"."notify_id" IS '消息ID';
COMMENT ON COLUMN "notification_read"."user_id" IS '用户ID';
COMMENT ON COLUMN "notification_read"."read_time" IS '已读时间';
```

---

## 6\. 知识库域（所属库：customer\_db）

### 6.1 kb\_category 知识分类表

```sql
CREATE TABLE "kb_category" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "parent_id" BIGINT NOT NULL DEFAULT 0,
  "name" VARCHAR(64) NOT NULL,
  "sort_no" INT NOT NULL DEFAULT 0,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "kb_category" IS '知识分类表';
COMMENT ON COLUMN "kb_category"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "kb_category"."parent_id" IS '父分类ID';
COMMENT ON COLUMN "kb_category"."name" IS '分类名称';
COMMENT ON COLUMN "kb_category"."sort_no" IS '排序';
CREATE INDEX "idx_kb_category_tenant_parent" ON "kb_category" ("tenant_code", "parent_id");
```

### 6.2 kb\_document 知识文档表

```sql
CREATE TABLE "kb_document" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_no" VARCHAR(40) NOT NULL,
  "category_id" BIGINT DEFAULT NULL,
  "title" VARCHAR(255) NOT NULL,
  "content" TEXT,
  "summary" VARCHAR(512) DEFAULT NULL,
  "source_type" SMALLINT DEFAULT 1,
  "file_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "hit_count" INT NOT NULL DEFAULT 0,
  "useful_rate" NUMERIC(5,2) DEFAULT NULL,
  "author" VARCHAR(64) DEFAULT NULL,
  "publish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_kb_document_source_type CHECK ("source_type" IN (1,2,3)),
  CONSTRAINT ck_kb_document_status CHECK ("status" IN (1,2,3,4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_doc_no" UNIQUE ("tenant_code", "doc_no")
);
COMMENT ON TABLE "kb_document" IS '知识文档表';
COMMENT ON COLUMN "kb_document"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "kb_document"."doc_no" IS '知识编号';
COMMENT ON COLUMN "kb_document"."category_id" IS '分类ID';
COMMENT ON COLUMN "kb_document"."title" IS '标题';
COMMENT ON COLUMN "kb_document"."content" IS '内容';
COMMENT ON COLUMN "kb_document"."summary" IS '摘要';
COMMENT ON COLUMN "kb_document"."source_type" IS '来源码：1-手工、2-导入、3-AI生成';
COMMENT ON COLUMN "kb_document"."file_id" IS '原文件ID';
COMMENT ON COLUMN "kb_document"."status" IS '状态码：1-草稿、2-审核中、3-已发布、4-已下线';
COMMENT ON COLUMN "kb_document"."hit_count" IS '命中次数';
COMMENT ON COLUMN "kb_document"."useful_rate" IS '采纳率';
COMMENT ON COLUMN "kb_document"."author" IS '维护人';
COMMENT ON COLUMN "kb_document"."publish_time" IS '发布时间';
CREATE INDEX "idx_kb_document_tenant_status" ON "kb_document" ("tenant_code", "status");
CREATE INDEX "idx_kb_document_tenant_category" ON "kb_document" ("tenant_code", "category_id");
```

### 6.3 kb\_version 知识版本表

```sql
CREATE TABLE "kb_version" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "version_no" INT NOT NULL,
  "content_snapshot" TEXT,
  "change_log" VARCHAR(255) DEFAULT NULL,
  "publisher" VARCHAR(64) DEFAULT NULL,
  "publish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "kb_version" IS '知识版本表';
COMMENT ON COLUMN "kb_version"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "kb_version"."doc_id" IS '文档ID';
COMMENT ON COLUMN "kb_version"."version_no" IS '版本号';
COMMENT ON COLUMN "kb_version"."content_snapshot" IS '内容快照';
COMMENT ON COLUMN "kb_version"."change_log" IS '变更说明';
COMMENT ON COLUMN "kb_version"."publisher" IS '发布人';
COMMENT ON COLUMN "kb_version"."publish_time" IS '发布时间';
CREATE INDEX "idx_kb_version_tenant_doc" ON "kb_version" ("tenant_code", "doc_id", "version_no");
```

### 6.4 kb\_audit 知识审核表

```sql
CREATE TABLE "kb_audit" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "version_no" INT DEFAULT NULL,
  "auditor_id" BIGINT DEFAULT NULL,
  "result" SMALLINT DEFAULT NULL,
  "reason" VARCHAR(255) DEFAULT NULL,
  "submit_time" TIMESTAMP NOT NULL,
  "audit_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_kb_audit_result CHECK ("result" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "kb_audit" IS '知识审核表';
COMMENT ON COLUMN "kb_audit"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "kb_audit"."doc_id" IS '文档ID';
COMMENT ON COLUMN "kb_audit"."version_no" IS '申请版本';
COMMENT ON COLUMN "kb_audit"."auditor_id" IS '审核人ID';
COMMENT ON COLUMN "kb_audit"."result" IS '结果码：1-通过、2-驳回';
COMMENT ON COLUMN "kb_audit"."reason" IS '原因';
COMMENT ON COLUMN "kb_audit"."submit_time" IS '提交时间';
COMMENT ON COLUMN "kb_audit"."audit_time" IS '审核时间';
CREATE INDEX "idx_kb_audit_tenant_doc" ON "kb_audit" ("tenant_code", "doc_id", "submit_time");
```

---

## 7\. 机器人 / AI 域（所属库：ai\_db）

### 7.1 bot\_intent 意图表

```sql
CREATE TABLE "bot_intent" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "intent_code" VARCHAR(64) NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "confidence" NUMERIC(5,2) DEFAULT NULL,
  "hit_count" INT NOT NULL DEFAULT 0,
  "samples" TEXT,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_bot_intent_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_intent" UNIQUE ("tenant_code", "intent_code")
);
COMMENT ON TABLE "bot_intent" IS '机器人意图表';
COMMENT ON COLUMN "bot_intent"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "bot_intent"."intent_code" IS '意图编码';
COMMENT ON COLUMN "bot_intent"."name" IS '意图名称';
COMMENT ON COLUMN "bot_intent"."status" IS '状态码：1-启用、2-停用';
COMMENT ON COLUMN "bot_intent"."confidence" IS '平均置信度';
COMMENT ON COLUMN "bot_intent"."hit_count" IS '命中次数';
COMMENT ON COLUMN "bot_intent"."samples" IS '训练语料示例';
```

### 7.2 bot\_model / bot\_version 模型配置与版本表

```sql
CREATE TABLE "bot_model" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "model_key" VARCHAR(64) NOT NULL,
  "model_name" VARCHAR(64) NOT NULL,
  "provider" VARCHAR(32) NOT NULL,
  "model_type" SMALLINT NOT NULL,
  "temperature" NUMERIC(3,2) NOT NULL DEFAULT 0.30,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_bot_model_model_type CHECK ("model_type" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_model" UNIQUE ("tenant_code", "model_key")
);
COMMENT ON TABLE "bot_model" IS '机器人模型配置表';
COMMENT ON COLUMN "bot_model"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "bot_model"."model_key" IS '模型标识';
COMMENT ON COLUMN "bot_model"."model_name" IS '模型名称';
COMMENT ON COLUMN "bot_model"."provider" IS '供应商：千问、DeepSeek等';
COMMENT ON COLUMN "bot_model"."model_type" IS '类型码：1-对话、2-视觉、3-轻量';
COMMENT ON COLUMN "bot_model"."temperature" IS '温度参数';

CREATE TABLE "bot_version" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "version_no" VARCHAR(32) NOT NULL,
  "train_type" SMALLINT NOT NULL,
  "base_model" VARCHAR(64) DEFAULT NULL,
  "accuracy" NUMERIC(5,2) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "publish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_bot_version_train_type CHECK ("train_type" IN (1,2)),
  CONSTRAINT ck_bot_version_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_version" UNIQUE ("tenant_code", "version_no")
);
COMMENT ON TABLE "bot_version" IS '机器人模型版本表';
COMMENT ON COLUMN "bot_version"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "bot_version"."version_no" IS '版本号（v9.2）';
COMMENT ON COLUMN "bot_version"."train_type" IS '训练类型码：1-全量、2-增量';
COMMENT ON COLUMN "bot_version"."base_model" IS '基座模型';
COMMENT ON COLUMN "bot_version"."accuracy" IS '识别率';
COMMENT ON COLUMN "bot_version"."status" IS '状态码：1-训练中、2-已上线、3-已回滚';
COMMENT ON COLUMN "bot_version"."publish_time" IS '上线时间';
```

### 7.3 training\_job 训练任务表

```sql
CREATE TABLE "training_job" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "job_no" VARCHAR(40) NOT NULL,
  "job_type" SMALLINT NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "progress" INT NOT NULL DEFAULT 0,
  "result_version" VARCHAR(32) DEFAULT NULL,
  "error_msg" VARCHAR(512) DEFAULT NULL,
  "start_time" TIMESTAMP DEFAULT NULL,
  "finish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_training_job_job_type CHECK ("job_type" IN (1,2)),
  CONSTRAINT ck_training_job_status CHECK ("status" IN (1,2,3,4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_job_no" UNIQUE ("tenant_code", "job_no")
);
COMMENT ON TABLE "training_job" IS 'AI训练任务表';
COMMENT ON COLUMN "training_job"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "training_job"."job_no" IS '任务编号';
COMMENT ON COLUMN "training_job"."job_type" IS '类型码：1-意图训练、2-模型训练';
COMMENT ON COLUMN "training_job"."status" IS '状态码：1-等待中、2-执行中、3-成功、4-失败';
COMMENT ON COLUMN "training_job"."progress" IS '进度 0-100';
COMMENT ON COLUMN "training_job"."result_version" IS '产出版本';
COMMENT ON COLUMN "training_job"."error_msg" IS '失败原因';
COMMENT ON COLUMN "training_job"."start_time" IS '开始时间';
COMMENT ON COLUMN "training_job"."finish_time" IS '结束时间';
CREATE INDEX "idx_training_job_tenant_status" ON "training_job" ("tenant_code", "status");
```

### 7.4 ai\_usage\_record AI 调用计量表

```sql
CREATE TABLE "ai_usage_record" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "model_key" VARCHAR(64) NOT NULL,
  "scene" SMALLINT NOT NULL,
  "input_tokens" INT NOT NULL DEFAULT 0,
  "output_tokens" INT NOT NULL DEFAULT 0,
  "cost_amount" NUMERIC(12,4) NOT NULL DEFAULT 0,
  "latency_ms" INT DEFAULT NULL,
  "is_success" BOOLEAN NOT NULL DEFAULT TRUE,
  "request_id" VARCHAR(64) DEFAULT NULL,
  "stat_date" DATE NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ai_usage_record_scene CHECK ("scene" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "ai_usage_record" IS 'AI调用计量表';
COMMENT ON COLUMN "ai_usage_record"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "ai_usage_record"."model_key" IS '模型标识';
COMMENT ON COLUMN "ai_usage_record"."scene" IS '场景码：1-对话、2-质检、3-视觉识别';
COMMENT ON COLUMN "ai_usage_record"."input_tokens" IS '输入 token';
COMMENT ON COLUMN "ai_usage_record"."output_tokens" IS '输出 token';
COMMENT ON COLUMN "ai_usage_record"."cost_amount" IS '成本金额';
COMMENT ON COLUMN "ai_usage_record"."latency_ms" IS '耗时（毫秒）';
COMMENT ON COLUMN "ai_usage_record"."is_success" IS '是否成功';
COMMENT ON COLUMN "ai_usage_record"."request_id" IS '关联请求号';
COMMENT ON COLUMN "ai_usage_record"."stat_date" IS '统计日期（用于聚合）';
CREATE INDEX "idx_ai_usage_record_tenant_model_date" ON "ai_usage_record" ("tenant_code", "model_key", "stat_date");
```

---

## 8\. 质检域（所属库：customer\_db）

### 8.1 qa\_task 质检任务表

```sql
CREATE TABLE "qa_task" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "task_no" VARCHAR(40) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "ai_score" NUMERIC(5,2) DEFAULT NULL,
  "ai_result" VARCHAR(512) DEFAULT NULL,
  "risk_level" SMALLINT DEFAULT 1,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "reviewer_id" BIGINT DEFAULT NULL,
  "review_score" NUMERIC(5,2) DEFAULT NULL,
  "review_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_task_risk_level CHECK ("risk_level" IN (1,2,3)),
  CONSTRAINT ck_qa_task_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_task_no" UNIQUE ("tenant_code", "task_no")
);
COMMENT ON TABLE "qa_task" IS '质检任务表';
COMMENT ON COLUMN "qa_task"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "qa_task"."task_no" IS '任务编号';
COMMENT ON COLUMN "qa_task"."session_id" IS '关联会话ID';
COMMENT ON COLUMN "qa_task"."agent_id" IS '被检坐席ID';
COMMENT ON COLUMN "qa_task"."ai_score" IS 'AI 初检评分';
COMMENT ON COLUMN "qa_task"."ai_result" IS 'AI 命中说明（JSON）';
COMMENT ON COLUMN "qa_task"."risk_level" IS '风险码：1-低、2-中、3-高';
COMMENT ON COLUMN "qa_task"."status" IS '状态码：1-待复核、2-已通过、3-已驳回';
COMMENT ON COLUMN "qa_task"."reviewer_id" IS '复核人ID';
COMMENT ON COLUMN "qa_task"."review_score" IS '复核评分';
COMMENT ON COLUMN "qa_task"."review_time" IS '复核时间';
CREATE INDEX "idx_qa_task_tenant_status" ON "qa_task" ("tenant_code", "status");
CREATE INDEX "idx_qa_task_tenant_session" ON "qa_task" ("tenant_code", "session_id");
```

### 8.2 qa\_rule 质检规则表

```sql
CREATE TABLE "qa_rule" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "rule_name" VARCHAR(64) NOT NULL,
  "rule_type" SMALLINT NOT NULL,
  "rule_content" TEXT,
  "weight" INT NOT NULL DEFAULT 1,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_rule_rule_type CHECK ("rule_type" IN (1,2,3,4)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "qa_rule" IS '质检规则表';
COMMENT ON COLUMN "qa_rule"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "qa_rule"."rule_name" IS '规则名称';
COMMENT ON COLUMN "qa_rule"."rule_type" IS '类型码：1-敏感词、2-承诺规范、3-必答项、4-情绪识别';
COMMENT ON COLUMN "qa_rule"."rule_content" IS '规则内容（关键词/正则/描述）';
COMMENT ON COLUMN "qa_rule"."weight" IS '权重';
CREATE INDEX "idx_qa_rule_tenant_type" ON "qa_rule" ("tenant_code", "rule_type");
```

### 8.3 qa\_review 质检复核表

```sql
CREATE TABLE "qa_review" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "task_id" BIGINT NOT NULL,
  "reviewer_id" BIGINT NOT NULL,
  "action" SMALLINT NOT NULL,
  "comment" VARCHAR(512) DEFAULT NULL,
  "review_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_qa_review_action CHECK ("action" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "qa_review" IS '质检复核记录表';
COMMENT ON COLUMN "qa_review"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "qa_review"."task_id" IS '质检任务ID';
COMMENT ON COLUMN "qa_review"."reviewer_id" IS '复核人ID';
COMMENT ON COLUMN "qa_review"."action" IS '动作码：1-通过、2-驳回、3-重检';
COMMENT ON COLUMN "qa_review"."comment" IS '复核意见';
CREATE INDEX "idx_qa_review_tenant_task" ON "qa_review" ("tenant_code", "task_id", "review_time");
```

---

## 9\. 计费域（所属库：billing\_db）

### 9.1 plan 套餐表

```sql
CREATE TABLE "plan" (
  "id" BIGINT NOT NULL,
  "plan_code" VARCHAR(32) NOT NULL,
  "plan_name" VARCHAR(64) NOT NULL,
  "price_yearly" NUMERIC(12,2) NOT NULL,
  "price_monthly" NUMERIC(12,2) NOT NULL,
  "quota_robot_sessions" INT NOT NULL DEFAULT 0,
  "quota_channels" INT NOT NULL DEFAULT 0,
  "quota_ai_qa" BOOLEAN NOT NULL DEFAULT FALSE,
  "quota_vision" BOOLEAN NOT NULL DEFAULT FALSE,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_plan_code" UNIQUE ("plan_code")
);
COMMENT ON TABLE "plan" IS '套餐表';
COMMENT ON COLUMN "plan"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "plan"."plan_code" IS '套餐编码：标准版、专业版、企业版';
COMMENT ON COLUMN "plan"."plan_name" IS '套餐名称';
COMMENT ON COLUMN "plan"."price_yearly" IS '年付价格';
COMMENT ON COLUMN "plan"."price_monthly" IS '月付价格';
COMMENT ON COLUMN "plan"."quota_robot_sessions" IS '月机器人会话配额';
COMMENT ON COLUMN "plan"."quota_channels" IS '渠道数配额';
COMMENT ON COLUMN "plan"."quota_ai_qa" IS '是否含全量质检';
COMMENT ON COLUMN "plan"."quota_vision" IS '是否含视觉识别';
```

### 9.2 billing\_order 订单表

```sql
CREATE TABLE "billing_order" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "order_no" VARCHAR(40) NOT NULL,
  "plan_id" BIGINT NOT NULL,
  "cycle" SMALLINT NOT NULL,
  "amount" NUMERIC(12,2) NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "pay_method" SMALLINT DEFAULT NULL,
  "pay_time" TIMESTAMP DEFAULT NULL,
  "invoice_status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_billing_order_cycle CHECK ("cycle" IN (1,2)),
  CONSTRAINT ck_billing_order_status CHECK ("status" IN (1,2,3,4)),
  CONSTRAINT ck_billing_order_pay_method CHECK ("pay_method" IN (1,2,3)),
  CONSTRAINT ck_billing_order_invoice_status CHECK ("invoice_status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_order_no" UNIQUE ("tenant_code", "order_no")
);
COMMENT ON TABLE "billing_order" IS '订单表';
COMMENT ON COLUMN "billing_order"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "billing_order"."order_no" IS '订单号';
COMMENT ON COLUMN "billing_order"."plan_id" IS '套餐ID';
COMMENT ON COLUMN "billing_order"."cycle" IS '周期码：1-年付、2-月付';
COMMENT ON COLUMN "billing_order"."amount" IS '订单金额';
COMMENT ON COLUMN "billing_order"."status" IS '状态码：1-待支付、2-已支付、3-已取消、4-已退款';
COMMENT ON COLUMN "billing_order"."pay_method" IS '支付方式码：1-支付宝、2-微信、3-对公转账';
COMMENT ON COLUMN "billing_order"."pay_time" IS '支付时间';
COMMENT ON COLUMN "billing_order"."invoice_status" IS '发票状态码：1-未申请、2-已申请、3-已开具';
CREATE INDEX "idx_billing_order_tenant_status" ON "billing_order" ("tenant_code", "status", "create_time");
```

### 9.3 payment\_record / invoice\_record 支付与发票表

```sql
CREATE TABLE "payment_record" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "order_id" BIGINT NOT NULL,
  "pay_no" VARCHAR(64) NOT NULL,
  "pay_method" VARCHAR(20) NOT NULL,
  "amount" NUMERIC(12,2) NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "pay_time" TIMESTAMP DEFAULT NULL,
  "callback_raw" TEXT,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_payment_record_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_pay_no" UNIQUE ("pay_no")
);
COMMENT ON TABLE "payment_record" IS '支付流水表';
COMMENT ON COLUMN "payment_record"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "payment_record"."order_id" IS '订单ID';
COMMENT ON COLUMN "payment_record"."pay_no" IS '支付流水号（三方）';
COMMENT ON COLUMN "payment_record"."pay_method" IS '支付方式';
COMMENT ON COLUMN "payment_record"."amount" IS '支付金额';
COMMENT ON COLUMN "payment_record"."status" IS '状态码：1-处理中、2-成功、3-失败';
COMMENT ON COLUMN "payment_record"."pay_time" IS '支付时间';
COMMENT ON COLUMN "payment_record"."callback_raw" IS '三方回调原文';
CREATE INDEX "idx_payment_record_tenant_order" ON "payment_record" ("tenant_code", "order_id");

CREATE TABLE "invoice_record" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "order_id" BIGINT NOT NULL,
  "invoice_no" VARCHAR(40) DEFAULT NULL,
  "invoice_type" SMALLINT NOT NULL,
  "title" VARCHAR(128) NOT NULL,
  "amount" NUMERIC(12,2) NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "file_id" BIGINT DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_invoice_record_invoice_type CHECK ("invoice_type" IN (1,2)),
  CONSTRAINT ck_invoice_record_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "invoice_record" IS '发票记录表';
COMMENT ON COLUMN "invoice_record"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "invoice_record"."order_id" IS '订单ID';
COMMENT ON COLUMN "invoice_record"."invoice_no" IS '发票号码';
COMMENT ON COLUMN "invoice_record"."invoice_type" IS '类型码：1-电子发票、2-增值税专用发票';
COMMENT ON COLUMN "invoice_record"."title" IS '发票抬头';
COMMENT ON COLUMN "invoice_record"."amount" IS '开票金额';
COMMENT ON COLUMN "invoice_record"."status" IS '状态码：1-已申请、2-已开具、3-已驳回';
COMMENT ON COLUMN "invoice_record"."file_id" IS '发票文件ID';
CREATE INDEX "idx_invoice_record_tenant_order" ON "invoice_record" ("tenant_code", "order_id");
```

---

## 10\. 平台与文件域（所属库：ops\_db + customer\_db）

### 10.1 site\_config 官网配置表

```sql
CREATE TABLE "site_config" (
  "id" BIGINT NOT NULL,
  "section_key" VARCHAR(32) NOT NULL,
  "config_json" JSON NOT NULL,
  "version_no" INT NOT NULL DEFAULT 1,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "published_at" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_site_config_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_section_version" UNIQUE ("section_key", "version_no")
);
COMMENT ON TABLE "site_config" IS '官网配置表';
COMMENT ON COLUMN "site_config"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "site_config"."section_key" IS '区块标识：首屏、功能、定价、常见问题等';
COMMENT ON COLUMN "site_config"."config_json" IS '区块配置内容（JSON）';
COMMENT ON COLUMN "site_config"."version_no" IS '配置版本';
COMMENT ON COLUMN "site_config"."status" IS '状态码：1-草稿、2-已发布';
COMMENT ON COLUMN "site_config"."published_at" IS '发布时间';
```

### 10.2 announcement 公告表

```sql
CREATE TABLE "announcement" (
  "id" BIGINT NOT NULL,
  "title" VARCHAR(128) NOT NULL,
  "content" TEXT,
  "announce_type" SMALLINT NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "publish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_announcement_announce_type CHECK ("announce_type" IN (1,2,3)),
  CONSTRAINT ck_announcement_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "announcement" IS '平台公告表';
COMMENT ON COLUMN "announcement"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "announcement"."title" IS '公告标题';
COMMENT ON COLUMN "announcement"."content" IS '公告内容';
COMMENT ON COLUMN "announcement"."announce_type" IS '类型码：1-维护通知、2-功能公告、3-其他';
COMMENT ON COLUMN "announcement"."status" IS '状态码：1-草稿、2-已发布、3-已下线';
COMMENT ON COLUMN "announcement"."publish_time" IS '发布时间';
CREATE INDEX "idx_announcement_status_time" ON "announcement" ("status", "publish_time");
```

### 10.3 audit\_log 审计日志表

```sql
CREATE TABLE "audit_log" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "module" VARCHAR(32) NOT NULL,
  "action" VARCHAR(64) NOT NULL,
  "target_type" VARCHAR(64) DEFAULT NULL,
  "target_id" VARCHAR(64) DEFAULT NULL,
  "result" SMALLINT NOT NULL,
  "detail" VARCHAR(1024) DEFAULT NULL,
  "ip" VARCHAR(64) DEFAULT NULL,
  "request_id" VARCHAR(64) DEFAULT NULL,
  "audit_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_audit_log_result CHECK ("result" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "audit_log" IS '审计日志表（只追加，不更新不删除）';
COMMENT ON COLUMN "audit_log"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "audit_log"."tenant_code" IS '租户编码（平台操作为 PLATFORM）';
COMMENT ON COLUMN "audit_log"."operator_id" IS '操作人ID';
COMMENT ON COLUMN "audit_log"."module" IS '模块';
COMMENT ON COLUMN "audit_log"."action" IS '操作';
COMMENT ON COLUMN "audit_log"."target_type" IS '对象类型';
COMMENT ON COLUMN "audit_log"."target_id" IS '对象标识';
COMMENT ON COLUMN "audit_log"."result" IS '结果码：1-成功、2-失败';
COMMENT ON COLUMN "audit_log"."detail" IS '详情';
COMMENT ON COLUMN "audit_log"."ip" IS 'IP';
COMMENT ON COLUMN "audit_log"."request_id" IS '请求号';
COMMENT ON COLUMN "audit_log"."audit_time" IS '审计时间';
CREATE INDEX "idx_audit_log_tenant_time" ON "audit_log" ("tenant_code", "audit_time");
CREATE INDEX "idx_audit_log_operator_time" ON "audit_log" ("operator_id", "audit_time");
```

### 10.4 file\_meta 文件元数据表

```sql
CREATE TABLE "file_meta" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "file_no" VARCHAR(40) NOT NULL,
  "file_name" VARCHAR(255) NOT NULL,
  "object_key" VARCHAR(512) NOT NULL,
  "file_size" BIGINT NOT NULL DEFAULT 0,
  "mime_type" VARCHAR(64) DEFAULT NULL,
  "biz_type" SMALLINT NOT NULL,
  "biz_id" BIGINT DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_file_meta_biz_type CHECK ("biz_type" IN (1,2,3,4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_file_no" UNIQUE ("tenant_code", "file_no")
);
COMMENT ON TABLE "file_meta" IS '文件元数据表';
COMMENT ON COLUMN "file_meta"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "file_meta"."file_no" IS '文件编号';
COMMENT ON COLUMN "file_meta"."file_name" IS '原始文件名';
COMMENT ON COLUMN "file_meta"."object_key" IS 'OSS 对象键';
COMMENT ON COLUMN "file_meta"."file_size" IS '大小（字节）';
COMMENT ON COLUMN "file_meta"."mime_type" IS 'MIME 类型';
COMMENT ON COLUMN "file_meta"."biz_type" IS '业务类型码：1-头像、2-附件、3-导出、4-发票';
COMMENT ON COLUMN "file_meta"."biz_id" IS '业务ID';
CREATE INDEX "idx_file_meta_tenant_biz" ON "file_meta" ("tenant_code", "biz_type", "biz_id");
```

---

## 11\. 业务表补全（原型审计新增）

> 本节为原型审计后补充的业务表，按所属服务/库组织；公共字段（id / tenant\_code / create\_time / update\_time / creator / editor / is\_deleted）同 1.3 模板，除主键外不再重复注释。

### 11.1 user\_db（user-service）：member\_invite 成员邀请表

```sql
CREATE TABLE "member_invite" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "invite_code" VARCHAR(32) NOT NULL,
  "inviter_id" BIGINT NOT NULL,
  "role_id" BIGINT NOT NULL,
  "invitee_phone" VARCHAR(20) DEFAULT NULL,
  "invitee_email" VARCHAR(128) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "used_by" BIGINT DEFAULT NULL,
  "used_time" TIMESTAMP DEFAULT NULL,
  "expire_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_member_invite_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_member_invite_code" UNIQUE ("tenant_code", "invite_code")
);
COMMENT ON TABLE "member_invite" IS '成员邀请表';
COMMENT ON COLUMN "member_invite"."invite_code" IS '邀请码';
COMMENT ON COLUMN "member_invite"."inviter_id" IS '邀请人用户ID';
COMMENT ON COLUMN "member_invite"."role_id" IS '受邀默认角色ID';
COMMENT ON COLUMN "member_invite"."status" IS '状态码：1-有效、2-已使用、3-已失效';
COMMENT ON COLUMN "member_invite"."used_by" IS '使用人用户ID';
COMMENT ON COLUMN "member_invite"."used_time" IS '使用时间';
COMMENT ON COLUMN "member_invite"."expire_time" IS '过期时间';
CREATE INDEX "idx_member_invite_tenant_status" ON "member_invite" ("tenant_code", "status");
```

### 11.2 ops\_db（ops-service）：api\_key / data\_export / customer\_success / register\_config

```sql
CREATE TABLE "api_key" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "key_name" VARCHAR(64) NOT NULL,
  "api_key" VARCHAR(128) NOT NULL,
  "permissions" VARCHAR(255) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "last_used_at" TIMESTAMP DEFAULT NULL,
  "expire_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_api_key_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_api_key_value" UNIQUE ("tenant_code", "api_key")
);
COMMENT ON TABLE "api_key" IS '开放API密钥表';
COMMENT ON COLUMN "api_key"."key_name" IS '密钥名称';
COMMENT ON COLUMN "api_key"."api_key" IS '密钥值（加密存储）';
COMMENT ON COLUMN "api_key"."permissions" IS '权限范围（JSON）';
COMMENT ON COLUMN "api_key"."status" IS '状态码：1-生效、2-已吊销';
COMMENT ON COLUMN "api_key"."last_used_at" IS '最近使用时间';

CREATE TABLE "data_export" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "export_type" SMALLINT NOT NULL,
  "scope_json" JSON DEFAULT NULL,
  "file_id" BIGINT DEFAULT NULL,
  "requester_id" BIGINT NOT NULL,
  "approver_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "reject_reason" VARCHAR(512) DEFAULT NULL,
  "request_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "approve_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_data_export_type CHECK ("export_type" IN (1,2,3,4)),
  CONSTRAINT ck_data_export_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "data_export" IS '数据导出审批表';
COMMENT ON COLUMN "data_export"."export_type" IS '类型码：1-会话、2-客户、3-报表、4-审计';
COMMENT ON COLUMN "data_export"."scope_json" IS '导出范围（JSON）';
COMMENT ON COLUMN "data_export"."file_id" IS '导出文件ID（file_meta）';
COMMENT ON COLUMN "data_export"."requester_id" IS '申请人用户ID';
COMMENT ON COLUMN "data_export"."approver_id" IS '审批人用户ID';
COMMENT ON COLUMN "data_export"."status" IS '状态码：1-待审批、2-已通过、3-已驳回';
COMMENT ON COLUMN "data_export"."reject_reason" IS '驳回原因';

CREATE TABLE "customer_success" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "health_score" SMALLINT DEFAULT NULL,
  "renew_risk" SMALLINT NOT NULL DEFAULT 1,
  "expand_opportunity" SMALLINT NOT NULL DEFAULT 2,
  "cs_owner" BIGINT DEFAULT NULL,
  "last_visit_time" TIMESTAMP DEFAULT NULL,
  "next_visit_time" TIMESTAMP DEFAULT NULL,
  "remark" VARCHAR(512) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_success_risk CHECK ("renew_risk" IN (1,2,3)),
  CONSTRAINT ck_customer_success_expand CHECK ("expand_opportunity" IN (1,2)),
  CONSTRAINT ck_customer_success_health CHECK ("health_score" IS NULL OR ("health_score" BETWEEN 0 AND 100)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_customer_success_tenant" UNIQUE ("tenant_code")
);
COMMENT ON TABLE "customer_success" IS '客户成功回访表';
COMMENT ON COLUMN "customer_success"."health_score" IS '健康度评分 0-100';
COMMENT ON COLUMN "customer_success"."renew_risk" IS '续费风险码：1-低、2-中、3-高';
COMMENT ON COLUMN "customer_success"."expand_opportunity" IS '扩展商机码：1-有、2-无';
COMMENT ON COLUMN "customer_success"."cs_owner" IS '客户成功经理用户ID';
COMMENT ON COLUMN "customer_success"."last_visit_time" IS '上次回访时间';
COMMENT ON COLUMN "customer_success"."next_visit_time" IS '下次回访时间';

CREATE TABLE "register_config" (
  "id" BIGINT NOT NULL,
  "allow_register" BOOLEAN NOT NULL DEFAULT TRUE,
  "need_review" BOOLEAN NOT NULL DEFAULT TRUE,
  "trial_days" SMALLINT NOT NULL DEFAULT 14,
  "config_json" JSON DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "register_config" IS '注册策略配置表（单行配置）';
COMMENT ON COLUMN "register_config"."allow_register" IS '是否开放注册';
COMMENT ON COLUMN "register_config"."need_review" IS '新注册是否需企业审核';
COMMENT ON COLUMN "register_config"."trial_days" IS '试用天数';
```

### 11.3 billing\_db（billing-service）：payment\_config 支付配置表

```sql
CREATE TABLE "payment_config" (
  "id" BIGINT NOT NULL,
  "pay_channel" SMALLINT NOT NULL,
  "merchant_id" VARCHAR(64) DEFAULT NULL,
  "config_json" JSON NOT NULL,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_payment_config_channel CHECK ("pay_channel" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_payment_config_channel" UNIQUE ("pay_channel")
);
COMMENT ON TABLE "payment_config" IS '支付配置表';
COMMENT ON COLUMN "payment_config"."pay_channel" IS '支付渠道码：1-支付宝、2-微信、3-对公转账';
COMMENT ON COLUMN "payment_config"."merchant_id" IS '商户号';
COMMENT ON COLUMN "payment_config"."config_json" IS '商户参数（加密存储，JSON）';
```

### 11.4 customer\_db（customer-service）：quick\_reply / skill\_group\_member / csat\_record / customer\_order

```sql
CREATE TABLE "quick_reply" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "title" VARCHAR(64) NOT NULL,
  "content" VARCHAR(512) NOT NULL,
  "category" VARCHAR(32) DEFAULT NULL,
  "sort_no" INT NOT NULL DEFAULT 0,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "quick_reply" IS '快捷回复话术表';
COMMENT ON COLUMN "quick_reply"."title" IS '话术标题（如：问候语）';
COMMENT ON COLUMN "quick_reply"."content" IS '话术内容';
COMMENT ON COLUMN "quick_reply"."category" IS '分组（如：售前/售后/致歉）';
CREATE INDEX "idx_quick_reply_tenant_sort" ON "quick_reply" ("tenant_code", "sort_no");

CREATE TABLE "skill_group_member" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "skill_group_id" BIGINT NOT NULL,
  "user_id" BIGINT NOT NULL,
  "is_leader" BOOLEAN NOT NULL DEFAULT FALSE,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_skill_group_member_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_skill_group_member" UNIQUE ("skill_group_id", "user_id")
);
COMMENT ON TABLE "skill_group_member" IS '技能组坐席绑定表';
COMMENT ON COLUMN "skill_group_member"."skill_group_id" IS '技能组ID';
COMMENT ON COLUMN "skill_group_member"."user_id" IS '坐席用户ID';
COMMENT ON COLUMN "skill_group_member"."is_leader" IS '是否组长';
COMMENT ON COLUMN "skill_group_member"."status" IS '状态码：1-在组、2-已移出';
CREATE INDEX "idx_skill_group_member_tenant" ON "skill_group_member" ("tenant_code", "skill_group_id");

CREATE TABLE "csat_record" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "score" SMALLINT NOT NULL,
  "feedback" VARCHAR(512) DEFAULT NULL,
  "evaluate_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_csat_score CHECK ("score" BETWEEN 1 AND 5),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_csat_session" UNIQUE ("tenant_code", "session_id")
);
COMMENT ON TABLE "csat_record" IS '满意度评价明细表';
COMMENT ON COLUMN "csat_record"."session_id" IS '会话ID';
COMMENT ON COLUMN "csat_record"."score" IS '评分 1-5';
COMMENT ON COLUMN "csat_record"."feedback" IS '评价内容';
CREATE INDEX "idx_csat_tenant_time" ON "csat_record" ("tenant_code", "evaluate_time");

CREATE TABLE "customer_order" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_id" BIGINT NOT NULL,
  "order_no" VARCHAR(40) NOT NULL,
  "product_name" VARCHAR(128) DEFAULT NULL,
  "product_file_id" BIGINT DEFAULT NULL,
  "amount" NUMERIC(12,2) NOT NULL DEFAULT 0,
  "order_status" SMALLINT NOT NULL DEFAULT 1,
  "order_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_order_status CHECK ("order_status" IN (1,2,3,4,5)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_customer_order_no" UNIQUE ("tenant_code", "order_no")
);
COMMENT ON TABLE "customer_order" IS '客户订单表';
COMMENT ON COLUMN "customer_order"."customer_id" IS '客户ID';
COMMENT ON COLUMN "customer_order"."order_no" IS '订单号';
COMMENT ON COLUMN "customer_order"."product_name" IS '商品名称';
COMMENT ON COLUMN "customer_order"."product_file_id" IS '商品图文件ID';
COMMENT ON COLUMN "customer_order"."amount" IS '订单金额';
COMMENT ON COLUMN "customer_order"."order_status" IS '状态码：1-待付款、2-已付款、3-已发货、4-已完成、5-已退款';
COMMENT ON COLUMN "customer_order"."order_time" IS '下单时间';
CREATE INDEX "idx_customer_order_tenant_customer" ON "customer_order" ("tenant_code", "customer_id");

CREATE TABLE "tenant_config" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "config_type" SMALLINT NOT NULL DEFAULT 1,
  "config_json" JSON NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_config_type CHECK ("config_type" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_config_type" UNIQUE ("tenant_code", "config_type")
);
COMMENT ON TABLE "tenant_config" IS '租户级配置表（系统设置）';
COMMENT ON COLUMN "tenant_config"."config_type" IS '配置类型码：1-通知偏好、2-自动化工作流、3-安全设置';
COMMENT ON COLUMN "tenant_config"."config_json" IS '配置内容（JSON）';
```

---

## 12\. 索引与 SQL 规范要点

1. **复合索引列序** ：查询先按租户过滤， `(tenant_code, 业务条件)` 是标配；
2. **覆盖索引** ：高频列表页（会话、工单、审计）用覆盖索引避免回表；
3. **禁止** ：索引列上做运算/函数、隐式类型转换（ `where tenant_code = 10086` 与 varchar 不匹配）；
4. **分页** ：深分页用游标/子查询，禁止大 offset；
5. **text 检索** ：全文走 OpenSearch，数据库只存原文；
6. **状态枚举** ：用 VARCHAR 存语义化枚举（如 `ACTIVE` ），不存魔法数字；
7. **时间字段** ：一律 TIMESTAMP，避免时区歧义（应用层统一 Asia/Shanghai 展示）。

---

## 13\. 数据量评估与分区

### 12.1 容量测算（500 家企业场景）

假设模型：小型企业 350 家（日均 30 会话）+ 中型 100 家（日均 200）+ 大型 50 家（日均 1,000），日均总会话约 **8 万次** 。

| 数据 | 日均新增 | 月新增 | 年新增 |
| --- | --- | --- | --- |
| session\_message（约 80 万条/日） | ~0.8GB | ~24GB | ~290GB |
| session（约 8 万条/日） | ~40MB | ~1.2GB | ~14GB |
| audit\_log（约 20 万条/日） | ~100MB | ~3GB | ~36GB |
| ai\_usage\_record（约 24 万条/日） | ~72MB | ~2GB | ~24GB |
| qa\_task（约 8 万条/日） | ~24MB | ~0.7GB | ~8GB |
| 其他业务表 | 可忽略 | — | — |
| **合计** | **~1GB/日** | **~31GB/月** | **~370GB/年** （不含索引与备份） |

**结论** ：500 家规模 **不需要分库分表** ，需要「按月分区 + 冷热归档 + 读写分离」。PostgreSQL 使用原生分区表（PARTITION BY RANGE），对应用透明，无需像 MySQL 那样手动分表。

### 12.2 分库分表触发红线

| 红线 | 阈值 | 500 家是否触发 |
| --- | --- | --- |
| 单表行数 | 建议 < 5,000 万行（PG 无 MySQL 的 2,000 万经验红线，主要受索引与查询延迟影响） | 消息表月增 2,400 万 → **按月分区规避** |
| 单库容量 | \> 1~2TB | 不触发（热数据仅 90 天） |
| 写入 TPS | \> 5,000/s | 不触发（峰值约 1,000~2,000/s） |
| 企业规模 | \> 5,000 家 / 日消息 > 500 万 | 不触发 |

### 12.3 分区与归档策略

| 表 | 增长特点 | 策略 |
| --- | --- | --- |
| session\_message | 增长最快 | 按月分区 `PARTITION BY RANGE (create_time)` ，按租户哈希可选 |
| session | 中等 | 按月分区，90 天后归档 |
| audit\_log | 只追加 | 按月分区，保留按合规要求（≥ 180 天） |
| ai\_usage\_record | 增长快 | 按日聚合表 + 明细按周归档 |
| 其余业务表 | 慢 | 单表 + 索引即可 |

**归档** ：热数据 90 天在主库，冷数据归档 OSS/独立库，检索走 OpenSearch（按租户过滤）。

---

## 14\. 数据生命周期与保留策略

| 数据 | 保留策略 | 说明 |
| --- | --- | --- |
| 会话/消息 | 热数据 90 天主库，历史归档冷存储 | 检索走 OpenSearch，带租户过滤 |
| 审计日志 | ≥ 180 天（合规要求），可延长 | 只追加，不更新不删除 |
| AI 计量 | 明细按周归档，日聚合表保留 | 支撑成本看板 |
| 客户/工单/知识 | 长期保留（客户存活期） | 逻辑删除，可恢复 |
| 已删除租户 | 按协议物理删除业务数据 | 审计数据保留防追溯 |

**归档流程** ：按月分区 → 90 天到期 → DETACH/删除分区 → 迁移至冷库/OSS → OpenSearch 建索引（带租户）→ 归档任务按租户分片、失败隔离。

---

## 15\. 数据库变更规范

1. 所有变更走迁移脚本（Flyway/Liquibase），禁止手工改库；
2. 每次发版一个迁移脚本，脚本幂等（ `IF NOT EXISTS` ）；
3. DDL 变更需评审：加列锁表影响、索引大小、存量数据回填；
4. 大表变更分批执行（pt-osc 等方式），避免长时间锁表；
5. 变更前后执行对比测试与回归。

---

## 16\. 字段枚举字典

> 存储值统一为 SMALLINT 码值（1、2、3…），数据库层通过 CHECK 约束保证合法性；英文常量为代码层枚举类命名，中文含义供评审阅读。标识符类字段（VARCHAR）不在此列，单独说明。新增枚举只需追加码值并登记本表，无需修改表结构。

### 16.1 租户与账号域

| 表.字段 | 码值（存储） | 英文常量 | 中文含义 |
| --- | --- | --- | --- |
| enterprise.status | 1 / 2 / 3 / 4 | PENDING / ACTIVE / REJECTED / CANCELLED | 1-待审核、2-正常、3-已驳回、4-已注销 |
| tenant.status | 1 / 2 / 3 / 4 | ACTIVE / FROZEN / EXPIRED / CANCELLED | 1-正常、2-冻结、3-到期、4-注销 |
| tenant.isolation\_mode | 1 / 2 / 3 | SHARED / SCHEMA / INSTANCE | 1-共享库、2-独立Schema、3-独立实例 |
| enterprise\_review.status | 1 / 2 / 3 | PENDING / APPROVED / REJECTED | 1-待审核、2-已通过、3-已驳回 |
| sys\_user.user\_type | 1 / 2 | PLATFORM / ENTERPRISE | 1-平台账号、2-企业账号 |
| sys\_user.status | 1 / 2 / 3 | ACTIVE / DISABLED / LOCKED | 1-正常、2-停用、3-锁定 |
| tenant\_member.status | 1 / 2 | ACTIVE / LEFT | 1-在职、2-已退出 |
| sys\_role.role\_type | 1 / 2 | PLATFORM / ENTERPRISE | 1-平台角色、2-企业角色 |
| sys\_permission.perm\_type | 1 / 2 / 3 | MENU / BUTTON / API | 1-菜单、2-按钮、3-接口 |
| login\_log.login\_type | 1 / 2 / 3 | PASSWORD / SSO\_WECHAT / SSO\_FEISHU | 1-密码、2-企业微信SSO、3-飞书SSO |
| login\_log.result | 1 / 2 / 3 | SUCCESS / FAIL / ABNORMAL | 1-成功、2-失败、3-异常 |
| member\_invite.status | 1 / 2 / 3 | VALID / USED / EXPIRED | 1-有效、2-已使用、3-已失效 |

### 16.2 渠道与配置域

| 表.字段 | 码值（存储） | 英文常量 | 中文含义 |
| --- | --- | --- | --- |
| channel.channel\_type | 1 / 2 / 3 / 4 / 5 / 6 / 7 | WEB / WECHAT / MINIAPP / APP / PHONE / EMAIL / CUSTOM | 1-网站、2-公众号、3-小程序、4-App、5-400热线、6-邮件、7-自定义 |
| channel.status | 1 / 2 | ON / OFF | 1-启用、2-停用 |
| channel.stage | 1 / 2 / 3 | CONFIG / TEST / ONLINE | 1-未配置、2-待上线、3-运行中 |
| channel\_key.key\_type | 1 / 2 | CHANNEL / MASTER | 1-渠道密钥、2-租户主密钥 |
| channel\_key.status | 1 / 2 | ACTIVE / REVOKED | 1-生效、2-已吊销 |
| channel\_test\_log.test\_result | 1 / 2 | PASS / FAIL | 1-通过、2-失败 |

### 16.3 客服业务域

| 表.字段 | 码值（存储） | 英文常量 | 中文含义 |
| --- | --- | --- | --- |
| session.status | 1 / 2 / 3 / 4 | QUEUED / BOT / AGENT / CLOSED | 1-排队中、2-机器人接待、3-人工接待、4-已结束 |
| session.source | —（VARCHAR 标识符） | WECHAT / APP / WEB | 微信 / App / 网站等渠道 |
| session\_message.msg\_type | 1 / 2 / 3 / 4 / 5 | TEXT / IMAGE / CARD / EVENT / SYSTEM | 1-文本、2-图片、3-卡片、4-事件、5-系统 |
| session\_message.sender\_type | 1 / 2 / 3 / 4 | CUSTOMER / AGENT / BOT / SYSTEM | 1-客户、2-坐席、3-机器人、4-系统 |
| session\_message.status | 1 / 2 / 3 / 4 | SENT / DELIVERED / READ / FAILED | 1-已发送、2-已送达、3-已读、4-失败 |
| session\_event.event\_type | 1 / 2 / 3 / 4 / 5 | TRANSFER / ESCALATE / ASSIGN / CLOSE / TIMEOUT | 1-转接、2-升级、3-分配、4-关闭、5-超时 |
| customer.level | 1 / 2 / 3 / 4 / 5 | NORMAL / SILVER / GOLD / PLATINUM / ENTERPRISE | 1-普通、2-银卡、3-金卡、4-铂金、5-企业 |
| customer.sentiment | 1 / 2 / 3 | POSITIVE / NEGATIVE / NEUTRAL | 1-正面、2-负面、3-中性 |
| ticket.ticket\_type | 1 / 2 | INTERNAL / SUPPORT | 1-企业内部工单、2-平台支持工单 |
| ticket.priority | 1 / 2 / 3 / 4 | LOW / MEDIUM / HIGH / URGENT | 1-低、2-中、3-高、4-紧急 |
| ticket.status | 1 / 2 / 3 / 4 | TODO / DOING / DONE / CLOSED | 1-待处理、2-处理中、3-已解决、4-已关闭 |
| ticket.sla\_state | 1 / 2 / 3 | OK / WARN / OVERDUE | 1-正常、2-预警、3-超时 |
| ticket\_event.event\_type | 1 / 2 / 3 / 4 / 5 / 6 | CREATE / ASSIGN / REPLY / ESCALATE / CLOSE / REOPEN | 1-创建、2-分配、3-回复、4-升级、5-关闭、6-重开 |
| notification.notify\_type | 1 / 2 / 3 / 4 / 5 / 6 | SYSTEM / TICKET / AUDIT / QA / ANNOUNCE / BILLING | 1-系统、2-工单、3-审核、4-质检、5-公告、6-账单 |
| notification.target\_type | 1 / 2 | USER / TENANT | 1-用户、2-企业 |
| skill\_group\_member.status | 1 / 2 | ACTIVE / REMOVED | 1-在组、2-已移出 |
| customer\_order.order\_status | 1 / 2 / 3 / 4 / 5 | PENDING / PAID / SHIPPED / COMPLETED / REFUNDED | 1-待付款、2-已付款、3-已发货、4-已完成、5-已退款 |
| tenant\_config.config\_type | 1 / 2 / 3 | NOTIFY / AUTOMATION / SECURITY | 1-通知偏好、2-自动化工作流、3-安全设置 |

### 16.4 知识库 / 机器人 AI / 质检域

| 表.字段 | 码值（存储） | 英文常量 | 中文含义 |
| --- | --- | --- | --- |
| kb\_document.source\_type | 1 / 2 / 3 | MANUAL / IMPORT / GENERATED | 1-手工、2-导入、3-AI生成 |
| kb\_document.status | 1 / 2 / 3 / 4 | DRAFT / AUDITING / PUBLISHED / OFFLINE | 1-草稿、2-审核中、3-已发布、4-已下线 |
| kb\_audit.result | 1 / 2 | PASS / REJECT | 1-通过、2-驳回 |
| bot\_intent.status | 1 / 2 | ON / OFF | 1-启用、2-停用 |
| bot\_model.provider | —（VARCHAR 标识符） | QWEN / DEEPSEEK | 千问 / DeepSeek等 |
| bot\_model.model\_type | 1 / 2 / 3 | CHAT / VISION / LIGHT | 1-对话、2-视觉、3-轻量 |
| bot\_version.train\_type | 1 / 2 | FULL / INCREMENT | 1-全量、2-增量 |
| bot\_version.status | 1 / 2 / 3 | TRAINING / ONLINE / ROLLBACK | 1-训练中、2-已上线、3-已回滚 |
| training\_job.job\_type | 1 / 2 | INTENT / MODEL | 1-意图训练、2-模型训练 |
| training\_job.status | 1 / 2 / 3 / 4 | PENDING / RUNNING / SUCCESS / FAILED | 1-等待中、2-执行中、3-成功、4-失败 |
| ai\_usage\_record.scene | 1 / 2 / 3 | CHAT / QA / VISION | 1-对话、2-质检、3-视觉识别 |
| qa\_task.risk\_level | 1 / 2 / 3 | LOW / MEDIUM / HIGH | 1-低、2-中、3-高 |
| qa\_task.status | 1 / 2 / 3 | PENDING / PASSED / REJECTED | 1-待复核、2-已通过、3-已驳回 |
| qa\_rule.rule\_type | 1 / 2 / 3 / 4 | SENSITIVE / PROMISE / REQUIRED / EMOTION | 1-敏感词、2-承诺规范、3-必答项、4-情绪识别 |
| qa\_review.action | 1 / 2 / 3 | PASS / REJECT / REOPEN | 1-通过、2-驳回、3-重检 |

### 16.5 计费 / 平台域

| 表.字段 | 码值（存储） | 英文常量 | 中文含义 |
| --- | --- | --- | --- |
| plan.plan\_code | —（VARCHAR 标识符） | BASIC / PRO / ENTERPRISE | 标准版 / 专业版 / 企业版 |
| billing\_order.cycle | 1 / 2 | YEAR / MONTH | 1-年付、2-月付 |
| billing\_order.status | 1 / 2 / 3 / 4 | PENDING / PAID / CANCELLED / REFUNDED | 1-待支付、2-已支付、3-已取消、4-已退款 |
| billing\_order.pay\_method | 1 / 2 / 3 | ALIPAY / WECHAT / TRANSFER | 1-支付宝、2-微信、3-对公转账 |
| billing\_order.invoice\_status | 1 / 2 / 3 | NONE / APPLIED / ISSUED | 1-未申请、2-已申请、3-已开具 |
| payment\_record.status | 1 / 2 / 3 | PENDING / SUCCESS / FAILED | 1-处理中、2-成功、3-失败 |
| invoice\_record.invoice\_type | 1 / 2 | ELECTRONIC / VAT | 1-电子发票、2-增值税专用发票 |
| invoice\_record.status | 1 / 2 / 3 | APPLIED / ISSUED / REJECTED | 1-已申请、2-已开具、3-已驳回 |
| site\_config.section\_key | —（VARCHAR 标识符） | HERO / FEATURES / PRICING / FAQ | 首屏 / 功能 / 定价 / 常见问题等 |
| site\_config.status | 1 / 2 | DRAFT / PUBLISHED | 1-草稿、2-已发布 |
| announcement.announce\_type | 1 / 2 / 3 | MAINTENANCE / FEATURE / OTHER | 1-维护通知、2-功能公告、3-其他 |
| announcement.status | 1 / 2 / 3 | DRAFT / PUBLISHED / OFFLINE | 1-草稿、2-已发布、3-已下线 |
| audit\_log.result | 1 / 2 | SUCCESS / FAIL | 1-成功、2-失败 |
| file\_meta.biz\_type | 1 / 2 / 3 / 4 | AVATAR / ATTACH / EXPORT / INVOICE | 1-头像、2-附件、3-导出、4-发票 |
| api\_key.status | 1 / 2 | ACTIVE / REVOKED | 1-生效、2-已吊销 |
| data\_export.export\_type | 1 / 2 / 3 / 4 | SESSION / CUSTOMER / REPORT / AUDIT | 1-会话、2-客户、3-报表、4-审计 |
| data\_export.status | 1 / 2 / 3 | PENDING / APPROVED / REJECTED | 1-待审批、2-已通过、3-已驳回 |
| customer\_success.renew\_risk | 1 / 2 / 3 | LOW / MEDIUM / HIGH | 1-低、2-中、3-高 |
| customer\_success.expand\_opportunity | 1 / 2 | YES / NO | 1-有、2-无 |
| payment\_config.pay\_channel | 1 / 2 / 3 | ALIPAY / WECHAT / TRANSFER | 1-支付宝、2-微信、3-对公转账 |

![](data:image/png;base64,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)

扫码加入星球

查看更多优质内容

https://wx.zsxq.com/mweb/views/joingroup/join\_group.html?group\_id=28851182188851