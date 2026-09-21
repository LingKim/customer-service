CREATE TABLE IF NOT EXISTS enterprise (
  id BIGINT NOT NULL, enterprise_code VARCHAR(16) NOT NULL, tenant_code VARCHAR(16), company_name VARCHAR(128) NOT NULL,
  industry VARCHAR(32), scale VARCHAR(32), license_no VARCHAR(64), register_address VARCHAR(255), legal_person VARCHAR(64),
  license_file_id BIGINT, contact_name VARCHAR(64), contact_phone VARCHAR(20), contact_email VARCHAR(128),
  status SMALLINT NOT NULL DEFAULT 1, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, creator VARCHAR(64), editor VARCHAR(64), is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_enterprise_status CHECK (status IN (1, 2, 3, 4)),
  CONSTRAINT ck_enterprise_code_len CHECK (char_length(enterprise_code) = 16),
  PRIMARY KEY (id), CONSTRAINT uk_enterprise_code UNIQUE (enterprise_code)
);

CREATE TABLE IF NOT EXISTS enterprise_review (
  id BIGINT NOT NULL, enterprise_id BIGINT NOT NULL, tenant_code VARCHAR(16), apply_no VARCHAR(32) NOT NULL,
  version_no SMALLINT NOT NULL DEFAULT 1, applicant_id BIGINT NOT NULL, company_name VARCHAR(128) NOT NULL,
  industry VARCHAR(32), scale VARCHAR(32), contact_name VARCHAR(64) NOT NULL, contact_phone VARCHAR(20),
  contact_email VARCHAR(128), license_no VARCHAR(64), register_address VARCHAR(255), legal_person VARCHAR(64),
  license_file_id BIGINT, status SMALLINT NOT NULL DEFAULT 1, reviewer_id BIGINT, review_time TIMESTAMP,
  reject_reason VARCHAR(512), submit_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  creator VARCHAR(64), editor VARCHAR(64), is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_enterprise_review_status CHECK (status IN (1, 2, 3)),
  PRIMARY KEY (id),
  CONSTRAINT uk_enterprise_review_apply_no UNIQUE (apply_no),
  CONSTRAINT uk_enterprise_review_enterprise_version UNIQUE (enterprise_id, version_no)
);

CREATE INDEX IF NOT EXISTS idx_enterprise_review_status_submit
  ON enterprise_review (status, submit_time);
