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
