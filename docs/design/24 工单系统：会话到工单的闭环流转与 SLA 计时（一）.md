---
title: "24 工单系统：会话到工单的闭环流转与 SLA 计时（一）"
source: "https://articles.zsxq.com/id_2159ev8w3mji.html"
author:
  - "[[苏三]]"
published:
created: 2026-09-25
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

## 一、项目概述

### 1.1 功能范围

前面几篇里，客户能发文字、发图，机器人能听、能查、能想、能画。但**有些事一轮对话解决不了**：退款要等财务、补发要走仓库、渠道接入失败只有平台能改。客户说一句"我等你消息"，人就散了——没有工单，这件事就没人接着跟。这一篇把"接住 → 跟进 → 闭环"这条链路补齐。

1.  **工单中心（企业侧）**：会话里解决不了的事一键转工单（自动带上来源会话、客户、最近对话摘要），也能手工建单（客户名 + 来源渠道）；列表支持状态/优先级/SLA/分类/处理人筛选 + 服务端分页 + 导出 CSV，还能切到**看板**按状态拖拽流转；

2.  **两段 SLA**：首次响应（客户多久有人理）+ 解决（多久真正办完），按优先级配置四档规则（紧急 15 分钟 / 2 小时、高 30 分钟 / 4 小时、中 60 分钟 / 8 小时、低 4 小时 / 2 天）；**建单时把规则快照进工单**，之后改规则不影响存量单；

3.  **超时不是"变个颜色"**：每 30 秒扫一次，正常 → 即将超时 → 已超时，写一条流转记录（留痕）、推长连接提醒、写进消息中心；没人认领就发给全租户坐席；

4.  **闭环动作**：认领 / 转派 / 回复（对客户可见的回复才算首次响应）/ 待客户确认 / 已解决 / 已关闭 / 重开 / **升级**（提一档优先级，但不重算 SLA 截止时间——否则"一升级就不超时"，考核就废了）；

5.  **平台支持工单**：企业提给平台的问题（渠道接入、计费、平台故障）走同一个工单表，靠 `ticket_type=2` 区分；企业侧只读（可以补充说明），平台侧在「支持工单」里跨租户认领与处理，平台回复会写进企业消息中心；

6.  **消息中心**：第 4 篇设计里就有 `notification` / `notification_read` 两张表，一直没接代码；这一篇把它接上，工单 SLA 预警、分派、升级、平台回复都进消息中心（右上角铃铛带未读数）。

另外，这一篇顺手把联调里抓出来的真问题都修了（图片"一直上传中"的响应式坑、新接口前缀忘了加网关路由、`column "source_channel" does not exist`），都在第七章。

### 1.2 协议与数据模型变化

HTTP（企业侧，`userType=2`，租户从登录态取）：

|         |                                                                          |                                                          |
|---------|--------------------------------------------------------------------------|----------------------------------------------------------|
| 方法    | 路径                                                                     | 说明                                                     |
| GET     | /api/customer/tickets                                                    | 工单列表（筛选 + 分页，返回 total/list）                 |
| GET     | /api/customer/tickets/overview                                           | 看板数字（待处理/处理中/待确认/超时/预警/我的/今日解决） |
| GET     | /api/customer/tickets/access                                             | 当前角色能干什么（前端据此显隐按钮）                     |
| GET     | /api/customer/tickets/{ticketNo}                                         | 工单详情 + 流转时间线                                    |
| POST    | /api/customer/tickets                                                    | 建单（带 sessionNo 就是会话转单）                        |
| POST    | /api/customer/tickets/platform                                           | 提交给平台支持（`ticket_type=2`）                        |
| POST    | /api/customer/tickets/{ticketNo}/assign·reply·status·escalate·supplement | 认领转派 / 回复 / 流转 / 升级 / 企业补充说明             |
| GET·PUT | /api/customer/tickets/sla-rules                                          | SLA 规则（**仅企业管理员**）                             |
| POST    | /api/customer/tickets/scan-sla                                           | 手动扫一次超时（仅管理员）                               |
| GET     | /api/customer/tickets/export                                             | 导出 CSV（带 BOM，Excel 不乱码）                         |

HTTP（平台侧，`userType=1`）：

|      |                                                              |                                             |
|------|--------------------------------------------------------------|---------------------------------------------|
| 方法 | 路径                                                         | 说明                                        |
| GET  | /api/platform/tickets                                        | 跨租户支持工单列表（租户号可前缀筛 + 分页） |
| GET  | /api/platform/tickets/overview                               | 平台看板数字                                |
| GET  | /api/platform/tickets/{ticketNo}?tenant=                     | 详情（工单号只在租户内唯一，必须带租户号）  |
| POST | /api/platform/tickets/{ticketNo}/claim·reply·status·escalate | 认领 / 回复 / 流转 / 升级                   |
| GET  | /api/platform/tickets/export                                 | 导出 CSV（带租户号）                        |

消息中心（企业侧）：`GET /api/customer/notifications`、`GET /unread-count`、`POST /{id}/read`、`POST /read-all`。

数据库：`ticket` / `ticket_event` 从"能存"升级成"能跑流程"，`ticket_sla_rule` 是新增的规则表。完整改动见第三章（含启动自检怎么自动补结构）。

## 二、设计：工单是"客户的问题被接住"

### 2.1 三个方向，别混成一个

|                  |                                 |                                        |                                                    |          |
|------------------|---------------------------------|----------------------------------------|----------------------------------------------------|----------|
| 方向             | 谁提                            | 谁处理                                 | 数据标记                                           | 现状     |
| **企业内部工单** | 坐席代客户提（会话转单 / 手建） | 企业同事（主管 / 高级客服 / 客服专员） | `ticket_type=1`，带 `session_no` / `customer_name` | 本篇实现 |
| **平台支持工单** | 企业成员                        | 平台运营 / 平台管理员                  | `ticket_type=2`，企业侧只读                        | 本篇实现 |
| 客户自助工单     | C 端客户自己                    | 企业客服                               | `source=2 客户自助`（常量已留）                    | 预留未做 |

为什么不让 C 端客户直接提工单：客户没有账号体系，也没有"工单列表"可看；让他们提单，反而会出现"谁在跟、跟到哪了"说不清。所以客户的诉求从会话进来，**要不要转工单、转给谁，是坐席判断的**；客户只在回复里看到进展。

### 2.2 两段 SLA：一段根本管不住"回了不管"

只卡一个总时限的话，"回了句正在处理然后一直不动"和"压根没人理"会被算成同一件事。所以拆成两段：

-   **首次响应**：客户等多久有人理。口径是**第一条对客户可见的回复**（内部备注不算，客户还在等）；

-   **解决**：多久真正办完，口径是标记"已解决 / 已关闭"的时间。

默认四档（可在「SLA 规则」里改，**仅企业管理员**）：低 240/2880、中 60/480、高 30/240、紧急 15/120（分钟）。**建单时把时长快照进工单**（`sla_first_minutes` / `sla_resolve_minutes`）——规则后来改宽了，存量工单的考核口径不会跟着变，否则"改配置就不用背超时"。

判定口径（\[TicketService.computeSlaState\]）：

1.  已经办结（已解决 / 已关闭）→ 正常，不再挂红标（**曾经超时**的证据留在流转记录里）；

2.  首次响应没做且过了截止 → 已超时；解决时限过了 → 已超时；

