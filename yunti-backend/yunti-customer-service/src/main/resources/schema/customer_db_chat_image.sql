-- 聊天图片（可重复执行）：把 file_meta.biz_type 的取值放开到 5。
--
-- 背景：客户在聊天窗口发图片时，图片附件也要登记 file_meta。
-- 原来约束是 CHECK (biz_type IN (1,2,3,4))（1-头像、2-附件、3-导出、4-发票），
-- 聊天图片这个新业务塞不进去，插入直接报：
--   new row for relation "file_meta" violates check constraint "ck_file_meta_biz_type"
--
-- 这类"约束挡住新业务"的问题，光看代码是发现不了的（列都在），
-- 所以除了这个脚本，SchemaGuard 还加了一道"必需约束取值"的自检，启动时能自动发现并补。

ALTER TABLE "file_meta" DROP CONSTRAINT IF EXISTS "ck_file_meta_biz_type";
ALTER TABLE "file_meta"
    ADD CONSTRAINT "ck_file_meta_biz_type" CHECK ("biz_type" IN (1,2,3,4,5));

COMMENT ON COLUMN "file_meta"."biz_type" IS '业务类型码：1-头像、2-附件、3-导出、4-发票、5-聊天图片';
