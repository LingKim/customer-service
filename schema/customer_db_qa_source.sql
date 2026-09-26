-- 质检任务接真实会话（可重复执行）：--   1. qa_task 加"一个会话只建一条质检任务"的唯一索引，避免重复质检同一单；--   2. 补一个扫描用的索引：按"已结束 + 还没有质检任务"找单子。---- 背景：这一版之前，"发起全量质检"是拿内置样例池随机生成任务的，-- 真实会话聊了什么根本没进质检中心。这个脚本是把它改成真实数据源的第一步。

-- 同一个租户下，同一个会话只能有一条未删除的质检任务
CREATE UNIQUE INDEX IF NOT EXISTS "uk_qa_task_tenant_session"
    ON "qa_task" ("tenant_code", "session_id")
    WHERE "is_deleted" = FALSE AND "session_id" IS NOT NULL;

-- 扫描候选：已结束且还没质检过的会话
CREATE INDEX IF NOT EXISTS "idx_session_tenant_end_time"
    ON "session" ("tenant_code", "end_time" DESC)
    WHERE "status" = 4 AND "is_deleted" = FALSE;