3.  都没过：看"下一个要赶的截止时间"还剩多少，剩得比 **窗口的 20%（且不少于 15 分钟）** 还少 → 即将超时。

### 2.3 提醒要"追着人跑"

超时只变颜色是没人看的。所以每条提醒走三条路：**长连接推送**（当时在线的人秒到）、**消息中心落库**（回头翻得到，谁读谁写已读）、**菜单角标**（有没有待办一眼看到）；工单没人认领时，提醒发给**全租户坐席**——没主的单不能被"没人负责"吞掉。

### 2.4 权限：租户内按角色收敛

|                                |        |                          |              |                      |
|--------------------------------|--------|--------------------------|--------------|----------------------|
| 角色                           | 看工单 | 建单/认领/转派/回复/流转 | 关闭别人的单 | SLA 规则、手动扫超时 |
| 企业管理员 ADMIN               | ✅      | ✅                        | ✅            | ✅                    |
| 客服主管 / 高级客服 / 客服专员 | ✅      | ✅                        | ❌            | ❌                    |
| 质检专员 / AI运营（只读）      | ✅      | ❌                        | ❌            | ❌                    |

角色不从 JWT 里取（改了角色要等令牌过期才生效），而是 customer-service 通过内部接口反查 user-service（`/api/user/internal/users/{userId}/roles`）。**问不到角色时**：日常作业照常放行（不能让内部接口把客服堵死），但管理类动作保守拒绝——多拦一次能解释清楚，放错一次就是配置被随手改了。

## 三、数据库准备

### 3.1 工单增量脚本

工单表和流转记录表在基础建表脚本里就有（第 4 篇设计的），这一篇要做的是"让它跑起来"：补列、放开取值、加 SLA 规则表。**幂等**（`CREATE IF NOT EXISTS` + `ADD COLUMN IF NOT EXISTS` + 约束先删后建），可重复执行：

### 文件：schema/customer\_db\_ticket.sql

客户库增量脚本：给 ticket 补上分类/来源/来源会话号/客户名/处理人姓名/创建人姓名/来源渠道/两段 SLA 快照与时间/预警标记；状态从 4 档放开到 5 档（多了"待客户确认"）；ticket\_event 补操作人姓名、状态前后、是否对客户可见与"升级/SLA 预警"两个事件类型；新增 ticket\_sla\_rule（按优先级配首次响应与解决时长）。

