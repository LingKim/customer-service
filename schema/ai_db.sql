CREATE TABLE IF NOT EXISTS bot_intent (
  id BIGINT NOT NULL,
  tenant_code VARCHAR(16) NOT NULL,
  intent_code VARCHAR(64) NOT NULL,
  name VARCHAR(64) NOT NULL,
  status SMALLINT NOT NULL DEFAULT 1,
  confidence NUMERIC(5, 2),
  hit_count INT NOT NULL DEFAULT 0,
  samples TEXT,
  escalate BOOLEAN NOT NULL DEFAULT FALSE,
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
  reception_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  transfer_on_anger BOOLEAN NOT NULL DEFAULT TRUE,
  transfer_after_unresolved INTEGER NOT NULL DEFAULT 2,
  transfer_keywords VARCHAR(255),
  transfer_message VARCHAR(255),
  is_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  model_key VARCHAR(64),
  temperature NUMERIC(3, 2) NOT NULL DEFAULT 0.30,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64), editor VARCHAR(64), is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_bot_setting_temperature CHECK (temperature BETWEEN 0 AND 2),
  PRIMARY KEY (id), CONSTRAINT uk_tenant_bot_setting UNIQUE (tenant_code)
);

CREATE TABLE IF NOT EXISTS bot_dialogue (
  id BIGINT NOT NULL PRIMARY KEY,
  tenant_code VARCHAR(16) NOT NULL,
  session_no VARCHAR(40) NOT NULL,
  turn_count INT NOT NULL DEFAULT 0,
  unresolved_rounds INT NOT NULL DEFAULT 0,
  last_intent VARCHAR(64),
  last_intent_code VARCHAR(64),
  last_confidence NUMERIC(5,2),
  last_emotion VARCHAR(20),
  emotion_score NUMERIC(5,2),
  slots TEXT,
  transferred BOOLEAN NOT NULL DEFAULT FALSE,
  transfer_reason VARCHAR(255),
  last_message VARCHAR(500),
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64),
  editor VARCHAR(64),
  is_deleted BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_tenant_session_dialogue
    ON bot_dialogue (tenant_code, session_no) WHERE is_deleted = FALSE;
CREATE INDEX IF NOT EXISTS idx_bot_dialogue_tenant_update
    ON bot_dialogue (tenant_code, update_time DESC);
