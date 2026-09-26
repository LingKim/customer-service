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

-- Chapter 24: notification center
CREATE TABLE IF NOT EXISTS "notification" (
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
CREATE INDEX IF NOT EXISTS "idx_notification_tenant_type_time" ON "notification" ("tenant_code", "notify_type", "publish_time");

CREATE TABLE IF NOT EXISTS "notification_read" (
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