``` code-block-container
-- 云梯智能客服平台 · customer_db 工单中心增量脚本（PostgreSQL 17）
-- 用途：工单从"会话转过来的一张纸"变成"有闭环、有 SLA 的工单"
--   1. ticket 补：分类 / 来源会话号 / 处理人姓名 / 客户姓名 / 双段 SLA（首次响应 + 解决）/ 预警标记；
--   2. ticket 放开状态取值：加上"待客户确认"；
--   3. ticket_event 补：操作人姓名、状态前后、是否对客户可见；放开事件类型（加"状态变更"和"SLA 预警"）；
--   4. 新增 ticket_sla_rule：按优先级配置"首次响应多久、解决多久"。
-- 幂等：可重复执行（CREATE IF NOT EXISTS + ADD COLUMN IF NOT EXISTS + 约束先删后建）。
--
-- 为什么约束要"先删后建"：工单状态原来只允许 1~4，这一篇多了"待客户确认"，
-- 直接插入会被 ck_ticket_status 挡住——和第 23 篇聊天图片的 biz_type=5 是同一种坑，
-- 所以 SchemaGuard 里也把这两个约束登记进了"必需约束取值"自检。

-- ---------- 1. ticket：补齐工单中心要用的列 ----------
CREATE TABLE IF NOT EXISTS "ticket" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "ticket_no" VARCHAR(40) NOT NULL,
  "ticket_type" SMALLINT NOT NULL DEFAULT 1,
  "category" SMALLINT NOT NULL DEFAULT 5,
  "title" VARCHAR(128) NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "customer_name" VARCHAR(64) DEFAULT NULL,
  "desc" TEXT,
  "priority" SMALLINT NOT NULL DEFAULT 2,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "source" SMALLINT NOT NULL DEFAULT 3,
  "session_no" VARCHAR(40) DEFAULT NULL,
  "source_session_id" BIGINT DEFAULT NULL,
  "assignee_id" BIGINT DEFAULT NULL,
  "assignee_name" VARCHAR(64) DEFAULT NULL,
  "group_id" BIGINT DEFAULT NULL,
  "sla_first_minutes" INT NOT NULL DEFAULT 0,
  "sla_resolve_minutes" INT NOT NULL DEFAULT 0,
  "first_response_due" TIMESTAMP DEFAULT NULL,
  "first_response_at" TIMESTAMP DEFAULT NULL,
  "resolve_due" TIMESTAMP DEFAULT NULL,
  "resolved_at" TIMESTAMP DEFAULT NULL,
  "sla_deadline" TIMESTAMP DEFAULT NULL,
  "sla_state" SMALLINT NOT NULL DEFAULT 1,
  "sla_alerted" BOOLEAN NOT NULL DEFAULT FALSE,
  "close_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "creator_name" VARCHAR(64) DEFAULT NULL,
  "source_channel" SMALLINT DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_ticket_ticket_type CHECK ("ticket_type" IN (1,2)),
  CONSTRAINT ck_ticket_category CHECK ("category" IN (1,2,3,4,5)),
  CONSTRAINT ck_ticket_priority CHECK ("priority" IN (1,2,3,4)),
  CONSTRAINT ck_ticket_status CHECK ("status" IN (1,2,3,4,5)),
  CONSTRAINT ck_ticket_source CHECK ("source" IN (1,2,3)),
  CONSTRAINT ck_ticket_sla_state CHECK ("sla_state" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_ticket_no" UNIQUE ("tenant_code", "ticket_no")
);

ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "category" SMALLINT NOT NULL DEFAULT 5;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "customer_name" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "source" SMALLINT NOT NULL DEFAULT 3;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "session_no" VARCHAR(40) DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "source_session_id" BIGINT DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "assignee_name" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "sla_first_minutes" INT NOT NULL DEFAULT 0;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "sla_resolve_minutes" INT NOT NULL DEFAULT 0;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "first_response_due" TIMESTAMP DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "first_response_at" TIMESTAMP DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "resolve_due" TIMESTAMP DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "resolved_at" TIMESTAMP DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "sla_alerted" BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "creator_name" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "ticket" ADD COLUMN IF NOT EXISTS "source_channel" SMALLINT DEFAULT NULL;

-- 来源渠道（客户从哪来：在线会话 / 电话 / 邮件 / 工单导入 / 其它）：和 source 不是一个维度——
-- source 说的是"这张工单怎么来的"，source_channel 说的是"客户从哪个渠道来的"
ALTER TABLE "ticket" DROP CONSTRAINT IF EXISTS "ck_ticket_source_channel";
ALTER TABLE "ticket"
    ADD CONSTRAINT "ck_ticket_source_channel" CHECK ("source_channel" IS NULL OR "source_channel" IN (1,2,3,4,5));

-- 状态多了"待客户确认"（3），约束要跟着放开
ALTER TABLE "ticket" DROP CONSTRAINT IF EXISTS "ck_ticket_status";
ALTER TABLE "ticket"
    ADD CONSTRAINT "ck_ticket_status" CHECK ("status" IN (1,2,3,4,5));
ALTER TABLE "ticket" DROP CONSTRAINT IF EXISTS "ck_ticket_category";
ALTER TABLE "ticket"
    ADD CONSTRAINT "ck_ticket_category" CHECK ("category" IN (1,2,3,4,5));
ALTER TABLE "ticket" DROP CONSTRAINT IF EXISTS "ck_ticket_source";
ALTER TABLE "ticket"
    ADD CONSTRAINT "ck_ticket_source" CHECK ("source" IN (1,2,3));

CREATE INDEX IF NOT EXISTS "idx_ticket_tenant_status_time" ON "ticket" ("tenant_code", "status", "create_time");
CREATE INDEX IF NOT EXISTS "idx_ticket_tenant_assignee" ON "ticket" ("tenant_code", "assignee_id");
-- SLA 扫描就是按这个索引扫的：租户 + 还没超时的那些
CREATE INDEX IF NOT EXISTS "idx_ticket_tenant_sla" ON "ticket" ("tenant_code", "sla_state", "resolve_due");
CREATE INDEX IF NOT EXISTS "idx_ticket_tenant_session" ON "ticket" ("tenant_code", "session_no");

COMMENT ON TABLE "ticket" IS '工单表';
COMMENT ON COLUMN "ticket"."ticket_no" IS '工单号';
COMMENT ON COLUMN "ticket"."ticket_type" IS '类型码：1-企业内部工单、2-平台支持工单';
COMMENT ON COLUMN "ticket"."category" IS '业务分类码：1-订单、2-退款售后、3-物流、4-商品、5-其他';
COMMENT ON COLUMN "ticket"."title" IS '工单主题';
COMMENT ON COLUMN "ticket"."desc" IS '问题描述（会话转单时带最近聊天记录摘要）';
COMMENT ON COLUMN "ticket"."priority" IS '优先级码：1-低、2-中、3-高、4-紧急';
COMMENT ON COLUMN "ticket"."status" IS '状态码：1-待处理、2-处理中、3-待客户确认、4-已解决、5-已关闭';
COMMENT ON COLUMN "ticket"."source" IS '来源码：1-会话转单、2-客户自助、3-坐席新建';
COMMENT ON COLUMN "ticket"."session_no" IS '来源会话号（坐席可一键跳回会话看上下文）';
COMMENT ON COLUMN "ticket"."source_session_id" IS '来源会话ID';
COMMENT ON COLUMN "ticket"."assignee_id" IS '处理人ID';
COMMENT ON COLUMN "ticket"."assignee_name" IS '处理人姓名（冗余，列表直接显示）';
COMMENT ON COLUMN "ticket"."sla_first_minutes" IS '建单时快照的首次响应 SLA（分钟）';
COMMENT ON COLUMN "ticket"."sla_resolve_minutes" IS '建单时快照的解决 SLA（分钟）';
COMMENT ON COLUMN "ticket"."first_response_due" IS '首次响应截止时间';
COMMENT ON COLUMN "ticket"."first_response_at" IS '首次响应时间（第一条对客户可见的回复）';
COMMENT ON COLUMN "ticket"."resolve_due" IS '解决截止时间';
COMMENT ON COLUMN "ticket"."resolved_at" IS '解决时间';
COMMENT ON COLUMN "ticket"."sla_deadline" IS 'SLA 截止时间（旧的单段口径，等于 resolve_due，保留兼容）';
COMMENT ON COLUMN "ticket"."sla_state" IS 'SLA 状态码：1-正常、2-即将超时、3-已超时';
COMMENT ON COLUMN "ticket"."sla_alerted" IS '这一轮预警是否已经推送过（避免每 30 秒重复轰炸）';
COMMENT ON COLUMN "ticket"."close_time" IS '关闭时间';
COMMENT ON COLUMN "ticket"."creator_name" IS '创建人姓名（冗余，列表直接显示）';
COMMENT ON COLUMN "ticket"."source_channel" IS '来源渠道码：1-在线会话、2-电话热线、3-邮件、4-工单导入、5-其它';

-- ---------- 2. ticket_event：流转记录 ----------
CREATE TABLE IF NOT EXISTS "ticket_event" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "ticket_id" BIGINT NOT NULL,
  "event_type" SMALLINT NOT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "operator_name" VARCHAR(64) DEFAULT NULL,
  "from_status" SMALLINT DEFAULT NULL,
  "to_status" SMALLINT DEFAULT NULL,
  "visible_to_customer" BOOLEAN NOT NULL DEFAULT FALSE,
  "content" VARCHAR(512) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ticket_event_event_type CHECK ("event_type" IN (1,2,3,4,5,6,7,8)),
  PRIMARY KEY ("id")
);

ALTER TABLE "ticket_event" ADD COLUMN IF NOT EXISTS "operator_name" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "ticket_event" ADD COLUMN IF NOT EXISTS "from_status" SMALLINT DEFAULT NULL;
ALTER TABLE "ticket_event" ADD COLUMN IF NOT EXISTS "to_status" SMALLINT DEFAULT NULL;
ALTER TABLE "ticket_event" ADD COLUMN IF NOT EXISTS "visible_to_customer" BOOLEAN NOT NULL DEFAULT FALSE;

-- 事件类型多了"状态变更"（7）和"SLA 预警"（8）
ALTER TABLE "ticket_event" DROP CONSTRAINT IF EXISTS "ck_ticket_event_event_type";
ALTER TABLE "ticket_event"
    ADD CONSTRAINT "ck_ticket_event_event_type" CHECK ("event_type" IN (1,2,3,4,5,6,7,8));

CREATE INDEX IF NOT EXISTS "idx_ticket_event_tenant_ticket"
    ON "ticket_event" ("tenant_code", "ticket_id", "event_time");

COMMENT ON TABLE "ticket_event" IS '工单流转记录表';
COMMENT ON COLUMN "ticket_event"."event_type" IS '事件码：1-创建、2-分配、3-回复、4-升级、5-关闭、6-重开、7-状态变更、8-SLA预警';
COMMENT ON COLUMN "ticket_event"."operator_name" IS '操作人姓名（冗余，时间线直接显示）';
COMMENT ON COLUMN "ticket_event"."from_status" IS '变更前状态（状态流转类事件用）';
COMMENT ON COLUMN "ticket_event"."to_status" IS '变更后状态';
COMMENT ON COLUMN "ticket_event"."visible_to_customer" IS '这条回复是否对客户可见（false=内部备注）';
COMMENT ON COLUMN "ticket_event"."content" IS '事件内容 / 回复正文';

-- ---------- 3. ticket_sla_rule：按优先级配置 SLA ----------
CREATE TABLE IF NOT EXISTS "ticket_sla_rule" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "priority" SMALLINT NOT NULL,
  "first_response_minutes" INT NOT NULL,
  "resolve_minutes" INT NOT NULL,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_ticket_sla_rule_priority CHECK ("priority" IN (1,2,3,4)),
  CONSTRAINT ck_ticket_sla_rule_minutes CHECK ("first_response_minutes" > 0 AND "resolve_minutes" > 0),
  PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX IF NOT EXISTS "uk_ticket_sla_rule_tenant_priority"
    ON "ticket_sla_rule" ("tenant_code", "priority")
    WHERE "is_deleted" = FALSE;

COMMENT ON TABLE "ticket_sla_rule" IS '工单 SLA 规则（按优先级配置首次响应 / 解决时长）';
COMMENT ON COLUMN "ticket_sla_rule"."priority" IS '优先级码：1-低、2-中、3-高、4-紧急';
COMMENT ON COLUMN "ticket_sla_rule"."first_response_minutes" IS '首次响应时限（分钟）';
COMMENT ON COLUMN "ticket_sla_rule"."resolve_minutes" IS '解决时限（分钟）';
```

