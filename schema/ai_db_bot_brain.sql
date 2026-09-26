-- AI 客服大脑 · 意图识别 / 多轮对话 / 情绪识别 / 自动转人工（可重复执行）：
--   1. bot_setting 补"接待策略"：机器人首轮接待、情绪激动转人工、连续未解决转人工、转人工关键词；
--   2. bot_intent 补 escalate：命中该意图直接转人工（不再让机器人硬答）；
--   3. 新增 bot_dialogue：一个会话的机器人对话状态（轮次 / 未解决次数 / 意图 / 情绪 / 槽位）；
--   4. 给已有租户补默认策略，并给"人工客服"这类意图打上直接转人工标记。
--
-- 为什么要独立一张 bot_dialogue：多轮对话必须有"记忆"。
-- 只靠把历史消息重新喂给模型是"无状态"的，判断不了"已经问了三轮还没解决，该转人工了"，
-- 也留不住"客户上一条报了订单号"这种槽位。状态放这里，会话一结束就跟着归档。

-- ---------- 1. bot_setting：接待策略 ----------
ALTER TABLE "bot_setting"
    ADD COLUMN IF NOT EXISTS "reception_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS "transfer_on_anger" BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS "transfer_after_unresolved" INTEGER NOT NULL DEFAULT 2,
    ADD COLUMN IF NOT EXISTS "transfer_keywords" VARCHAR(255),
    ADD COLUMN IF NOT EXISTS "transfer_message" VARCHAR(255);

COMMENT ON COLUMN "bot_setting"."reception_enabled" IS '是否由机器人首轮接待：关闭后客户进线直接进人工队列';
COMMENT ON COLUMN "bot_setting"."transfer_on_anger" IS '客户情绪激动时是否自动转人工';
COMMENT ON COLUMN "bot_setting"."transfer_after_unresolved" IS '连续多少轮没解决就自动转人工（0=从不）';
COMMENT ON COLUMN "bot_setting"."transfer_keywords" IS '命中即转人工的关键词，逗号分隔';
COMMENT ON COLUMN "bot_setting"."transfer_message" IS '转接中话术：客户要求人工后回的那一句（与"转人工提示"是两回事）';

-- 转接中话术与"转人工提示"是两个字段，别混用：
--   transfer_prompt  —— 提示语："如需人工客服，请回复转人工…"，出现在机器人答不上来的回答里；
--   transfer_message —— 确认语："好的，正在为您转接人工客服，请稍候。"，真的开始转人工时说。
-- 早先把提示语当成了确认语，客户说完"转人工"，机器人回他"请回复转人工"，看起来就像没转。
UPDATE "bot_setting"
   SET "transfer_message" = '好的，正在为您转接人工客服，请稍候。'
 WHERE "transfer_message" IS NULL;

-- 老数据补一个默认关键词表（只在没配过的时候写，不覆盖用户已经改过的值）
UPDATE "bot_setting"
   SET "transfer_keywords" = '转人工,人工客服,找人工,要人工,人工'
 WHERE "transfer_keywords" IS NULL;

-- ---------- 2. bot_intent：直接转人工标记 ----------
ALTER TABLE "bot_intent" ADD COLUMN IF NOT EXISTS "escalate" BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN "bot_intent"."escalate" IS '命中该意图是否直接转人工（人工客服、投诉这类意图）';

-- 名称里带"人工 / 投诉"的意图默认直接转人工
UPDATE "bot_intent"
   SET "escalate" = TRUE
 WHERE "escalate" = FALSE
   AND ("name" LIKE '%人工%' OR "name" LIKE '%投诉%' OR "name" LIKE '%举报%');

-- ---------- 3. bot_dialogue：多轮对话状态 ----------
CREATE TABLE IF NOT EXISTS "bot_dialogue" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_no" VARCHAR(40) NOT NULL,
  "turn_count" INT NOT NULL DEFAULT 0,
  "unresolved_rounds" INT NOT NULL DEFAULT 0,
  "last_intent" VARCHAR(64) DEFAULT NULL,
  "last_intent_code" VARCHAR(64) DEFAULT NULL,
  "last_confidence" NUMERIC(5,2) DEFAULT NULL,
  "last_emotion" VARCHAR(20) DEFAULT NULL,
  "emotion_score" NUMERIC(5,2) DEFAULT NULL,
  "slots" TEXT,
  "transferred" BOOLEAN NOT NULL DEFAULT FALSE,
  "transfer_reason" VARCHAR(255) DEFAULT NULL,
  "last_message" VARCHAR(500) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "bot_dialogue" IS '机器人多轮对话状态表（一个会话一条）';
COMMENT ON COLUMN "bot_dialogue"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "bot_dialogue"."session_no" IS '会话编号（对应 customer_db.session.session_no）';
COMMENT ON COLUMN "bot_dialogue"."turn_count" IS '机器人已经接待的轮次';
COMMENT ON COLUMN "bot_dialogue"."unresolved_rounds" IS '连续未解决轮次：答不上来/没查到就加一，答上了清零';
COMMENT ON COLUMN "bot_dialogue"."last_intent" IS '最近一次识别的意图名称';
COMMENT ON COLUMN "bot_dialogue"."last_intent_code" IS '最近一次识别的意图编码';
COMMENT ON COLUMN "bot_dialogue"."last_confidence" IS '最近一次意图置信度（0-100）';
COMMENT ON COLUMN "bot_dialogue"."last_emotion" IS '最近一次识别的情绪：中性/不满/愤怒/焦虑';
COMMENT ON COLUMN "bot_dialogue"."emotion_score" IS '负面情绪强度（0-100），越高越该转人工';
COMMENT ON COLUMN "bot_dialogue"."slots" IS '多轮槽位（JSON）：从对话里抽出来的订单号、手机号等';
COMMENT ON COLUMN "bot_dialogue"."transferred" IS '是否已经转人工';
COMMENT ON COLUMN "bot_dialogue"."transfer_reason" IS '转人工原因';
COMMENT ON COLUMN "bot_dialogue"."last_message" IS '客户最后一句话（截断保存，列表展示用）';

-- 一个租户下一个会话只有一条状态（软删的不算，便于保留历史）
CREATE UNIQUE INDEX IF NOT EXISTS "uk_tenant_session_dialogue"
    ON "bot_dialogue" ("tenant_code", "session_no")
 WHERE "is_deleted" = FALSE;

CREATE INDEX IF NOT EXISTS "idx_bot_dialogue_tenant_update"
    ON "bot_dialogue" ("tenant_code", "update_time" DESC);
