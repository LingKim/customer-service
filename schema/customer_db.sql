CREATE TABLE IF NOT EXISTS file_meta (
  id BIGINT NOT NULL,
  tenant_code VARCHAR(16) NOT NULL,
  file_no VARCHAR(40) NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  file_size BIGINT NOT NULL DEFAULT 0,
  mime_type VARCHAR(64),
  biz_type SMALLINT NOT NULL,
  biz_id BIGINT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64) NOT NULL,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  editor VARCHAR(64),
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_file_meta_size CHECK (file_size >= 0),
  CONSTRAINT ck_file_meta_biz_type CHECK (biz_type IN (1, 2, 3, 4)),
  PRIMARY KEY (id),
  CONSTRAINT uk_tenant_file_no UNIQUE (tenant_code, file_no)
);

CREATE INDEX IF NOT EXISTS idx_file_meta_biz_owner
  ON file_meta (biz_type, creator, is_deleted);

CREATE TABLE IF NOT EXISTS skill_group (
  id BIGINT NOT NULL,
  tenant_code VARCHAR(16) NOT NULL,
  name VARCHAR(64) NOT NULL,
  description VARCHAR(255),
  is_default BOOLEAN NOT NULL DEFAULT FALSE,
  is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64), editor VARCHAR(64), is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (id), CONSTRAINT uk_skill_group_tenant_name UNIQUE (tenant_code, name)
);

CREATE TABLE IF NOT EXISTS channel (
  id BIGINT NOT NULL,
  tenant_code VARCHAR(16) NOT NULL,
  channel_id VARCHAR(32) NOT NULL,
  channel_type SMALLINT NOT NULL,
  name VARCHAR(64) NOT NULL,
  "desc" VARCHAR(255),
  allowed_origins VARCHAR(512),
  skill_group_id BIGINT,
  status SMALLINT NOT NULL DEFAULT 1,
  stage SMALLINT NOT NULL DEFAULT 3,
  is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64), editor VARCHAR(64), is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_channel_type CHECK (channel_type IN (1, 2, 3)),
  CONSTRAINT ck_channel_status CHECK (status IN (1, 2)),
  CONSTRAINT ck_channel_stage CHECK (stage IN (1, 2, 3)),
  PRIMARY KEY (id), CONSTRAINT uk_tenant_channel UNIQUE (tenant_code, channel_id)
);

CREATE INDEX IF NOT EXISTS idx_channel_tenant_status ON channel (tenant_code, status, is_deleted);

CREATE TABLE IF NOT EXISTS channel_key (
  id BIGINT NOT NULL,
  tenant_code VARCHAR(16) NOT NULL,
  channel_id BIGINT NOT NULL,
  app_key VARCHAR(64) NOT NULL,
  key_type SMALLINT NOT NULL DEFAULT 1,
  status SMALLINT NOT NULL DEFAULT 1,
  rotated_at TIMESTAMP,
  expire_time TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64), editor VARCHAR(64), is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_channel_key_type CHECK (key_type IN (1, 2)),
  CONSTRAINT ck_channel_key_status CHECK (status IN (1, 2)),
  PRIMARY KEY (id), CONSTRAINT uk_tenant_channel_key UNIQUE (tenant_code, channel_id, app_key)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_channel_active_key ON channel_key (channel_id)
  WHERE status = 1 AND is_deleted = FALSE;

CREATE TABLE IF NOT EXISTS "qa_rule" (
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
CREATE INDEX IF NOT EXISTS "idx_qa_rule_tenant_type" ON "qa_rule" ("tenant_code", "rule_type");

-- 未删除规则同一企业同一名称唯一，阻止再次出现重复
CREATE UNIQUE INDEX IF NOT EXISTS "uk_qa_rule_tenant_name_active"
    ON "qa_rule" ("tenant_code", "rule_name")
    WHERE "is_deleted" = FALSE;

CREATE TABLE IF NOT EXISTS "qa_task" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "task_no" VARCHAR(40) NOT NULL,
  "session_id" BIGINT DEFAULT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "ai_score" NUMERIC(5,2) DEFAULT NULL,
  "ai_result" TEXT DEFAULT NULL,
  "transcript" TEXT DEFAULT NULL,
  "ai_source" VARCHAR(32) DEFAULT NULL,
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
CREATE INDEX IF NOT EXISTS "idx_qa_task_tenant_status" ON "qa_task" ("tenant_code", "status");
CREATE INDEX IF NOT EXISTS "idx_qa_task_tenant_session" ON "qa_task" ("tenant_code", "session_id");

CREATE TABLE IF NOT EXISTS "qa_review" (
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
CREATE INDEX IF NOT EXISTS "idx_qa_review_tenant_task" ON "qa_review" ("tenant_code", "task_id", "review_time");

-- 第 15 章：会话、消息和访客档案，字段契约来自第 03 章设计。
CREATE TABLE IF NOT EXISTS "session" (
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
CREATE INDEX IF NOT EXISTS "idx_session_tenant_status_time" ON "session" ("tenant_code", "status", "create_time");
CREATE INDEX IF NOT EXISTS "idx_session_tenant_customer" ON "session" ("tenant_code", "customer_id");

CREATE TABLE IF NOT EXISTS "session_message" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "msg_no" VARCHAR(40) NOT NULL,
  "msg_type" SMALLINT NOT NULL,
  "visible_to" SMALLINT NOT NULL DEFAULT 1,
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
  CONSTRAINT ck_session_message_visible_to CHECK ("visible_to" IN (1,2)),
  CONSTRAINT ck_session_message_sender_type CHECK ("sender_type" IN (1,2,3,4)),
  CONSTRAINT ck_session_message_status CHECK ("status" IN (1,2,3,4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_msg_no" UNIQUE ("tenant_code", "msg_no")
);
COMMENT ON TABLE "session_message" IS '会话消息表';
COMMENT ON COLUMN "session_message"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "session_message"."session_id" IS '会话ID';
COMMENT ON COLUMN "session_message"."msg_no" IS '消息编号（客户端幂等键）';
COMMENT ON COLUMN "session_message"."visible_to" IS '可见范围码：1-客户与坐席都可见、2-仅坐席可见（内部备注）';
COMMENT ON COLUMN "session_message"."msg_type" IS '类型码：1-文本、2-图片、3-卡片、4-事件、5-系统';
COMMENT ON COLUMN "session_message"."sender_type" IS '发送方码：1-客户、2-坐席、3-机器人、4-系统';
COMMENT ON COLUMN "session_message"."sender_id" IS '发送人ID';
COMMENT ON COLUMN "session_message"."content" IS '消息内容（JSON，含图片/卡片结构）';
COMMENT ON COLUMN "session_message"."ref_id" IS '引用消息ID';
COMMENT ON COLUMN "session_message"."status" IS '状态码：1-已发送、2-已送达、3-已读、4-失败';
COMMENT ON COLUMN "session_message"."send_time" IS '发送时间';
CREATE INDEX IF NOT EXISTS "idx_session_message_tenant_session_time" ON "session_message" ("tenant_code", "session_id", "send_time");

CREATE TABLE IF NOT EXISTS "customer" (
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
CREATE INDEX IF NOT EXISTS "idx_customer_tenant_level" ON "customer" ("tenant_code", "level");

-- 第 16 章：会话流转记录。
CREATE TABLE IF NOT EXISTS "session_event" (
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
CREATE INDEX IF NOT EXISTS "idx_session_event_tenant_session" ON "session_event" ("tenant_code", "session_id", "event_time");