**为什么约束要"先删后建"**：工单状态原来只允许 1\~4，这一篇多了"待客户确认"，直接插入会被 `ck_ticket_status` 挡住——和第 23 篇聊天图片的 `biz_type=5` 是同一种坑。

### 文件：schema/customer\_db.sql

全量建表脚本同步：工单的新列、新约束、来源渠道，以及新的 SLA 规则表（新库直接建对）

``` code-block-container
-- 云梯智能客服平台 · customer_db 建表脚本（PostgreSQL 17）
-- 由 database-design.md v4.5 生成

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
  "allowed_origins" VARCHAR(512) DEFAULT NULL,
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
COMMENT ON COLUMN "channel"."allowed_origins" IS '访客接入域名白名单（逗号分隔，支持 *.example.com；为空表示不限制）';

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

CREATE TABLE "skill_group" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "description" VARCHAR(255) DEFAULT NULL,
  "is_default" BOOLEAN NOT NULL DEFAULT FALSE,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "overflow_after_seconds" INTEGER NOT NULL DEFAULT 60,
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

CREATE TABLE "agent_status" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "agent_id" BIGINT NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "max_concurrency" SMALLINT NOT NULL DEFAULT 5,
  "is_connected" BOOLEAN NOT NULL DEFAULT FALSE,
  "status_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_agent_status_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_agent_status" UNIQUE ("tenant_code", "agent_id")
);
COMMENT ON TABLE "agent_status" IS '坐席状态表';
COMMENT ON COLUMN "agent_status"."agent_id" IS '坐席用户ID';
COMMENT ON COLUMN "agent_status"."status" IS '状态码：1-在线、2-忙碌、3-小休';
COMMENT ON COLUMN "agent_status"."max_concurrency" IS '最多同时接待的会话数';
COMMENT ON COLUMN "agent_status"."is_connected" IS '长连接是否在线（由实时网关维护，路由只分配在线坐席）';
COMMENT ON COLUMN "agent_status"."status_time" IS '状态变更时间（同负载时优先分配给更久没换状态的坐席）';
CREATE INDEX "idx_agent_status_tenant_status" ON "agent_status" ("tenant_code", "status");

CREATE INDEX "idx_session_queue"
    ON "session" ("tenant_code", "start_time")
    WHERE "agent_id" IS NULL AND "is_deleted" = FALSE;

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
  "bot_transfer_reason" VARCHAR(255) DEFAULT NULL,
  "source" VARCHAR(20) DEFAULT NULL,
  "start_time" TIMESTAMP NOT NULL,
  "end_time" TIMESTAMP DEFAULT NULL,
  "csat_score" SMALLINT DEFAULT NULL,
  "last_msg_seq" BIGINT NOT NULL DEFAULT 0,
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
COMMENT ON COLUMN "session"."last_msg_seq" IS '会话已分配的最大消息序号';
COMMENT ON COLUMN "session"."customer_id" IS '客户ID';
COMMENT ON COLUMN "session"."status" IS '状态码：1-排队中、2-机器人接待、3-人工接待、4-已结束';
COMMENT ON COLUMN "session"."skill_group_id" IS '技能组ID';
COMMENT ON COLUMN "session"."agent_id" IS '当前坐席用户ID';
COMMENT ON COLUMN "session"."intent" IS '智能客服识别出的意图（由 AI 客服大脑写入）';
COMMENT ON COLUMN "session"."emotion" IS '智能客服识别出的客户情绪：中性/焦虑/不满/愤怒';
COMMENT ON COLUMN "session"."bot_transfer_reason" IS '机器人转人工的原因（客户情绪激动 / 答不上来 / 命中转人工意图等）';
COMMENT ON COLUMN "session"."source" IS '来源：微信、App、网站等渠道';
COMMENT ON COLUMN "session"."start_time" IS '开始时间';
COMMENT ON COLUMN "session"."end_time" IS '结束时间';
COMMENT ON COLUMN "session"."csat_score" IS '满意度评分1-5';
CREATE INDEX "idx_session_tenant_status_time" ON "session" ("tenant_code", "status", "create_time");
-- 质检补扫用：按"已结束"找最近结束的会话
CREATE INDEX "idx_session_tenant_end_time"
    ON "session" ("tenant_code", "end_time" DESC)
    WHERE "status" = 4 AND "is_deleted" = FALSE;
CREATE INDEX "idx_session_tenant_customer" ON "session" ("tenant_code", "customer_id");

CREATE TABLE "session_message" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "msg_no" VARCHAR(40) NOT NULL,
  "client_msg_no" VARCHAR(64) DEFAULT NULL,
  "seq" BIGINT NOT NULL DEFAULT 0,
  "msg_type" SMALLINT NOT NULL,
  "sender_type" SMALLINT NOT NULL,
  "sender_id" BIGINT DEFAULT NULL,
  "content" TEXT,
  "ref_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "visible_to" SMALLINT NOT NULL DEFAULT 1,
  "send_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_session_message_msg_type CHECK ("msg_type" IN (1,2,3,4,5)),
  CONSTRAINT ck_session_message_sender_type CHECK ("sender_type" IN (1,2,3,4)),
  CONSTRAINT ck_session_message_status CHECK ("status" IN (1,2,3,4)),
  CONSTRAINT ck_session_message_visible_to CHECK ("visible_to" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_msg_no" UNIQUE ("tenant_code", "msg_no")
);

-- 幂等：同一个会话里同一个 client_msg_no 只允许一条；增量补拉按 (会话, seq) 走索引
CREATE UNIQUE INDEX "uk_session_client_msg"
    ON "session_message" ("tenant_code", "session_id", "client_msg_no")
    WHERE "client_msg_no" IS NOT NULL;

CREATE INDEX "idx_session_message_seq"
    ON "session_message" ("tenant_code", "session_id", "seq");
COMMENT ON TABLE "session_message" IS '会话消息表';
COMMENT ON COLUMN "session_message"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "session_message"."session_id" IS '会话ID';
COMMENT ON COLUMN "session_message"."msg_no" IS '消息编号（客户端幂等键）';
COMMENT ON COLUMN "session_message"."client_msg_no" IS '客户端消息号（幂等键，同一条消息重发只落一条）';
COMMENT ON COLUMN "session_message"."seq" IS '会话内序号（从 1 开始，双方按它排序）';
COMMENT ON COLUMN "session_message"."msg_type" IS '类型码：1-文本、2-图片、3-卡片、4-事件、5-系统';
COMMENT ON COLUMN "session_message"."sender_type" IS '发送方码：1-客户、2-坐席、3-机器人、4-系统';
COMMENT ON COLUMN "session_message"."sender_id" IS '发送人ID';
COMMENT ON COLUMN "session_message"."content" IS '消息内容（JSON，含图片/卡片结构）';
COMMENT ON COLUMN "session_message"."ref_id" IS '引用消息ID';
COMMENT ON COLUMN "session_message"."status" IS '状态码：1-已发送、2-已送达、3-已读、4-失败';
COMMENT ON COLUMN "session_message"."visible_to" IS '可见范围码：1-客户与坐席都可见、2-仅坐席可见（内部备注）';
COMMENT ON COLUMN "session_message"."send_time" IS '发送时间';
CREATE INDEX "idx_session_message_tenant_session_time" ON "session_message" ("tenant_code", "session_id", "send_time");

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
  CONSTRAINT ck_session_event_event_type CHECK ("event_type" IN (1,2,3,4,5,6)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "session_event" IS '会话事件表';
COMMENT ON COLUMN "session_event"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "session_event"."session_id" IS '会话ID';
COMMENT ON COLUMN "session_event"."event_type" IS '事件类型码：1-转接、2-升级、3-分配、4-关闭、5-超时、6-机器人转人工';
COMMENT ON COLUMN "session_event"."operator_id" IS '操作人ID';
COMMENT ON COLUMN "session_event"."from_value" IS '来源值（原坐席/原技能组）';
COMMENT ON COLUMN "session_event"."to_value" IS '目标值';
COMMENT ON COLUMN "session_event"."remark" IS '备注';
COMMENT ON COLUMN "session_event"."event_time" IS '事件时间';
CREATE INDEX "idx_session_event_tenant_session" ON "session_event" ("tenant_code", "session_id", "event_time");

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
COMMENT ON COLUMN "customer"."customer_no" IS '客户编号（匿名访客为 128 位随机串，不可枚举）';
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

CREATE TABLE "ticket" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "ticket_no" VARCHAR(40) NOT NULL,
  "ticket_type" SMALLINT NOT NULL DEFAULT 1,
  "category" SMALLINT NOT NULL DEFAULT 5,
  "title" VARCHAR(128) NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "customer_name" VARCHAR(64) DEFAULT NULL,
  "desc" TEXT,
  "priority" SMALLINT NOT NULL DEFAULT 2,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "source" SMALLINT NOT NULL DEFAULT 3,
  "session_no" VARCHAR(40) DEFAULT NULL,
  "source_session_id" BIGINT DEFAULT NULL,
  "assignee_id" BIGINT DEFAULT NULL,
  "assignee_name" VARCHAR(64) DEFAULT NULL,
  "group_id" BIGINT DEFAULT NULL,
  "sla_first_minutes" INT NOT NULL DEFAULT 0,
  "sla_resolve_minutes" INT NOT NULL DEFAULT 0,
  "first_response_due" TIMESTAMP DEFAULT NULL,
  "first_response_at" TIMESTAMP DEFAULT NULL,
  "resolve_due" TIMESTAMP DEFAULT NULL,
  "resolved_at" TIMESTAMP DEFAULT NULL,
  "sla_deadline" TIMESTAMP DEFAULT NULL,
  "sla_state" SMALLINT DEFAULT 1,
  "sla_alerted" BOOLEAN NOT NULL DEFAULT FALSE,
  "close_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "creator_name" VARCHAR(64) DEFAULT NULL,
  "source_channel" SMALLINT DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_ticket_ticket_type CHECK ("ticket_type" IN (1,2)),
  CONSTRAINT ck_ticket_category CHECK ("category" IN (1,2,3,4,5)),
  CONSTRAINT ck_ticket_priority CHECK ("priority" IN (1,2,3,4)),
  CONSTRAINT ck_ticket_status CHECK ("status" IN (1,2,3,4,5)),
  CONSTRAINT ck_ticket_source CHECK ("source" IN (1,2,3)),
  CONSTRAINT ck_ticket_source_channel CHECK ("source_channel" IS NULL OR "source_channel" IN (1,2,3,4,5)),
  CONSTRAINT ck_ticket_sla_state CHECK ("sla_state" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_ticket_no" UNIQUE ("tenant_code", "ticket_no")
);
COMMENT ON TABLE "ticket" IS '工单表';
COMMENT ON COLUMN "ticket"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "ticket"."ticket_no" IS '工单号';
COMMENT ON COLUMN "ticket"."ticket_type" IS '类型码：1-企业内部工单、2-平台支持工单';
COMMENT ON COLUMN "ticket"."category" IS '业务分类码：1-订单、2-退款售后、3-物流、4-商品、5-其他';
COMMENT ON COLUMN "ticket"."customer_id" IS '客户ID（快照，分库冗余，便于按客户查工单）';
COMMENT ON COLUMN "ticket"."customer_name" IS '客户姓名（冗余，列表直接显示）';
COMMENT ON COLUMN "ticket"."title" IS '工单主题';
COMMENT ON COLUMN "ticket"."desc" IS '问题描述（会话转单时带最近聊天记录摘要）';
COMMENT ON COLUMN "ticket"."priority" IS '优先级码：1-低、2-中、3-高、4-紧急';
COMMENT ON COLUMN "ticket"."status" IS '状态码：1-待处理、2-处理中、3-待客户确认、4-已解决、5-已关闭';
COMMENT ON COLUMN "ticket"."source" IS '来源码：1-会话转单、2-客户自助、3-坐席新建';
COMMENT ON COLUMN "ticket"."session_no" IS '来源会话号（坐席可一键跳回会话看上下文）';
COMMENT ON COLUMN "ticket"."source_session_id" IS '来源会话ID';
COMMENT ON COLUMN "ticket"."assignee_id" IS '处理人ID';
COMMENT ON COLUMN "ticket"."assignee_name" IS '处理人姓名（冗余，列表直接显示）';
COMMENT ON COLUMN "ticket"."group_id" IS '处理技能组ID';
COMMENT ON COLUMN "ticket"."sla_first_minutes" IS '建单时快照的首次响应 SLA（分钟）';
COMMENT ON COLUMN "ticket"."sla_resolve_minutes" IS '建单时快照的解决 SLA（分钟）';
COMMENT ON COLUMN "ticket"."first_response_due" IS '首次响应截止时间';
COMMENT ON COLUMN "ticket"."first_response_at" IS '首次响应时间（第一条对客户可见的回复）';
COMMENT ON COLUMN "ticket"."resolve_due" IS '解决截止时间';
COMMENT ON COLUMN "ticket"."resolved_at" IS '解决时间';
COMMENT ON COLUMN "ticket"."sla_deadline" IS 'SLA 截止时间（旧的单段口径，等于 resolve_due，保留兼容）';
COMMENT ON COLUMN "ticket"."sla_state" IS 'SLA 状态码：1-正常、2-即将超时、3-已超时';
COMMENT ON COLUMN "ticket"."sla_alerted" IS '这一轮预警是否已经推送过（避免重复轰炸）';
COMMENT ON COLUMN "ticket"."close_time" IS '关闭时间';
COMMENT ON COLUMN "ticket"."creator_name" IS '创建人姓名（冗余，列表直接显示）';
COMMENT ON COLUMN "ticket"."source_channel" IS '来源渠道码：1-在线会话、2-电话热线、3-邮件、4-工单导入、5-其它';
CREATE INDEX "idx_ticket_tenant_status_time" ON "ticket" ("tenant_code", "status", "create_time");
CREATE INDEX "idx_ticket_tenant_assignee" ON "ticket" ("tenant_code", "assignee_id");
CREATE INDEX "idx_ticket_tenant_sla" ON "ticket" ("tenant_code", "sla_state", "resolve_due");
CREATE INDEX "idx_ticket_tenant_session" ON "ticket" ("tenant_code", "session_no");

CREATE TABLE "ticket_event" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "ticket_id" BIGINT NOT NULL,
  "event_type" SMALLINT NOT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "operator_name" VARCHAR(64) DEFAULT NULL,
  "from_status" SMALLINT DEFAULT NULL,
  "to_status" SMALLINT DEFAULT NULL,
  "visible_to_customer" BOOLEAN NOT NULL DEFAULT FALSE,
  "content" VARCHAR(512) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ticket_event_event_type CHECK ("event_type" IN (1,2,3,4,5,6,7,8)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "ticket_event" IS '工单流转记录表';
COMMENT ON COLUMN "ticket_event"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "ticket_event"."ticket_id" IS '工单ID';
COMMENT ON COLUMN "ticket_event"."event_type" IS '事件码：1-创建、2-分配、3-回复、4-升级、5-关闭、6-重开、7-状态变更、8-SLA预警';
COMMENT ON COLUMN "ticket_event"."operator_id" IS '操作人ID';
COMMENT ON COLUMN "ticket_event"."operator_name" IS '操作人姓名（冗余，时间线直接显示）';
COMMENT ON COLUMN "ticket_event"."from_status" IS '变更前状态（状态流转类事件用）';
COMMENT ON COLUMN "ticket_event"."to_status" IS '变更后状态';
COMMENT ON COLUMN "ticket_event"."visible_to_customer" IS '这条回复是否对客户可见（false=内部备注）';
COMMENT ON COLUMN "ticket_event"."content" IS '事件内容 / 回复正文';
CREATE INDEX "idx_ticket_event_tenant_ticket" ON "ticket_event" ("tenant_code", "ticket_id", "event_time");

CREATE TABLE "ticket_sla_rule" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "priority" SMALLINT NOT NULL,
  "first_response_minutes" INT NOT NULL,
  "resolve_minutes" INT NOT NULL,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_ticket_sla_rule_priority CHECK ("priority" IN (1,2,3,4)),
  CONSTRAINT ck_ticket_sla_rule_minutes CHECK ("first_response_minutes" > 0 AND "resolve_minutes" > 0),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "ticket_sla_rule" IS '工单 SLA 规则（按优先级配置首次响应 / 解决时长）';
COMMENT ON COLUMN "ticket_sla_rule"."priority" IS '优先级码：1-低、2-中、3-高、4-紧急';
COMMENT ON COLUMN "ticket_sla_rule"."first_response_minutes" IS '首次响应时限（分钟）';
COMMENT ON COLUMN "ticket_sla_rule"."resolve_minutes" IS '解决时限（分钟）';
CREATE UNIQUE INDEX "uk_ticket_sla_rule_tenant_priority"
    ON "ticket_sla_rule" ("tenant_code", "priority")
    WHERE "is_deleted" = FALSE;

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
COMMENT ON COLUMN "kb_document"."chunk_count" IS '切片数（向量化完成后回填）';
COMMENT ON COLUMN "kb_document"."index_status" IS '索引状态：1-未索引、2-索引中、3-已索引、4-索引失败';
COMMENT ON COLUMN "kb_document"."file_name" IS '原始文件名（导入的文档）';
CREATE INDEX "idx_kb_document_tenant_status" ON "kb_document" ("tenant_code", "status");
CREATE INDEX "idx_kb_document_tenant_category" ON "kb_document" ("tenant_code", "category_id");

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE "kb_chunk" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "chunk_no" INT NOT NULL,
  "content" TEXT NOT NULL,
  "char_count" INT NOT NULL DEFAULT 0,
  "token_count" INT NOT NULL DEFAULT 0,
  "embedding" vector(1024),
  "embedding_model" VARCHAR(64) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "kb_chunk" IS '知识文档切片表（检索的最小单位）';
CREATE INDEX "idx_kb_chunk_tenant_doc" ON "kb_chunk" ("tenant_code", "doc_id", "chunk_no");
CREATE INDEX "idx_kb_chunk_embedding" ON "kb_chunk" USING hnsw ("embedding" vector_cosine_ops)
    WHERE "embedding" IS NOT NULL;

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
CREATE UNIQUE INDEX "uk_qa_task_tenant_session"
    ON "qa_task" ("tenant_code", "session_id")
    WHERE "is_deleted" = FALSE AND "session_id" IS NOT NULL;

CREATE TABLE "qa_rule" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "rule_name" VARCHAR(64) NOT NULL,
  "rule_type" SMALLINT NOT NULL,
  "rule_content" TEXT,
  "weight" INT NOT NULL DEFAULT 1,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "is_realtime" BOOLEAN NOT NULL DEFAULT TRUE,
  "hit_keywords" VARCHAR(512) DEFAULT NULL,
  "severity" SMALLINT NOT NULL DEFAULT 2,
  "timeout_seconds" INTEGER NOT NULL DEFAULT 60,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_rule_rule_type CHECK ("rule_type" IN (1,2,3,4,5)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "qa_rule" IS '质检规则表';
COMMENT ON COLUMN "qa_rule"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "qa_rule"."rule_name" IS '规则名称';
COMMENT ON COLUMN "qa_rule"."rule_type" IS '类型码：1-敏感词、2-承诺规范、3-必答项、4-情绪识别';
COMMENT ON COLUMN "qa_rule"."rule_content" IS '规则内容（关键词/正则/描述）';
COMMENT ON COLUMN "qa_rule"."weight" IS '权重';
COMMENT ON COLUMN "qa_rule"."is_realtime" IS '是否参与实时质检：关掉就只在会话结束后批量质检';
COMMENT ON COLUMN "qa_rule"."hit_keywords" IS '命中词表（逗号分隔）：敏感词类规则命中任意一个就告警';
COMMENT ON COLUMN "qa_rule"."severity" IS '告警级别：1-提示、2-警告、3-严重';
COMMENT ON COLUMN "qa_rule"."timeout_seconds" IS '响应超时秒数（仅"5-响应超时"类规则使用）';
CREATE INDEX "idx_qa_rule_tenant_type" ON "qa_rule" ("tenant_code", "rule_type");

CREATE TABLE "qa_alert" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "session_no" VARCHAR(40) NOT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "message_id" BIGINT DEFAULT NULL,
  "message_seq" BIGINT DEFAULT NULL,
  "rule_id" BIGINT DEFAULT NULL,
  "rule_name" VARCHAR(64) NOT NULL,
  "rule_type" SMALLINT NOT NULL,
  "severity" SMALLINT NOT NULL DEFAULT 2,
  "hit_keyword" VARCHAR(128) DEFAULT NULL,
  "snippet" VARCHAR(512) DEFAULT NULL,
  "advice" VARCHAR(512) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "handler_id" BIGINT DEFAULT NULL,
  "handle_remark" VARCHAR(512) DEFAULT NULL,
  "handle_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_alert_severity CHECK ("severity" IN (1,2,3)),
  CONSTRAINT ck_qa_alert_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "qa_alert" IS '会话实时质检告警';
COMMENT ON COLUMN "qa_alert"."session_no" IS '会话号（坐席点告警直接跳到这条会话）';
COMMENT ON COLUMN "qa_alert"."agent_id" IS '告警发生时该会话的负责坐席';
COMMENT ON COLUMN "qa_alert"."message_id" IS '触发告警的消息 ID';
COMMENT ON COLUMN "qa_alert"."rule_name" IS '命中的规则名';
COMMENT ON COLUMN "qa_alert"."hit_keyword" IS '命中的词（敏感词类规则才有）';
COMMENT ON COLUMN "qa_alert"."snippet" IS '命中片段（截取消息上下文，便于坐席定位）';
COMMENT ON COLUMN "qa_alert"."advice" IS '处置建议';
COMMENT ON COLUMN "qa_alert"."status" IS '状态码：1-待处理、2-已处理';
CREATE INDEX "idx_qa_alert_tenant_session" ON "qa_alert" ("tenant_code", "session_no", "create_time");
CREATE INDEX "idx_qa_alert_tenant_status" ON "qa_alert" ("tenant_code", "status", "create_time");
CREATE UNIQUE INDEX "uk_qa_alert_message_rule"
    ON "qa_alert" ("tenant_code", "message_id", "rule_id")
    WHERE "is_deleted" = FALSE AND "message_id" IS NOT NULL AND "rule_id" IS NOT NULL;

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

CREATE TABLE "file_meta" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "file_no" VARCHAR(40) NOT NULL,
  "file_name" VARCHAR(255) NOT NULL,
  "object_key" VARCHAR(512) NOT NULL,
  "file_size" BIGINT NOT NULL DEFAULT 0,
  "mime_type" VARCHAR(128) DEFAULT NULL,
  "biz_type" SMALLINT NOT NULL,
  "biz_id" BIGINT DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_file_meta_biz_type CHECK ("biz_type" IN (1,2,3,4,5)),
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
COMMENT ON COLUMN "file_meta"."biz_type" IS '业务类型码：1-头像、2-附件、3-导出、4-发票、5-聊天图片';
COMMENT ON COLUMN "file_meta"."biz_id" IS '业务ID';
CREATE INDEX "idx_file_meta_tenant_biz" ON "file_meta" ("tenant_code", "biz_type", "biz_id");

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

### 文件：scripts/migrate-customer-db.sh

客户库执行器纳入 customer\_db\_ticket.sql

``` code-block-container
#!/usr/bin/env bash
# 把 customer_db 的所有增量脚本按顺序跑一遍（每个脚本都是"可重复执行"的，多跑无副作用）。
#
# 用法：
#   bash scripts/migrate-customer-db.sh
#   PGHOST=127.0.0.1 PGPORT=5432 PGUSER=mac PGPASSWORD=123456 DB=customer_db bash scripts/migrate-customer-db.sh
#
# 什么时候要跑：customer-service 启动日志里出现"数据库结构不完整"，
# 或者接口报 column "xxx" does not exist / relation "xxx" does not exist。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="${DB:-customer_db}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-mac}"

