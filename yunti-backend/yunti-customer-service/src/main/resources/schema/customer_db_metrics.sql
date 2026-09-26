-- 云梯智能客服平台 · customer_db 数据大屏与坐席绩效增量脚本（PostgreSQL 17）
-- 用途：把"报表要用的数字"按 坐席 × 天 预聚合出来，供大屏与绩效报表快速读取。
--   新增 agent_daily_metric：一天的接待量 / 消息量 / 首响时长 / 会话时长 / 满意度 / 转接次数…
-- 幂等：可重复执行（CREATE TABLE IF NOT EXISTS + 索引 IF NOT EXISTS）。
--
-- 为什么要预聚合而不是每次现算：
--   1. 绩效报表要按坐席筛选、排序、分页、再下钻，现算就得对 session_message 做全表聚合，
--      数据量一大就拖死列表；
--   2. 大屏要 5 秒刷新一次，每次都全表扫是浪费——预聚合之后大屏只读今天的几十行。
--   重算是**幂等**的：同一个坐席同一天重算多少次结果都一样（DELETE + INSERT 或 UPSERT）。

CREATE TABLE IF NOT EXISTS "agent_daily_metric" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "agent_name" VARCHAR(64) DEFAULT NULL,
  "stat_date" DATE NOT NULL,
  "session_count" INT NOT NULL DEFAULT 0,
  "human_session_count" INT NOT NULL DEFAULT 0,
  "message_count" INT NOT NULL DEFAULT 0,
  "customer_message_count" INT NOT NULL DEFAULT 0,
  "first_response_seconds" INT NOT NULL DEFAULT 0,
  "avg_response_seconds" INT NOT NULL DEFAULT 0,
  "avg_session_seconds" INT NOT NULL DEFAULT 0,
  "transfer_count" INT NOT NULL DEFAULT 0,
  "csat_count" INT NOT NULL DEFAULT 0,
  "csat_score" NUMERIC(5,2) DEFAULT NULL,
  "good_csat_count" INT NOT NULL DEFAULT 0,
  "close_count" INT NOT NULL DEFAULT 0,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_agent_date" UNIQUE ("tenant_code", "agent_id", "stat_date")
);

-- 报表默认按"最近 30 天 + 租户"筛，再按坐席聚合；这条索引撑住它
CREATE INDEX IF NOT EXISTS "idx_agent_metric_tenant_date"
    ON "agent_daily_metric" ("tenant_code", "stat_date" DESC);

COMMENT ON TABLE "agent_daily_metric" IS '坐席绩效日聚合表（按 坐席 × 天 预聚合，报表与大屏都读它）';
COMMENT ON COLUMN "agent_daily_metric"."agent_id" IS '坐席用户ID（0 表示"未分配/机器人接待"这一行）';
COMMENT ON COLUMN "agent_daily_metric"."stat_date" IS '统计日期（按自然日）';
COMMENT ON COLUMN "agent_daily_metric"."session_count" IS '接待会话数（含机器人阶段后转过来的）';
COMMENT ON COLUMN "agent_daily_metric"."human_session_count" IS '人工接待过的会话数';
COMMENT ON COLUMN "agent_daily_metric"."message_count" IS '这条坐席发的消息数（不含内部备注？含，备注也是坐席产出）';
COMMENT ON COLUMN "agent_daily_metric"."customer_message_count" IS '客户发的消息数（用来算"人均沟通量"）';
COMMENT ON COLUMN "agent_daily_metric"."first_response_seconds" IS '首次响应时长均值（秒）：客户最后一条 → 坐席第一条，按会话平均';
COMMENT ON COLUMN "agent_daily_metric"."avg_response_seconds" IS '平均响应时长（秒）：坐席每次回复距客户上一条消息的平均间隔';
COMMENT ON COLUMN "agent_daily_metric"."avg_session_seconds" IS '平均会话时长（秒）：会话开始到结束';
COMMENT ON COLUMN "agent_daily_metric"."transfer_count" IS '转接次数（session_event 的转接事件）';
COMMENT ON COLUMN "agent_daily_metric"."csat_count" IS '收到的评价条数';
COMMENT ON COLUMN "agent_daily_metric"."csat_score" IS '满意度均值（1~5）';
COMMENT ON COLUMN "agent_daily_metric"."good_csat_count" IS '好评数（4~5 分）';
COMMENT ON COLUMN "agent_daily_metric"."close_count" IS '结束的会话数（状态 4）';
