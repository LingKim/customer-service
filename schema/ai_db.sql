CREATE TABLE IF NOT EXISTS bot_intent (
  id BIGINT NOT NULL,
  tenant_code VARCHAR(16) NOT NULL,
  intent_code VARCHAR(64) NOT NULL,
  name VARCHAR(64) NOT NULL,
  status SMALLINT NOT NULL DEFAULT 1,
  confidence NUMERIC(5, 2),
  hit_count INT NOT NULL DEFAULT 0,
  samples TEXT,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64), editor VARCHAR(64), is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_bot_intent_status CHECK (status IN (1, 2)),
  PRIMARY KEY (id), CONSTRAINT uk_tenant_intent UNIQUE (tenant_code, intent_code)
);

CREATE TABLE IF NOT EXISTS bot_model (
  id BIGINT NOT NULL,
  tenant_code VARCHAR(16) NOT NULL,
  model_key VARCHAR(64) NOT NULL,
  model_name VARCHAR(64) NOT NULL,
  provider VARCHAR(32) NOT NULL,
  model_type SMALLINT NOT NULL,
  temperature NUMERIC(3, 2) NOT NULL DEFAULT 0.30,
  is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64), editor VARCHAR(64), is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_bot_model_type CHECK (model_type IN (1, 2, 3)),
  CONSTRAINT ck_bot_model_temperature CHECK (temperature BETWEEN 0 AND 2),
  PRIMARY KEY (id), CONSTRAINT uk_tenant_model UNIQUE (tenant_code, model_key)
);

CREATE TABLE IF NOT EXISTS bot_setting (
  id BIGINT NOT NULL,
  tenant_code VARCHAR(16) NOT NULL,
  bot_name VARCHAR(64) NOT NULL DEFAULT '小云',
  welcome_message VARCHAR(255),
  fallback_message VARCHAR(255),
  transfer_prompt VARCHAR(255),
  is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  model_key VARCHAR(64),
  temperature NUMERIC(3, 2) NOT NULL DEFAULT 0.30,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64), editor VARCHAR(64), is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_bot_setting_temperature CHECK (temperature BETWEEN 0 AND 2),
  PRIMARY KEY (id), CONSTRAINT uk_tenant_bot_setting UNIQUE (tenant_code)
);
