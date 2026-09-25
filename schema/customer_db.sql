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
