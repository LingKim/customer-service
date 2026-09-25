-- =============================================================================
-- 在线客服工作台（多坐席接待、转接与会话协同）增量脚本
-- 只加一个字段：消息的可见范围。会话事件直接复用已有的 session_event 表。
-- 可重复执行。
-- =============================================================================

ALTER TABLE "session_message"
    ADD COLUMN IF NOT EXISTS "visible_to" SMALLINT NOT NULL DEFAULT 1;

COMMENT ON COLUMN "session_message"."visible_to" IS '可见范围码：1-客户与坐席都可见、2-仅坐席可见（内部备注）';

-- 老库补约束（已存在时会报重复，用 DO 块兜住，保证脚本可重复执行）
DO $$
BEGIN
    ALTER TABLE "session_message"
        ADD CONSTRAINT ck_session_message_visible_to CHECK ("visible_to" IN (1,2));
EXCEPTION
    WHEN duplicate_object THEN NULL;
END $$;
