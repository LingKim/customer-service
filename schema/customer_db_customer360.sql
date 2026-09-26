-- 云梯智能客服平台 · customer_db 客户 360 视图增量脚本（PostgreSQL 17）
-- 用途：客户从"会话里那一列客户名"变成"坐席接单前就能看懂的人"
--   1. customer 补：备注、累计会话数、累计工单数、最近会话时间、风险等级；
--   2. customer_tag 从"一个名字"升级成"指向标签体系的关联"：
--      补 tag_id / tag_group / source / operator_id / operator_name / update_time / is_deleted；
--   3. 新增 customer_tag_def：标签体系（分组、颜色、类型、排序、启用）；
--   4. 新增 customer_event：客户动态（建档 / 打标 / 去标 / 等级调整 / 备注 / 风险标记）。
--   5. 满意度评价正式开写 csat_record（表在第 4 篇的全量脚本里，这里补齐"老库自愈"）；
--   6. 标签自动化：customer_tag_def 加"指标 + 比较符 + 阈值 + 统计窗口"四件套；
--   7. 客户类型：个人客户 / 企业客户（原来错当成"会员等级"的第 5 档，语义不对）。
-- 幂等：可重复执行（CREATE IF NOT EXISTS + ADD COLUMN IF NOT EXISTS + 约束先删后建 + 索引 IF NOT EXISTS）。
--
-- 为什么 customer_tag 要"加列"而不是"新建一张关联表"：
-- 这张表在第 4 篇的建表脚本里就有，只是没接过代码（一直是空的）。老库上它可能已经被别的分支
-- 塞过数据，直接改名/重建会把数据弄丢；加列 + 补唯一索引，老行照样能用（tag_id 为空时按老口径读）。

-- ---------- 1. customer：补齐客户 360 要用的列 ----------
CREATE TABLE IF NOT EXISTS "customer" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_no" VARCHAR(32) NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "phone" VARCHAR(20) DEFAULT NULL,
  "level" SMALLINT NOT NULL DEFAULT 1,
  "channel" VARCHAR(20) DEFAULT NULL,
  "orders_count" INT NOT NULL DEFAULT 0,
  "total_value" NUMERIC(12,2) NOT NULL DEFAULT 0.00,
  "points" INT NOT NULL DEFAULT 0,
  "csat" NUMERIC(5,2) DEFAULT NULL,
  "sentiment" SMALLINT DEFAULT NULL,
  "last_active" TIMESTAMP DEFAULT NULL,
  "remark" VARCHAR(255) DEFAULT NULL,
  "session_count" INT NOT NULL DEFAULT 0,
  "ticket_count" INT NOT NULL DEFAULT 0,
  "last_session_at" TIMESTAMP DEFAULT NULL,
  "risk_level" SMALLINT NOT NULL DEFAULT 1,
  "merged_into" VARCHAR(32) DEFAULT NULL,
  "anonymized_at" TIMESTAMP DEFAULT NULL,
  "customer_type" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_level CHECK ("level" IN (1,2,3,4,5)),
  CONSTRAINT ck_customer_sentiment CHECK ("sentiment" IN (1,2,3)),
  CONSTRAINT ck_customer_risk_level CHECK ("risk_level" IN (1,2,3)),
  CONSTRAINT ck_customer_customer_type CHECK ("customer_type" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_customer_no" UNIQUE ("tenant_code", "customer_no")
);

ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "remark" VARCHAR(255) DEFAULT NULL;
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "session_count" INT NOT NULL DEFAULT 0;
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "ticket_count" INT NOT NULL DEFAULT 0;
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "last_session_at" TIMESTAMP DEFAULT NULL;
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "risk_level" SMALLINT NOT NULL DEFAULT 1;
-- 合并：源客户软删 + 记下"并到谁了"（不物理删：会话/工单都还指着他）
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "merged_into" VARCHAR(32) DEFAULT NULL;
-- 匿名化：合规要求"删 PII 但不破坏统计"，所以只记一个时间点，姓名/手机/备注被抹掉
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "anonymized_at" TIMESTAMP DEFAULT NULL;
-- 客户类型：1-个人客户、2-企业客户。
-- 为什么要单独一列：客户是"企业自己的客户"，个人还是企业（B 端采购）属于**客户类型**，
-- 跟"会员忠诚度分层"（银卡 / 金卡 / 铂金）不是一回事，混在第 5 档里没人看得懂。
ALTER TABLE "customer" ADD COLUMN IF NOT EXISTS "customer_type" SMALLINT NOT NULL DEFAULT 1;

