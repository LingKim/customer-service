-- 云梯智能客服平台 · user_db 成员邀请增量脚本（PostgreSQL 17）
-- 用途：在已按注册/登录教程建库的本地环境补齐邀请客服成员所需表。
-- 幂等：可重复执行，不会与已存在的表冲突。

CREATE TABLE IF NOT EXISTS "tenant_member" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "join_time" TIMESTAMP DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_member_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_user" UNIQUE ("tenant_code", "user_id")
);

CREATE TABLE IF NOT EXISTS "sys_role" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "role_code" VARCHAR(64) NOT NULL,
  "role_name" VARCHAR(64) NOT NULL,
  "role_type" SMALLINT NOT NULL,
  "is_fixed" BOOLEAN NOT NULL DEFAULT FALSE,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "remark" VARCHAR(255) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_sys_role_role_type CHECK ("role_type" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_role_code" UNIQUE ("tenant_code", "role_code")
);

CREATE TABLE IF NOT EXISTS "sys_user_role" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "user_id" BIGINT NOT NULL,
  "role_id" BIGINT NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_user_role" UNIQUE ("tenant_code", "user_id", "role_id")
);

CREATE TABLE IF NOT EXISTS "member_invite" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "invite_code" VARCHAR(32) NOT NULL,
  "inviter_id" BIGINT NOT NULL,
  "role_id" BIGINT NOT NULL,
  "invitee_phone" VARCHAR(20) DEFAULT NULL,
  "invitee_email" VARCHAR(128) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "used_by" BIGINT DEFAULT NULL,
  "used_time" TIMESTAMP DEFAULT NULL,
  "expire_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_member_invite_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_member_invite_code" UNIQUE ("tenant_code", "invite_code")
);

CREATE INDEX IF NOT EXISTS "idx_tenant_member_user_id" ON "tenant_member" ("user_id");
CREATE INDEX IF NOT EXISTS "idx_member_invite_tenant_status" ON "member_invite" ("tenant_code", "status");
CREATE UNIQUE INDEX IF NOT EXISTS "uk_member_invite_global_code" ON "member_invite" ("invite_code");
