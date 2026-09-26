-- 坐席状态与智能路由（可重复执行）：
--   1. 新增 agent_status：坐席在线/忙碌/小休 + 最多同时接待几单；
--   2. skill_group 补 overflow_after_seconds：排队超过这个秒数就升级（放宽技能组限制）；
--   3. 补 skill_group_member：技能组坐席绑定；
--   4. 补路由所需索引。

CREATE TABLE IF NOT EXISTS "agent_status" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "agent_id" BIGINT NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "max_concurrency" SMALLINT NOT NULL DEFAULT 5,
  "is_connected" BOOLEAN NOT NULL DEFAULT FALSE,
  "status_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_agent_status_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_agent_status" UNIQUE ("tenant_code", "agent_id")
);

COMMENT ON TABLE "agent_status" IS '坐席状态表';
COMMENT ON COLUMN "agent_status"."agent_id" IS '坐席用户ID';
COMMENT ON COLUMN "agent_status"."status" IS '状态码：1-在线、2-忙碌、3-小休';
COMMENT ON COLUMN "agent_status"."max_concurrency" IS '最多同时接待的会话数';
COMMENT ON COLUMN "agent_status"."is_connected" IS '长连接是否在线（由实时网关维护，路由只分配在线坐席）';
COMMENT ON COLUMN "agent_status"."status_time" IS '状态变更时间（同负载时优先分配给更久没换状态的坐席）';

CREATE INDEX IF NOT EXISTS "idx_agent_status_tenant_status"
    ON "agent_status" ("tenant_code", "status");
ALTER TABLE "agent_status" ADD COLUMN IF NOT EXISTS "is_connected" BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE "skill_group"
    ADD COLUMN IF NOT EXISTS "overflow_after_seconds" INTEGER NOT NULL DEFAULT 60;

CREATE TABLE IF NOT EXISTS "skill_group_member" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "skill_group_id" BIGINT NOT NULL,
  "user_id" BIGINT NOT NULL,
  "is_leader" BOOLEAN NOT NULL DEFAULT FALSE,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64),
  "editor" VARCHAR(64),
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_skill_group_member_status CHECK ("status" IN (1, 2)),
  PRIMARY KEY ("id"),
  CONSTRAINT uk_skill_group_member UNIQUE ("skill_group_id", "user_id")
);

CREATE INDEX IF NOT EXISTS "idx_skill_group_member_tenant"
    ON "skill_group_member" ("tenant_code", "skill_group_id");

COMMENT ON COLUMN "skill_group"."overflow_after_seconds"
    IS '排队超过这个秒数就升级：放宽技能组限制，交给其它在线坐席（0 表示不升级）';

-- 排队扫描：按租户取"没人负责且没结束"的会话，先来先服务
CREATE INDEX IF NOT EXISTS "idx_session_queue"
    ON "session" ("tenant_code", "start_time")
    WHERE "agent_id" IS NULL AND "is_deleted" = FALSE;