ALTER TABLE "customer" DROP CONSTRAINT IF EXISTS "ck_customer_customer_type";
ALTER TABLE "customer"
    ADD CONSTRAINT "ck_customer_customer_type" CHECK ("customer_type" IN (1,2));

-- 风险等级是新约束：老库上直接插 level=2 会被原来没有这条约束的库放过，但新库会挡——
-- 统一"先删后建"，两边口径一致。
ALTER TABLE "customer" DROP CONSTRAINT IF EXISTS "ck_customer_risk_level";
ALTER TABLE "customer"
    ADD CONSTRAINT "ck_customer_risk_level" CHECK ("risk_level" IN (1,2,3));

-- 客户列表默认按"最近活跃"倒序、按等级/风险筛选，这条索引撑住它
CREATE INDEX IF NOT EXISTS "idx_customer_tenant_last_active"
    ON "customer" ("tenant_code", "last_active" DESC);

COMMENT ON COLUMN "customer"."remark" IS '客户备注（坐席可见的内部说明，不展示给客户）';
COMMENT ON COLUMN "customer"."session_count" IS '累计会话数（建档后每次新会话 +1，冗余字段，列表排序用）';
COMMENT ON COLUMN "customer"."ticket_count" IS '累计工单数（会话转单 / 坐席建单时 +1）';
COMMENT ON COLUMN "customer"."last_session_at" IS '最近一次会话时间（没有会话时为空）';
COMMENT ON COLUMN "customer"."risk_level" IS '风险等级码：1-正常、2-关注、3-风险';
COMMENT ON COLUMN "customer"."merged_into" IS '重复客户被合并到哪个客户编号（自身软删，便于回溯）';
COMMENT ON COLUMN "customer"."anonymized_at" IS '匿名化时间（姓名 / 手机号 / 备注已抹除，会话与工单统计保留）';
COMMENT ON COLUMN "customer"."customer_type" IS '客户类型码：1-个人客户、2-企业客户（B 端采购，与会员等级无关）';
COMMENT ON COLUMN "customer"."level" IS '会员等级码（价值分层）：1-普通、2-银卡、3-金卡、4-铂金、5-钻石';

-- ---------- 2. customer_tag：从"标签名"升级成"指向标签体系的关联" ----------
CREATE TABLE IF NOT EXISTS "customer_tag" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_id" BIGINT NOT NULL,
  "tag_name" VARCHAR(32) NOT NULL,
  "tag_id" BIGINT DEFAULT NULL,
  "tag_group" VARCHAR(32) DEFAULT NULL,
  "source" SMALLINT NOT NULL DEFAULT 1,
  "operator_id" BIGINT DEFAULT NULL,
  "operator_name" VARCHAR(64) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_tag_source CHECK ("source" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_customer_tag" UNIQUE ("tenant_code", "customer_id", "tag_name")
);

ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "tag_id" BIGINT DEFAULT NULL;
ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "tag_group" VARCHAR(32) DEFAULT NULL;
ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "source" SMALLINT NOT NULL DEFAULT 1;
ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "operator_id" BIGINT DEFAULT NULL;
ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "operator_name" VARCHAR(64) DEFAULT NULL;
ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "customer_tag" ADD COLUMN IF NOT EXISTS "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE "customer_tag" DROP CONSTRAINT IF EXISTS "ck_customer_tag_source";
ALTER TABLE "customer_tag"
    ADD CONSTRAINT "ck_customer_tag_source" CHECK ("source" IN (1,2,3));

