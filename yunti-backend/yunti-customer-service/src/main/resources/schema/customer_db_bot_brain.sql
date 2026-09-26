-- AI 客服大脑（可重复执行）：
--   1. session 加 bot_transfer_reason：机器人为什么把这单转给人工；
--   2. 放开会话流转类型到 6，新增"6-机器人转人工"。
--
-- 为什么原因要存在 session 上、而不是只写进 session_event：
-- 坐席打开会话时第一眼就要看到"这单为什么转过来"（客户在骂人？机器人答不上来？），
-- 而流转记录是详情页才展开的东西。放在 session 上，列表和会话头部都能直接显示，
-- 前端也不用为了这一行字多打一次接口。

ALTER TABLE "session"
    ADD COLUMN IF NOT EXISTS "bot_transfer_reason" VARCHAR(255);

COMMENT ON COLUMN "session"."bot_transfer_reason" IS '机器人转人工的原因（客户情绪激动 / 答不上来 / 命中转人工意图等）';
COMMENT ON COLUMN "session"."intent" IS '智能客服识别出的意图（由 AI 客服大脑写入）';
COMMENT ON COLUMN "session"."emotion" IS '智能客服识别出的客户情绪：中性/焦虑/不满/愤怒';

-- 放开会话流转类型限制到 6（可重复执行：先删后加）
ALTER TABLE "session_event" DROP CONSTRAINT IF EXISTS "ck_session_event_event_type";
ALTER TABLE "session_event"
    ADD CONSTRAINT "ck_session_event_event_type" CHECK ("event_type" IN (1,2,3,4,5,6));

COMMENT ON COLUMN "session_event"."event_type"
    IS '事件类型码：1-转接、2-升级、3-分配、4-关闭、5-超时、6-机器人转人工';
