-- 边聊边检 · 响应超时提醒（可重复执行）：--   1. qa_rule 加 timeout_seconds：这个租户"客户发完消息多久没回就算超时"；--   2. 放开规则类型限制，允许 5-响应超时（时效类规则，不看话术内容、看时长）；--   3. 给已有租户补一条默认的「响应超时」规则（60 秒，警告级）。---- 为什么单独一类规则：敏感词、承诺、情绪都是"看这句话说了什么"，-- 而响应超时是"看这话晾了多久"，判定时机也不一样——它由定时扫描触发，不是消息触发。

ALTER TABLE "qa_rule"
    ADD COLUMN IF NOT EXISTS "timeout_seconds" INTEGER NOT NULL DEFAULT 60;

COMMENT ON COLUMN "qa_rule"."timeout_seconds" IS '响应超时秒数（仅"5-响应超时"类规则使用）：客户发完消息超过这个时长没被回复就告警';

-- 放开类型限制到 5（可重复执行：先删后加）
ALTER TABLE "qa_rule" DROP CONSTRAINT IF EXISTS "ck_qa_rule_rule_type";
ALTER TABLE "qa_rule"
    ADD CONSTRAINT "ck_qa_rule_rule_type" CHECK ("rule_type" IN (1,2,3,4,5));

-- 给还没有"响应超时"规则的租户补一条默认规则（装完就生效）
INSERT INTO "qa_rule" ("id", "tenant_code", "rule_name", "rule_type", "rule_content", "weight",
                       "is_enabled", "is_realtime", "hit_keywords", "severity", "timeout_seconds",
                       "create_time", "update_time", "creator", "is_deleted")
SELECT (EXTRACT(EPOCH FROM clock_timestamp()) * 1000000)::BIGINT + row_number() OVER (),
       t."tenant_code",
       '响应超时',
       5,
       '客户发出消息后长时间没有坐席回复，需要提醒并及时响应',
       25,
       TRUE,
       TRUE,
       NULL,
       2,
       60,
       CURRENT_TIMESTAMP,
       CURRENT_TIMESTAMP,
       'SYSTEM',
       FALSE
  FROM (SELECT DISTINCT "tenant_code" FROM "qa_rule" WHERE "is_deleted" = FALSE) t
 WHERE NOT EXISTS (
        SELECT 1 FROM "qa_rule" r
         WHERE r."tenant_code" = t."tenant_code"
           AND r."rule_type" = 5
           AND r."is_deleted" = FALSE
       );
