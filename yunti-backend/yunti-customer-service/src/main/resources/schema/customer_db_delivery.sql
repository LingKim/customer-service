-- 消息必达（可重复执行）：
--   1. client_msg_no：客户端幂等键，同一条消息重发多少次都只落一条；
--   2. seq：会话内序号，保证双方看到的顺序一致；
--   3. session.last_msg_seq：会话当前最大序号，重连后据此做增量补拉。

ALTER TABLE "session_message"
    ADD COLUMN IF NOT EXISTS "client_msg_no" VARCHAR(64) DEFAULT NULL;

ALTER TABLE "session_message"
    ADD COLUMN IF NOT EXISTS "seq" BIGINT NOT NULL DEFAULT 0;

ALTER TABLE "session"
    ADD COLUMN IF NOT EXISTS "last_msg_seq" BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN "session_message"."client_msg_no" IS '客户端消息号（幂等键，同一条消息重发只落一条）';
COMMENT ON COLUMN "session_message"."seq" IS '会话内序号（从 1 开始，双方按它排序）';
COMMENT ON COLUMN "session"."last_msg_seq" IS '会话已分配的最大消息序号';

-- 幂等：同一个会话里，同一个 client_msg_no 只允许存在一条
CREATE UNIQUE INDEX IF NOT EXISTS "uk_session_client_msg"
    ON "session_message" ("tenant_code", "session_id", "client_msg_no")
    WHERE "client_msg_no" IS NOT NULL;

-- 增量补拉：按 (会话, 序号) 取一页，走索引
CREATE INDEX IF NOT EXISTS "idx_session_message_seq"
    ON "session_message" ("tenant_code", "session_id", "seq");

-- 历史数据回填：按 id 顺序给每个会话重排 seq
WITH ranked AS (
    SELECT "id", row_number() OVER (PARTITION BY "tenant_code", "session_id" ORDER BY "id") AS rn
      FROM "session_message"
     WHERE "is_deleted" = FALSE
)
UPDATE "session_message" m
   SET "seq" = r.rn
  FROM ranked r
 WHERE m."id" = r."id"
   AND m."seq" = 0;

-- 会话的 last_msg_seq 与消息表对齐（可重复执行）
UPDATE "session" s
   SET "last_msg_seq" = COALESCE((
           SELECT MAX(m."seq") FROM "session_message" m WHERE m."session_id" = s."id"
       ), 0),
       "update_time" = CURRENT_TIMESTAMP
 WHERE s."last_msg_seq" <> COALESCE((
           SELECT MAX(m."seq") FROM "session_message" m WHERE m."session_id" = s."id"
       ), 0);