# 找不到 psql 就试着用 Postgres.app 自带的那份
PSQL="${PSQL:-$(command -v psql || true)}"
if [ -z "$PSQL" ] && [ -x "/Applications/Postgres.app/Contents/Versions/latest/bin/psql" ]; then
  PSQL="/Applications/Postgres.app/Contents/Versions/latest/bin/psql"
fi
if [ -z "$PSQL" ]; then
  echo "找不到 psql，请设置 PSQL=/path/to/psql 后重试" >&2
  exit 1
fi

export PGPASSWORD="${PGPASSWORD:-123456}"
export PGHOST PGPORT PGUSER

# 顺序按依赖排：先有的表先建，后加的列后补
SCRIPTS=(
  customer_db_security.sql
  customer_db_collab.sql
  customer_db_delivery.sql
  customer_db_qa.sql
  customer_db_routing.sql
  customer_db_realtime_qa.sql
  customer_db_qa_source.sql
  customer_db_qa_timeout.sql
  customer_db_kb.sql
  customer_db_bot_brain.sql
  customer_db_chat_image.sql
  customer_db_ticket.sql
)

echo "目标库：${PGUSER}@${PGHOST}:${PGPORT}/${DB}"
for script in "${SCRIPTS[@]}"; do
  printf '  → %-32s' "$script"
  "$PSQL" -q -v ON_ERROR_STOP=1 -d "$DB" -f "${ROOT}/schema/${script}" > /tmp/migrate-$$.log 2>&1 \
    && echo "完成" \
    || { echo "失败"; tail -10 /tmp/migrate-$$.log; rm -f /tmp/migrate-$$.log; exit 1; }
