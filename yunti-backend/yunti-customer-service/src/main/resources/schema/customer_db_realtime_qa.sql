-- 会话实时质检（边聊边检，可重复执行）：--   1. qa_rule 补三列：是否参与实时质检、命中词表、告警级别；--   2. 新增 qa_alert：一句话一条告警，坐席当场就能看到；--   3. 补两个查询索引（按会话看、按处理状态看）。---- 与"批量质检"的分工：批量质检跑在会话结束之后、可以慢慢调 AI；-- 实时质检跑在消息落库的同一时刻，只做"能在毫秒级判定"的规则（敏感词 / 绝对化承诺 / 情绪安抚），-- 所以它不依赖大模型，AI 不可用时也不会漏检。

ALTER TABLE "qa_rule"
    ADD COLUMN IF NOT EXISTS "is_realtime" BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE "qa_rule"
    ADD COLUMN IF NOT EXISTS "hit_keywords" VARCHAR(512) DEFAULT NULL;

ALTER TABLE "qa_rule"
    ADD COLUMN IF NOT EXISTS "severity" SMALLINT NOT NULL DEFAULT 2;

COMMENT ON COLUMN "qa_rule"."is_realtime" IS '是否参与实时质检：关掉就只在会话结束后批量质检';
COMMENT ON COLUMN "qa_rule"."hit_keywords" IS '命中词表（逗号分隔）：敏感词类规则命中任意一个就告警';
COMMENT ON COLUMN "qa_rule"."severity" IS '告警级别：1-提示、2-警告、3-严重';

-- 默认的敏感词规则补一份可用的命中词，装完就能看到实时预警效果
UPDATE "qa_rule"
   SET "hit_keywords" = '妈的,废物,滚,随便你,投诉也没用,爱咋咋地,你懂不懂,自己去查',
       "severity" = 3,
       "update_time" = CURRENT_TIMESTAMP
 WHERE "rule_type" = 1
   AND "is_deleted" = FALSE
   AND ("hit_keywords" IS NULL OR "hit_keywords" = '');

CREATE TABLE IF NOT EXISTS "qa_alert" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "session_no" VARCHAR(40) NOT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "message_id" BIGINT DEFAULT NULL,
  "message_seq" BIGINT DEFAULT NULL,
  "rule_id" BIGINT DEFAULT NULL,
  "rule_name" VARCHAR(64) NOT NULL,
  "rule_type" SMALLINT NOT NULL,
  "severity" SMALLINT NOT NULL DEFAULT 2,
  "hit_keyword" VARCHAR(128) DEFAULT NULL,
  "snippet" VARCHAR(512) DEFAULT NULL,
  "advice" VARCHAR(512) DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "handler_id" BIGINT DEFAULT NULL,
  "handle_remark" VARCHAR(512) DEFAULT NULL,
  "handle_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_alert_severity CHECK ("severity" IN (1,2,3)),
  CONSTRAINT ck_qa_alert_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id")
);

COMMENT ON TABLE "qa_alert" IS '会话实时质检告警';
COMMENT ON COLUMN "qa_alert"."session_no" IS '会话号（坐席点告警直接跳到这条会话）';
COMMENT ON COLUMN "qa_alert"."agent_id" IS '告警发生时该会话的负责坐席';
COMMENT ON COLUMN "qa_alert"."message_id" IS '触发告警的消息 ID';
COMMENT ON COLUMN "qa_alert"."rule_name" IS '命中的规则名';
COMMENT ON COLUMN "qa_alert"."hit_keyword" IS '命中的词（敏感词类规则才有）';
COMMENT ON COLUMN "qa_alert"."snippet" IS '命中片段（截取消息上下文，便于坐席定位）';
COMMENT ON COLUMN "qa_alert"."advice" IS '处置建议';
COMMENT ON COLUMN "qa_alert"."status" IS '状态码：1-待处理、2-已处理';

CREATE INDEX IF NOT EXISTS "idx_qa_alert_tenant_session"
    ON "qa_alert" ("tenant_code", "session_no", "create_time");

CREATE INDEX IF NOT EXISTS "idx_qa_alert_tenant_status"
    ON "qa_alert" ("tenant_code", "status", "create_time");

-- 同一条消息 + 同一条规则只告警一次：消息幂等重发时不会重复弹窗
CREATE UNIQUE INDEX IF NOT EXISTS "uk_qa_alert_message_rule"
    ON "qa_alert" ("tenant_code", "message_id", "rule_id")
    WHERE "is_deleted" = FALSE AND "message_id" IS NOT NULL AND "rule_id" IS NOT NULL;