-- 同一个客户、同一个标签只允许挂一次（软删的不算）：重复打标就是"重复点击"，
-- 靠唯一索引挡住，代码里不用再查一遍有没有
CREATE UNIQUE INDEX IF NOT EXISTS "uk_tenant_customer_tag_id"
    ON "customer_tag" ("tenant_code", "customer_id", "tag_id")
    WHERE "tag_id" IS NOT NULL AND "is_deleted" = FALSE;

CREATE INDEX IF NOT EXISTS "idx_tenant_customer_tag_customer"
    ON "customer_tag" ("tenant_code", "customer_id")
    WHERE "is_deleted" = FALSE;

COMMENT ON COLUMN "customer_tag"."tag_id" IS '标签定义ID（customer_tag_def.id；为空表示历史数据，只有标签名）';
COMMENT ON COLUMN "customer_tag"."tag_group" IS '标签分组（冗余，列表直接读，不用再 join）';
COMMENT ON COLUMN "customer_tag"."source" IS '来源码：1-手工打标、2-规则自动、3-批量导入';
COMMENT ON COLUMN "customer_tag"."operator_id" IS '打标人ID';
COMMENT ON COLUMN "customer_tag"."operator_name" IS '打标人姓名';

-- ---------- 3. customer_tag_def：标签体系 ----------
CREATE TABLE IF NOT EXISTS "customer_tag_def" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "tag_code" VARCHAR(32) NOT NULL,
  "tag_name" VARCHAR(32) NOT NULL,
  "tag_group" VARCHAR(32) NOT NULL,
  "color" VARCHAR(16) NOT NULL DEFAULT 'blue',
  "tag_type" SMALLINT NOT NULL DEFAULT 1,
  "rule_hint" VARCHAR(255) DEFAULT NULL,
  "rule_metric" VARCHAR(32) DEFAULT NULL,
  "rule_op" VARCHAR(4) DEFAULT NULL,
  "rule_value" NUMERIC(12,2) DEFAULT NULL,
  "rule_window_days" INT NOT NULL DEFAULT 0,
  "description" VARCHAR(255) DEFAULT NULL,
  "sort_no" INT NOT NULL DEFAULT 100,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_tag_def_type CHECK ("tag_type" IN (1,2)),
  CONSTRAINT ck_customer_tag_def_op CHECK ("rule_op" IS NULL OR "rule_op" IN ('GT','GTE','LT','LTE','EQ')),
  CONSTRAINT ck_customer_tag_def_metric CHECK ("rule_metric" IS NULL OR "rule_metric" IN
        ('CSAT','LEVEL','RISK_LEVEL','SESSIONS','TICKETS',
         'REFUND_SESSIONS','COMPLAINT_SESSIONS','NIGHT_SESSIONS','ACTIVE_DAYS')),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_tag_def_code" UNIQUE ("tenant_code", "tag_code"),
  CONSTRAINT "uk_tenant_tag_def_name" UNIQUE ("tenant_code", "tag_name")
);

-- 规则标签的四件套：指标 / 比较符 / 阈值 / 统计窗口（天，0 = 全周期）
ALTER TABLE "customer_tag_def" ADD COLUMN IF NOT EXISTS "rule_metric" VARCHAR(32) DEFAULT NULL;
ALTER TABLE "customer_tag_def" ADD COLUMN IF NOT EXISTS "rule_op" VARCHAR(4) DEFAULT NULL;
ALTER TABLE "customer_tag_def" ADD COLUMN IF NOT EXISTS "rule_value" NUMERIC(12,2) DEFAULT NULL;
ALTER TABLE "customer_tag_def" ADD COLUMN IF NOT EXISTS "rule_window_days" INT NOT NULL DEFAULT 0;

ALTER TABLE "customer_tag_def" DROP CONSTRAINT IF EXISTS "ck_customer_tag_def_op";
ALTER TABLE "customer_tag_def"
    ADD CONSTRAINT "ck_customer_tag_def_op" CHECK ("rule_op" IS NULL OR "rule_op" IN ('GT','GTE','LT','LTE','EQ'));
