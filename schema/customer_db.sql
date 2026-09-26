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
  CONSTRAINT ck_file_meta_biz_type CHECK (biz_type IN (1, 2, 3, 4, 5)),
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
  overflow_after_seconds INTEGER NOT NULL DEFAULT 60,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64), editor VARCHAR(64), is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (id), CONSTRAINT uk_skill_group_tenant_name UNIQUE (tenant_code, name)
);

CREATE TABLE IF NOT EXISTS agent_status (
  id BIGINT NOT NULL,
  tenant_code VARCHAR(16) NOT NULL,
  agent_id BIGINT NOT NULL,
  status SMALLINT NOT NULL DEFAULT 1,
  max_concurrency SMALLINT NOT NULL DEFAULT 5,
  is_connected BOOLEAN NOT NULL DEFAULT FALSE,
  status_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64), editor VARCHAR(64), is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_agent_status_status CHECK (status IN (1, 2, 3)),
  PRIMARY KEY (id), CONSTRAINT uk_tenant_agent_status UNIQUE (tenant_code, agent_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_status_tenant_status ON agent_status (tenant_code, status);

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
CREATE UNIQUE INDEX IF NOT EXISTS "uk_qa_task_tenant_session" ON "qa_task" ("tenant_code", "session_id")
  WHERE "is_deleted" = FALSE AND "session_id" IS NOT NULL;

CREATE TABLE IF NOT EXISTS "qa_alert" (
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

CREATE INDEX IF NOT EXISTS "idx_qa_alert_tenant_session"
    ON "qa_alert" ("tenant_code", "session_no", "create_time");

CREATE INDEX IF NOT EXISTS "idx_qa_alert_tenant_status"
    ON "qa_alert" ("tenant_code", "status", "create_time");

-- 同一条消息 + 同一条规则只告警一次：消息幂等重发时不会重复弹窗
CREATE UNIQUE INDEX IF NOT EXISTS "uk_qa_alert_message_rule"
    ON "qa_alert" ("tenant_code", "message_id", "rule_id")
    WHERE "is_deleted" = FALSE AND "message_id" IS NOT NULL AND "rule_id" IS NOT NULL;

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
  "last_msg_seq" BIGINT NOT NULL DEFAULT 0,
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
COMMENT ON COLUMN "session"."last_msg_seq" IS '会话已分配的最大消息序号';
COMMENT ON COLUMN "session"."channel_id" IS '来源渠道ID';
COMMENT ON COLUMN "session"."customer_id" IS '客户ID';
COMMENT ON COLUMN "session"."status" IS '状态码：1-排队中、2-机器人接待、3-人工接待、4-已结束';
COMMENT ON COLUMN "session"."skill_group_id" IS '技能组ID';
COMMENT ON COLUMN "session"."agent_id" IS '当前坐席用户ID';
COMMENT ON COLUMN "session"."intent" IS '当前意图';
COMMENT ON COLUMN "session"."emotion" IS '情绪标签';
COMMENT ON COLUMN "session"."bot_transfer_reason" IS '机器人转人工的原因';
COMMENT ON COLUMN "session"."source" IS '来源：微信、App、网站等渠道';
COMMENT ON COLUMN "session"."start_time" IS '开始时间';
COMMENT ON COLUMN "session"."end_time" IS '结束时间';
COMMENT ON COLUMN "session"."csat_score" IS '满意度评分1-5';
CREATE INDEX IF NOT EXISTS "idx_session_tenant_status_time" ON "session" ("tenant_code", "status", "create_time");
CREATE INDEX IF NOT EXISTS "idx_session_tenant_customer" ON "session" ("tenant_code", "customer_id");
CREATE INDEX IF NOT EXISTS "idx_session_queue" ON "session" ("tenant_code", "start_time")
  WHERE "agent_id" IS NULL AND "is_deleted" = FALSE;
CREATE INDEX IF NOT EXISTS "idx_session_tenant_end_time" ON "session" ("tenant_code", "end_time" DESC)
  WHERE "status" = 4 AND "is_deleted" = FALSE;

CREATE TABLE IF NOT EXISTS "session_message" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "msg_no" VARCHAR(40) NOT NULL,
  "client_msg_no" VARCHAR(64),
  "seq" BIGINT NOT NULL DEFAULT 0,
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
COMMENT ON COLUMN "session_message"."msg_no" IS '服务端消息编号';
COMMENT ON COLUMN "session_message"."client_msg_no" IS '客户端消息号（同一会话内的幂等键）';
COMMENT ON COLUMN "session_message"."seq" IS '会话内递增序号';
COMMENT ON COLUMN "session_message"."visible_to" IS '可见范围码：1-客户与坐席都可见、2-仅坐席可见（内部备注）';
COMMENT ON COLUMN "session_message"."msg_type" IS '类型码：1-文本、2-图片、3-卡片、4-事件、5-系统';
COMMENT ON COLUMN "session_message"."sender_type" IS '发送方码：1-客户、2-坐席、3-机器人、4-系统';
COMMENT ON COLUMN "session_message"."sender_id" IS '发送人ID';
COMMENT ON COLUMN "session_message"."content" IS '消息内容（JSON，含图片/卡片结构）';
COMMENT ON COLUMN "session_message"."ref_id" IS '引用消息ID';
COMMENT ON COLUMN "session_message"."status" IS '状态码：1-已发送、2-已送达、3-已读、4-失败';
COMMENT ON COLUMN "session_message"."send_time" IS '发送时间';
CREATE INDEX IF NOT EXISTS "idx_session_message_tenant_session_time" ON "session_message" ("tenant_code", "session_id", "send_time");
CREATE UNIQUE INDEX IF NOT EXISTS "uk_session_client_msg"
  ON "session_message" ("tenant_code", "session_id", "client_msg_no")
  WHERE "client_msg_no" IS NOT NULL;
CREATE INDEX IF NOT EXISTS "idx_session_message_seq"
  ON "session_message" ("tenant_code", "session_id", "seq");

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
CREATE INDEX IF NOT EXISTS "idx_session_event_tenant_session" ON "session_event" ("tenant_code", "session_id", "event_time");

-- 第 20 章：企业知识库完整建库结构。
CREATE TABLE IF NOT EXISTS "kb_category" (
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
CREATE INDEX IF NOT EXISTS "idx_kb_category_tenant_parent" ON "kb_category" ("tenant_code", "parent_id");

CREATE TABLE IF NOT EXISTS "kb_document" (
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
CREATE INDEX IF NOT EXISTS "idx_kb_document_tenant_status" ON "kb_document" ("tenant_code", "status");
CREATE INDEX IF NOT EXISTS "idx_kb_document_tenant_category" ON "kb_document" ("tenant_code", "category_id");

-- 企业知识库：文档解析、切块与向量索引（可重复执行）
--   1. 打开 pgvector 扩展；
--   2. 新增 kb_chunk：一个文档切出来的每一块，带向量；
--   3. 补两个索引：按文档取切片、按向量做相似度检索（HNSW 余弦）。
--
-- 为什么切片单独一张表：文档（kb_document）是"人看的"，切片（kb_chunk）是"检索用的"。
-- 一份文档切几十上百块很正常，放一张表里查询会互相拖累；分开之后，
-- 文档列表只查文档表，检索只打切片表 + 向量索引。

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS "kb_chunk" (
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
COMMENT ON COLUMN "kb_chunk"."doc_id" IS '所属文档ID';
COMMENT ON COLUMN "kb_chunk"."chunk_no" IS '文档内切片序号（从 1 开始）';
COMMENT ON COLUMN "kb_chunk"."content" IS '切片正文';
COMMENT ON COLUMN "kb_chunk"."char_count" IS '字符数（切块时统计，用于展示）';
COMMENT ON COLUMN "kb_chunk"."token_count" IS '估算 token 数（按 1 汉字≈1 token 粗估）';
COMMENT ON COLUMN "kb_chunk"."embedding" IS '向量（1024 维，千问 text-embedding-v3 默认维度）';
COMMENT ON COLUMN "kb_chunk"."embedding_model" IS '生成这个向量的模型（换模型要重建索引）';

-- 按文档取切片：切片预览、重建索引都要用
CREATE INDEX IF NOT EXISTS "idx_kb_chunk_tenant_doc"
    ON "kb_chunk" ("tenant_code", "doc_id", "chunk_no");

-- 向量检索：HNSW + 余弦距离（pgvector 0.5+ 才支持 HNSW）
CREATE INDEX IF NOT EXISTS "idx_kb_chunk_embedding"
    ON "kb_chunk" USING hnsw ("embedding" vector_cosine_ops)
    WHERE "embedding" IS NOT NULL;

-- 文档表补两列：切了多少块、最后索引进度（前端列表直接展示，不用再 count）
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "chunk_count" INT NOT NULL DEFAULT 0;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "index_status" SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "index_message" VARCHAR(255) DEFAULT NULL;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "file_name" VARCHAR(255) DEFAULT NULL;
ALTER TABLE "kb_document" ADD COLUMN IF NOT EXISTS "file_size" BIGINT DEFAULT NULL;

COMMENT ON COLUMN "kb_document"."chunk_count" IS '切片数（向量化完成后回填）';
COMMENT ON COLUMN "kb_document"."index_status" IS '索引状态：1-未索引、2-索引中、3-已索引、4-索引失败';
COMMENT ON COLUMN "kb_document"."index_message" IS '索引失败原因 / 最近一次索引说明';
COMMENT ON COLUMN "kb_document"."file_name" IS '原始文件名（导入的文档）';
COMMENT ON COLUMN "kb_document"."file_size" IS '文件大小（字节）';

-- 知识库上传暴露的老问题：file_meta.mime_type 只有 varchar(64)，
-- 而 Office 文档的标准 MIME 比它长（docx 71、xlsx 65、pptx 73），
-- 一上传 Word 就报 "value too long for type character varying(64)"。
ALTER TABLE "file_meta" ALTER COLUMN "mime_type" TYPE VARCHAR(128);
COMMENT ON COLUMN "file_meta"."mime_type" IS '文件 MIME 类型（Office 文档的 MIME 比较长，留到 128）';

-- Chapter 24: ticket and SLA schema
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

-- Chapter 25: agent metrics schema
-- 云梯智能客服平台 · customer_db 数据大屏与坐席绩效增量脚本（PostgreSQL 17）
-- 用途：把"报表要用的数字"按 坐席 × 天 预聚合出来，供大屏与绩效报表快速读取。
--   新增 agent_daily_metric：一天的接待量 / 消息量 / 首响时长 / 会话时长 / 满意度 / 转接次数…
-- 幂等：可重复执行（CREATE TABLE IF NOT EXISTS + 索引 IF NOT EXISTS）。
--
-- 为什么要预聚合而不是每次现算：
--   1. 绩效报表要按坐席筛选、排序、分页、再下钻，现算就得对 session_message 做全表聚合，
--      数据量一大就拖死列表；
--   2. 大屏要 5 秒刷新一次，每次都全表扫是浪费——预聚合之后大屏只读今天的几十行。
--   重算是**幂等**的：同一个坐席同一天重算多少次结果都一样（DELETE + INSERT 或 UPSERT）。

CREATE TABLE IF NOT EXISTS "agent_daily_metric" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "agent_name" VARCHAR(64) DEFAULT NULL,
  "stat_date" DATE NOT NULL,
  "session_count" INT NOT NULL DEFAULT 0,
  "human_session_count" INT NOT NULL DEFAULT 0,
  "message_count" INT NOT NULL DEFAULT 0,
  "customer_message_count" INT NOT NULL DEFAULT 0,
  "first_response_seconds" INT NOT NULL DEFAULT 0,
  "avg_response_seconds" INT NOT NULL DEFAULT 0,
  "avg_session_seconds" INT NOT NULL DEFAULT 0,
  "transfer_count" INT NOT NULL DEFAULT 0,
  "csat_count" INT NOT NULL DEFAULT 0,
  "csat_score" NUMERIC(5,2) DEFAULT NULL,
  "good_csat_count" INT NOT NULL DEFAULT 0,
  "close_count" INT NOT NULL DEFAULT 0,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_agent_date" UNIQUE ("tenant_code", "agent_id", "stat_date")
);

-- 报表默认按"最近 30 天 + 租户"筛，再按坐席聚合；这条索引撑住它
CREATE INDEX IF NOT EXISTS "idx_agent_metric_tenant_date"
    ON "agent_daily_metric" ("tenant_code", "stat_date" DESC);

COMMENT ON TABLE "agent_daily_metric" IS '坐席绩效日聚合表（按 坐席 × 天 预聚合，报表与大屏都读它）';
COMMENT ON COLUMN "agent_daily_metric"."agent_id" IS '坐席用户ID（0 表示"未分配/机器人接待"这一行）';
COMMENT ON COLUMN "agent_daily_metric"."stat_date" IS '统计日期（按自然日）';
COMMENT ON COLUMN "agent_daily_metric"."session_count" IS '接待会话数（含机器人阶段后转过来的）';
COMMENT ON COLUMN "agent_daily_metric"."human_session_count" IS '人工接待过的会话数';
COMMENT ON COLUMN "agent_daily_metric"."message_count" IS '这条坐席发的消息数（不含内部备注？含，备注也是坐席产出）';
COMMENT ON COLUMN "agent_daily_metric"."customer_message_count" IS '客户发的消息数（用来算"人均沟通量"）';
COMMENT ON COLUMN "agent_daily_metric"."first_response_seconds" IS '首次响应时长均值（秒）：客户最后一条 → 坐席第一条，按会话平均';
COMMENT ON COLUMN "agent_daily_metric"."avg_response_seconds" IS '平均响应时长（秒）：坐席每次回复距客户上一条消息的平均间隔';
COMMENT ON COLUMN "agent_daily_metric"."avg_session_seconds" IS '平均会话时长（秒）：会话开始到结束';
COMMENT ON COLUMN "agent_daily_metric"."transfer_count" IS '转接次数（session_event 的转接事件）';
COMMENT ON COLUMN "agent_daily_metric"."csat_count" IS '收到的评价条数';
COMMENT ON COLUMN "agent_daily_metric"."csat_score" IS '满意度均值（1~5）';
COMMENT ON COLUMN "agent_daily_metric"."good_csat_count" IS '好评数（4~5 分）';
COMMENT ON COLUMN "agent_daily_metric"."close_count" IS '结束的会话数（状态 4）';
