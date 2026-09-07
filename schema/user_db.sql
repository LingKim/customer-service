CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT NOT NULL, tenant_code VARCHAR(16) NOT NULL, user_no VARCHAR(32) NOT NULL,
  name VARCHAR(64) NOT NULL, phone VARCHAR(20), email VARCHAR(128), password VARCHAR(128), avatar VARCHAR(512),
  user_type SMALLINT NOT NULL, status SMALLINT NOT NULL DEFAULT 1, last_login_time TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64), editor VARCHAR(64), is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_user_user_type CHECK (user_type IN (1, 2)),
  CONSTRAINT ck_sys_user_status CHECK (status IN (1, 2, 3)),
  PRIMARY KEY (id), CONSTRAINT uk_user_no UNIQUE (user_no)
);

CREATE TABLE IF NOT EXISTS login_log (
  id BIGINT NOT NULL, tenant_code VARCHAR(16) NOT NULL, user_id BIGINT NOT NULL, login_type SMALLINT NOT NULL,
  ip VARCHAR(64), device VARCHAR(128), browser VARCHAR(64), region VARCHAR(64), result SMALLINT NOT NULL,
  login_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_login_log_login_type CHECK (login_type IN (1, 2, 3)),
  CONSTRAINT ck_login_log_result CHECK (result IN (1, 2, 3)), PRIMARY KEY (id)
);