done
rm -f /tmp/migrate-$$.log

echo
echo "全部完成。核对一下关键结构："
"$PSQL" -d "$DB" -c "select rule_name, rule_type, is_enabled, is_realtime, timeout_seconds
                       from qa_rule
                      where is_deleted = false
                      order by rule_type;"
"$PSQL" -d "$DB" -c "select column_name, data_type
                       from information_schema.columns
                      where table_name = 'session' and column_name = 'bot_transfer_reason';"
```

### 3.2 运行期副本

后端启动自检要能自动补结构，所以 `resources/schema/` 下必须有一份内容完全一样的副本（`scripts/check-schema-copies.sh` 会检查）：

### 复制：schema/customer\_db\_ticket.sql → yunti-backend/yunti-customer-service/src/main/resources/schema/customer\_db\_ticket.sql

运行期副本：启动自检发现缺表/缺列/约束没放开时，就是执行它来修。

源文件的权威内容就在前面（`schema/customer_db_ticket.sql`），运行期这份副本内容一模一样——直接在工程里复制一份即可：`cp schema/customer_db_ticket.sql yunti-backend/yunti-customer-service/src/main/resources/schema/customer_db_ticket.sql`。

### 3.3 启动自检：把"少跑脚本"提前到启动时说出来

这一篇的工单需要三样东西：表（`ticket_sla_rule`）、列（`session_no` / `resolve_due` / `sla_alerted` / `source_channel` …）、**约束取值**（状态允许 5、事件类型允许 8、来源渠道允许 5）。三样都登记进 SchemaGuard，缺了就自动执行对应脚本，执行不了会在启动日志里明确告诉你跑哪个脚本：

### 改动：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/config/SchemaGuard.java

改动点：启动自检新增工单相关的表/列/约束取值

这个文件一共 3 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 100 行附近）**

原来是这样：

``` code-block-container
        REQUIRED_TABLES.put("qa_review", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_alert", "customer_db_realtime_qa.sql");
        REQUIRED_TABLES.put("kb_chunk", "customer_db_kb.sql");

        REQUIRED_COLUMNS.put("channel.allowed_origins", "customer_db_security.sql");
        REQUIRED_COLUMNS.put("session.last_msg_seq", "customer_db_delivery.sql");
```

改成：

``` code-block-container
        REQUIRED_TABLES.put("qa_review", "customer_db_qa.sql");
        REQUIRED_TABLES.put("qa_alert", "customer_db_realtime_qa.sql");
        REQUIRED_TABLES.put("kb_chunk", "customer_db_kb.sql");
        REQUIRED_TABLES.put("ticket_sla_rule", "customer_db_ticket.sql");

        REQUIRED_COLUMNS.put("channel.allowed_origins", "customer_db_security.sql");
        REQUIRED_COLUMNS.put("session.last_msg_seq", "customer_db_delivery.sql");
```

**新增 2（第 116 行附近）**

原来是这样：

``` code-block-container
        REQUIRED_COLUMNS.put("kb_document.chunk_count", "customer_db_kb.sql");
        REQUIRED_COLUMNS.put("kb_document.index_status", "customer_db_kb.sql");
        REQUIRED_COLUMNS.put("session.bot_transfer_reason", "customer_db_bot_brain.sql");

        REQUIRED_MIN_LENGTH.put("file_meta.mime_type", 128);
```

改成：

``` code-block-container
        REQUIRED_COLUMNS.put("kb_document.chunk_count", "customer_db_kb.sql");
        REQUIRED_COLUMNS.put("kb_document.index_status", "customer_db_kb.sql");
        REQUIRED_COLUMNS.put("session.bot_transfer_reason", "customer_db_bot_brain.sql");
        REQUIRED_COLUMNS.put("ticket.session_no", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("ticket.first_response_due", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("ticket.resolve_due", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("ticket.sla_alerted", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("ticket.source_channel", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("ticket_event.operator_name", "customer_db_ticket.sql");
        REQUIRED_COLUMNS.put("ticket_sla_rule.first_response_minutes", "customer_db_ticket.sql");

        REQUIRED_MIN_LENGTH.put("file_meta.mime_type", 128);
```

**新增 3（第 130 行附近）**

原来是这样：

``` code-block-container
        REQUIRED_CONSTRAINTS.put("ck_file_meta_biz_type",
                new ConstraintRule("'5'", "customer_db_chat_image.sql",
                        "file_meta.biz_type 允许 5-聊天图片"));
    }

    private final SchemaMapper schemaMapper;
```

改成：

``` code-block-container
        REQUIRED_CONSTRAINTS.put("ck_file_meta_biz_type",
                new ConstraintRule("'5'", "customer_db_chat_image.sql",
                        "file_meta.biz_type 允许 5-聊天图片"));
        // 工单状态多了"待客户确认"，事件类型多了"状态变更 / SLA 预警"
        REQUIRED_CONSTRAINTS.put("ck_ticket_status",
                new ConstraintRule("'5'", "customer_db_ticket.sql",
                        "ticket.status 允许 5-已关闭"));
        REQUIRED_CONSTRAINTS.put("ck_ticket_event_event_type",
                new ConstraintRule("'8'", "customer_db_ticket.sql",
                        "ticket_event.event_type 允许 8-SLA预警"));
        REQUIRED_CONSTRAINTS.put("ck_ticket_source_channel",
                new ConstraintRule("'5'", "customer_db_ticket.sql",
                        "ticket.source_channel 允许 5-其它"));
    }

    private final SchemaMapper schemaMapper;
```

## 四、customer-service：工单主链路

### 4.1 三个实体

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/entity/Ticket.java

工单实体：注意两处设计——SLA 时长与两个截止时间是**建单时快照**的（规则改了不影响存量单），`sla_alerted` 记录"这一轮预警推没推过"（否则每 30 秒重复轰炸）。

``` code-block-container
package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ticket 工单。
 *
 * <p>SLA 是"两段"的：首次响应（客户等多久有人理）和解决（多久真正处理完）。
 * 只卡一个总时限的话，"有人回复了但一直没解决"和"压根没人理"会被算成同一件事。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ticket")
public class Ticket {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String ticketNo;

    /** 1-企业内部工单、2-平台支持工单 */
    private Integer ticketType;

    /** 1-订单、2-退款售后、3-物流、4-商品、5-其他 */
    private Integer category;

    private String title;

    private Long customerId;

    private String customerName;

    /** 问题描述（会话转单时带最近聊天记录摘要） */
    @TableField("\"desc\"")
    private String desc;

    /** 1-低、2-中、3-高、4-紧急 */
    private Integer priority;

    /** 1-待处理、2-处理中、3-待客户确认、4-已解决、5-已关闭 */
    private Integer status;

    /** 1-会话转单、2-客户自助、3-坐席新建 */
    private Integer source;

    /** 来源会话号 */
    private String sessionNo;

    /** 来源会话ID */
    private Long sourceSessionId;

    private Long assigneeId;

    private String assigneeName;

    private Long groupId;

    /** 建单时快照的 SLA 时长（规则后来改了，不影响存量工单） */
    private Integer slaFirstMinutes;

    private Integer slaResolveMinutes;

    private LocalDateTime firstResponseDue;

    private LocalDateTime firstResponseAt;

    private LocalDateTime resolveDue;

    private LocalDateTime resolvedAt;

    /** 旧的单段口径，等于 resolveDue（保留兼容） */
    private LocalDateTime slaDeadline;

    /** 1-正常、2-即将超时、3-已超时 */
    private Integer slaState;

    /** 这一轮预警是否已经推送过（避免每 30 秒重复轰炸） */
    private Boolean slaAlerted;

    private LocalDateTime closeTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String creatorName;

    /** 来源渠道：1-在线会话、2-电话热线、3-邮件、4-工单导入、5-其它 */
    private Integer sourceChannel;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/entity/TicketEvent.java

流转记录：工单的"闭环"就靠这张表——谁在什么时候创建、分派、回复、改状态、升级、超时预警，一条条按时间排开，客户投诉"我的工单没人管"时翻这张表就能给答案。

``` code-block-container
package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ticket_event 工单流转记录。
 *
 * <p>工单的"闭环"就靠这张表：谁在什么时候创建、分派、回复、改状态、超时预警，
 * 一条条按时间排开，客户投诉"我的工单没人管"时，翻这张表就能给答案。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ticket_event")
public class TicketEvent {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long ticketId;

    /** 1-创建、2-分配、3-回复、4-升级、5-关闭、6-重开、7-状态变更、8-SLA预警 */
    private Integer eventType;

    private Long operatorId;

    private String operatorName;

    private Integer fromStatus;

    private Integer toStatus;

    /** 这条回复是否对客户可见（false=内部备注） */
    private Boolean visibleToCustomer;

    private String content;

    private LocalDateTime eventTime;

    private LocalDateTime createTime;
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/entity/TicketSlaRule.java

SLA 规则：按**优先级**配"多久内必须首次响应、多久内必须解决"。为什么按优先级而不是按分类：紧急单和低优先单都可能是"退款"，但对等待时间的要求完全不同。

``` code-block-container
package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ticket_sla_rule 工单 SLA 规则：按优先级配置"首次响应多久、解决多久"。
 *
 * <p>为什么按优先级而不是按分类：紧急工单和低优先工单都可能是"退款"，
 * 但等待时间的要求完全不同——SLA 的本质是"多久内必须有人动"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ticket_sla_rule")
public class TicketSlaRule {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 1-低、2-中、3-高、4-紧急 */
    private Integer priority;

    private Integer firstResponseMinutes;

    private Integer resolveMinutes;

    private Boolean isEnabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
```

对应的三个 Mapper 都是 MyBatis-Plus 的标准 `BaseMapper`，一行代码不多写：

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/TicketMapper.java

工单 Mapper。

``` code-block-container
package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.Ticket;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/TicketEventMapper.java

流转记录 Mapper。

``` code-block-container
package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.TicketEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketEventMapper extends BaseMapper<TicketEvent> {
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/TicketSlaRuleMapper.java

SLA 规则 Mapper。

``` code-block-container
package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.TicketSlaRule;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketSlaRuleMapper extends BaseMapper<TicketSlaRule> {
}
```

###
