-- 第 14 章：为已有 customer_db 质检任务补充送检对话和结果来源。
-- 仅修改表结构；执行前须确认目标数据库和历史迁移状态。
ALTER TABLE qa_task ADD COLUMN IF NOT EXISTS transcript TEXT;
ALTER TABLE qa_task ADD COLUMN IF NOT EXISTS ai_source VARCHAR(32);