ALTER TABLE "customer_tag_def" DROP CONSTRAINT IF EXISTS "ck_customer_tag_def_metric";
-- 收窄"可执行指标"白名单：去掉三个**交易类**指标（累计消费 / 订单数 / 积分）。
-- 为什么去掉：这些数据属于电商/交易系统，客服系统既拿不到也维护不了——
-- 配成规则标签的结果是"永远不命中"，比没有更糟（运营会以为规则坏了）。
-- 顺序很重要：先把已经配了这三个指标的标签**降级成手工标签**，再收窄约束，
-- 否则 ADD CONSTRAINT 会因为存量数据不满足而失败。
UPDATE "customer_tag_def"
   SET "tag_type" = 1,
       "rule_metric" = NULL,
       "rule_op" = NULL,
       "rule_value" = NULL
 WHERE "rule_metric" IN ('TOTAL_VALUE', 'ORDERS', 'POINTS');

ALTER TABLE "customer_tag_def"
    ADD CONSTRAINT "ck_customer_tag_def_metric" CHECK ("rule_metric" IS NULL OR "rule_metric" IN
        ('CSAT','LEVEL','RISK_LEVEL','SESSIONS','TICKETS',
         'REFUND_SESSIONS','COMPLAINT_SESSIONS','NIGHT_SESSIONS','ACTIVE_DAYS'));

CREATE INDEX IF NOT EXISTS "idx_tenant_tag_def_group"
    ON "customer_tag_def" ("tenant_code", "tag_group", "sort_no");

-- 规则引擎每 10 分钟扫一遍"有规则的标签"，这条索引撑住它
CREATE INDEX IF NOT EXISTS "idx_tenant_tag_def_rule"
    ON "customer_tag_def" ("tenant_code", "tag_type")
    WHERE "is_deleted" = FALSE AND "is_enabled" = TRUE;

COMMENT ON TABLE "customer_tag_def" IS '客户标签定义表（标签体系：分组 + 颜色 + 手工/规则 + 排序）';
COMMENT ON COLUMN "customer_tag_def"."tag_code" IS '标签编码（租户内唯一，导入/接口用）';
COMMENT ON COLUMN "customer_tag_def"."tag_group" IS '分组：价值 / 服务 / 风险 / 偏好 / 来源';
COMMENT ON COLUMN "customer_tag_def"."color" IS '展示颜色：blue / green / orange / red / purple / gray';
COMMENT ON COLUMN "customer_tag_def"."tag_type" IS '类型码：1-手工打标、2-规则自动';
COMMENT ON COLUMN "customer_tag_def"."rule_hint" IS '自动标签的命中口径说明（写给人看；rule_metric 那一组是给引擎跑的）';
COMMENT ON COLUMN "customer_tag_def"."rule_metric" IS '规则指标（只允许客服系统自己产生的数据）：CSAT 满意度 / LEVEL 会员等级 / RISK_LEVEL 风险等级 / SESSIONS 会话数 / TICKETS 工单数 / REFUND_SESSIONS 退款类会话 / COMPLAINT_SESSIONS 投诉类会话 / NIGHT_SESSIONS 夜间咨询 / ACTIVE_DAYS 距最近活跃天数';
COMMENT ON COLUMN "customer_tag_def"."rule_op" IS '比较符：GT / GTE / LT / LTE / EQ';
COMMENT ON COLUMN "customer_tag_def"."rule_value" IS '阈值';
COMMENT ON COLUMN "customer_tag_def"."rule_window_days" IS '统计窗口（天）：0 表示全周期；只对会话类指标有意义';
COMMENT ON COLUMN "customer_tag_def"."sort_no" IS '组内排序号（越小越前）';
COMMENT ON COLUMN "customer_tag_def"."is_enabled" IS '是否启用：停用后不能再打标，但已经打上的标签仍然展示';

