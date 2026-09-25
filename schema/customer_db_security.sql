-- 访客接入安全加固（可重复执行）：
-- 1. 渠道增加访客域名白名单，防止别人拿着公开的 appKey 在自己站上嵌挂件刷会话；
-- 2. 匿名客户编号改成不可枚举的随机串（新数据生效，历史数据可自行决定是否重刷）。

ALTER TABLE "channel"
ADD COLUMN IF NOT EXISTS "allowed_origins" VARCHAR(512) DEFAULT NULL;

COMMENT ON COLUMN "channel"."allowed_origins"
IS '访客接入域名白名单（逗号分隔，支持 *.example.com；为空表示不限制）';

COMMENT ON COLUMN "customer"."customer_no"
IS '客户编号（匿名访客为 130 位随机串，不可枚举）';

-- 可选：把历史匿名客户的编号重刷成不可枚举随机串（会让老访客变成新访客，按需执行）
-- UPDATE "customer"
-- SET "customer_no" = 'V' || upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 26))
-- WHERE "customer_no" ~ '^V[0-9]{17}$';