-- ---------- 4. customer_event：客户动态（360 里的时间线） ----------
-- ---------- 4. csat_record：满意度评价（第 4 篇设计的表，这一篇正式开写） ----------
-- 表本来就在全量建库脚本里，这里再写一遍是为了"老库缺表能自愈"：
-- 增量脚本 + 启动自检登记，缺了会自动补上（幂等）。
CREATE TABLE IF NOT EXISTS "csat_record" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "score" SMALLINT NOT NULL,
  "feedback" VARCHAR(512) DEFAULT NULL,
  "evaluate_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_csat_score CHECK ("score" BETWEEN 1 AND 5),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_csat_session" UNIQUE ("tenant_code", "session_id")
);

CREATE INDEX IF NOT EXISTS "idx_csat_tenant_time"
    ON "csat_record" ("tenant_code", "evaluate_time");
-- 客户 360 要按客户算"满意度均值"，这条索引撑住它
CREATE INDEX IF NOT EXISTS "idx_csat_tenant_customer"
    ON "csat_record" ("tenant_code", "customer_id");

COMMENT ON TABLE "csat_record" IS '满意度评价明细表（会话结束后由客户评价，坐席也可代录）';
COMMENT ON COLUMN "csat_record"."session_id" IS '会话ID';
COMMENT ON COLUMN "csat_record"."customer_id" IS '客户ID（客户 360 按它算满意度均值）';
COMMENT ON COLUMN "csat_record"."score" IS '评分 1-5';
COMMENT ON COLUMN "csat_record"."feedback" IS '评价内容';

-- ---------- 5. customer_event：客户动态（360 里的时间线） ----------
CREATE TABLE IF NOT EXISTS "customer_event" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_id" BIGINT NOT NULL,
  "event_type" SMALLINT NOT NULL,
  "event_title" VARCHAR(64) NOT NULL,
  "event_content" VARCHAR(512) DEFAULT NULL,
  "ref_type" SMALLINT DEFAULT NULL,
  "ref_no" VARCHAR(64) DEFAULT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "operator_name" VARCHAR(64) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_customer_event_type CHECK ("event_type" IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16)),
  PRIMARY KEY ("id")
);

-- 事件类型从 6 类扩到 16 类：会话开始 / 会话结束 / 转人工 / 转工单 / 满意度评价 /
-- 敏感信息查阅 / 订单变更 / 客户合并 / 匿名化 / 客户删除。
-- 老库上这条约束只允许 1~6，直接写 7 会被挡住——所以照例"先删后建"。
ALTER TABLE "customer_event" DROP CONSTRAINT IF EXISTS "ck_customer_event_type";
ALTER TABLE "customer_event"
    ADD CONSTRAINT "ck_customer_event_type" CHECK ("event_type" IN (1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16));

CREATE INDEX IF NOT EXISTS "idx_customer_event_tenant_customer"
    ON "customer_event" ("tenant_code", "customer_id", "event_time" DESC);

-- 按类型查动态（"这个客户最近被谁看过手机号"这类合规查询）
CREATE INDEX IF NOT EXISTS "idx_customer_event_tenant_type"
    ON "customer_event" ("tenant_code", "event_type", "event_time" DESC);

COMMENT ON TABLE "customer_event" IS '客户动态表（建档 / 打标 / 去标 / 等级调整 / 备注 / 风险标记）';
COMMENT ON COLUMN "customer_event"."event_type" IS '事件类型码：1-建档、2-打标、3-去标、4-等级调整、5-备注更新、6-风险标记、7-发起会话、8-会话结束、9-转人工、10-转工单、11-满意度评价、12-敏感信息查阅、13-订单变更、14-客户合并、15-客户匿名化、16-客户删除';
COMMENT ON COLUMN "customer_event"."event_title" IS '动态标题（列表直接展示）';
COMMENT ON COLUMN "customer_event"."event_content" IS '动态详情（例如"银卡 → 金卡"）';
COMMENT ON COLUMN "customer_event"."ref_type" IS '关联对象类型码：1-会话、2-工单、3-订单、4-标签';
COMMENT ON COLUMN "customer_event"."ref_no" IS '关联对象编号（会话号 / 工单号 / 订单号 / 标签编码）';
COMMENT ON COLUMN "customer_event"."operator_id" IS '操作人ID（空表示系统自动）';
COMMENT ON COLUMN "customer_event"."operator_name" IS '操作人姓名';
