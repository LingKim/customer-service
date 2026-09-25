---
title: "23 多模态客服：图片识别、OCR 与截图判读（千问 VL 接入）（一）"
source: "https://articles.zsxq.com/id_bfdl5aw1v3wa.html"
author:
  - "[[苏三]]"
published:
created: 2026-09-25
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

## 一、项目概述

### 1.1 功能范围

前面几篇里，机器人已经会听（意图、情绪）、会查（知识库、引用溯源）、会想（多轮、转人工）。

但客户发一张图，它还是瞎的——而真实客服场景里，**截图往往比文字更关键**："支付失败"配一张报错截图、"快递没收到"配一张物流轨迹、"收到的东西坏了"配一张破损照。这一篇把图片这条链路补齐。

1.  **客户发图**：访客窗口选图（支持一次多选、最多 3 张），先上传到 RustFS，在输入框上方暂存成"附件卡"（可逐张删除）；点「发送」时**文字 + 最多 3 张图合成一条消息**发出去，而不是"选完图立刻发一条"，后者会让机器人先按"只有图"接一轮、文字到了再答一次，两轮都对不上上下文；

2.  **图片识别**：Java 侧把原图从对象存储取出来、缩放成 JPEG 再 base64（**压图在 Java、发请求在 Python**），交给千问 VL 做 OCR 与截图判读，产出四类结构化信息：`ocrText` 图中文字、`summary` 一句话判读、`orderNo/amount/errorText` 订单号金额报错原文、`needHuman` 是否建议人工；

3.  **识别结果回填**：结论写回那条图片消息（`ai*` 字段）并**实时推给坐席**——坐席打开会话就看见"AI 判读"卡：判读、订单号、金额、建议人工标、报错原文，还能展开看 OCR 原文；

4.  **机器人据此作答**：把"图里的内容"和"客户随图说的那句话"拼成一段交给客服大脑，意图识别、知识库检索、引用溯源全都能照常工作；识别那几秒客户侧能看到"正在输入"，不是干等；

5.  **降级要诚实**：没配视觉密钥就明确返回"不可用"，机器人如实告诉客户"暂时看不了图片"，**绝不编一段"我看到图上写着…"**；图片超限、张数超限都会明确报错，而不是把大 body 硬塞给网关；

6.  **质检也看得到图**：实时质检与离线质检的送检文本里，图片消息取"判读 + 报错 + 图中文字"，而不是那串 `{"fileId":...}` 的 JSON。

### 1.2 协议与数据模型变化

HTTP（新增）：

|      |                                             |                                                           |
|------|---------------------------------------------|-----------------------------------------------------------|
| 方法 | 路径                                        | 说明                                                      |
| POST | /api/customer/sessions/attachments          | 访客上传聊天图片（`multipart/form-data`，用渠道密钥鉴权） |
| GET  | /api/customer/sessions/attachments/{fileId} | 读取图片字节（访客与坐席共用，靠雪花 ID 当凭据）          |
| POST | /api/ai/v1/agent/vision                     | 识别一组图片：OCR + 判读 + 结构化字段                     |
| GET  | /api/ai/v1/agent/vision/health              | 视觉模型体检（密钥、模型、张数上限）                      |

图片消息的正文（`session_message.msg_type = 2` 的 `content`）是一段 JSON：

|                                                                                                                  |        |                                                                        |
|------------------------------------------------------------------------------------------------------------------|--------|------------------------------------------------------------------------|
| 字段                                                                                                             | 谁写的 | 说明                                                                   |
| `fileId` / `url` / `name` / `size`                                                                               | 前端   | 第一张图（**老消息就是这个样子**，所以顶层字段必须保留）               |
| `fileIds`                                                                                                        | 前端   | 这条消息的全部文件 ID，顺序 = 客户选图顺序                             |
| `images`                                                                                                         | 前端   | 全部图片的完整清单（fileId/url/name/size），页面渲染多图就靠它         |
| `text`                                                                                                           | 前端   | 客户随图说的那句话：视觉模型拿它当"客户的问题"，大脑拿它当"客户说的话" |
| `aiSummary` / `aiOcrText` / `aiOrderNo` / `aiAmount` / `aiErrorText` / `aiNeedHuman` / `aiAvailable` / `aiModel` | 服务端 | 识别完成后回填（一条图片消息会先到一次、识别完再更新一次）             |

数据库：**不改表结构**，只放开一个 CHECK 约束——`file_meta.biz_type` 允许 `5-聊天图片`。这一条恰恰是联调里最容易踩的坑（见第八章第 3 条）：列都在、代码也对，插入就是被约束挡住。

### 二、设计：谁压图、谁调模型

和第 23 篇参考的企业智能招聘系统一样，这件事**必须分两层做**：

|                          |                                                                   |                                                                                     |
|--------------------------|-------------------------------------------------------------------|-------------------------------------------------------------------------------------|
| 谁                       | 干什么                                                            | 为什么                                                                              |
| Java（customer-service） | 从 RustFS 取原图 → 缩放（最大边 1280）→ 转 JPEG → base64 data URL | 图片在对象存储里，Java 手里就有存储客户端；压缩后 payload 从十几 MB 降到 100\~300KB |
| Python（yunti-ai）       | 按 OpenAI 兼容的多模态格式发出去，解析结构化结果                  | 各家的多模态格式大同小异，收口在一层里，换模型不改业务代码                          |

**为什么不用传统 OCR API**：客服场景要的不只是"把字抠出来"，还要"看懂这张图在说什么"（是报错截图？订单截图？商品破损照？）。多模态模型一次就能给出"文字 + 判读"，省掉"OCR 取字 → 再喂给模型理解"这两跳。

**几条硬规则**：

-   **一次最多 3 张图**：AI 侧 `vision_max_images=3`，前端也卡 3 张，两边对齐；超过就提示客户"先发出去再选"，而不是悄悄丢掉；

-   **并发但不乱序**：3 张图的"下载 + 压缩"在 Java 侧并发跑（`invokeAll` 按提交顺序返回，顺序严格不变），但**模型只调用一次**、一次带 3 张图——模型能看到图与图之间的关系（比如两张截图是不是同一个订单），这是串行调 3 次模型换不来的；

-   **失败只降级**：识别失败/超时/没密钥，都不能影响"消息已经发出去"这个事实，客户该看到的图照样在，只是拿不到判读。

## 三、数据库与对象存储准备

### 3.1 放开 file\_meta 的业务类型约束

聊天图片要和头像、附件、导出文件放在同一张 `file_meta` 表里，就得给它一个业务类型码。约束里原来只有 1\~4，直接写 5 会报：

``` code-block-container
ERROR: new row for relation "file_meta" violates check constraint "ck_file_meta_biz_type"
详细：Failing row contains (227337764083863552, T202609050000002, IMG227337764083863552, XHS-000-COVER-....png, ...), VISITOR, ..., f).
```

这个报错很有欺骗性：表在、列在、代码也对，只是**少跑了一个脚本**。所以本篇既有"全量建表脚本同步"，也有"增量脚本"给已经在跑的库：

``` code-block-container
cd /Users/mac/Documents/ai-test/cc/customer-service
bash scripts/migrate-customer-db.sh
```

### 文件：schema/customer\_db\_chat\_image.sql

客户库增量脚本：把 `file_meta.biz_type` 的允许取值放开到 5-聊天图片。用 `DROP CONSTRAINT` + `ADD CONSTRAINT` 而不是 `IF NOT EXISTS`，是因为约束文本要能被"重写"——反复执行也不会出错。

``` code-block-container
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
```

### 文件：schema/customer\_db.sql

全量建表脚本同步约束与注释（新库直接建对，别让新同学在建库这一步就掉坑）

``` code-block-container
-- 云梯智能客服平台 · customer_db 建表脚本（PostgreSQL 17）
-- 由 database-design.md v4.5 生成

CREATE TABLE "channel" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" VARCHAR(32) NOT NULL,
  "channel_type" SMALLINT NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "desc" VARCHAR(255) DEFAULT NULL,
  "skill_group_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 2,
  "stage" SMALLINT NOT NULL DEFAULT 1,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "allowed_origins" VARCHAR(512) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_channel_channel_type CHECK ("channel_type" IN (1,2,3,4,5,6,7)),
  CONSTRAINT ck_channel_status CHECK ("status" IN (1,2)),
  CONSTRAINT ck_channel_stage CHECK ("stage" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_channel" UNIQUE ("tenant_code", "channel_id")
);
COMMENT ON TABLE "channel" IS '渠道表';
COMMENT ON COLUMN "channel"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "channel"."channel_id" IS '渠道ID（SDK 用）';
COMMENT ON COLUMN "channel"."channel_type" IS '类型码：1-网站、2-公众号、3-小程序、4-App、5-400热线、6-邮件、7-自定义';
COMMENT ON COLUMN "channel"."name" IS '渠道名称';
COMMENT ON COLUMN "channel"."desc" IS '描述';
COMMENT ON COLUMN "channel"."skill_group_id" IS '绑定技能组ID';
COMMENT ON COLUMN "channel"."status" IS '状态码：1-启用、2-停用';
COMMENT ON COLUMN "channel"."stage" IS '阶段码：1-未配置、2-待上线、3-运行中';
COMMENT ON COLUMN "channel"."allowed_origins" IS '访客接入域名白名单（逗号分隔，支持 *.example.com；为空表示不限制）';

CREATE TABLE "channel_key" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "app_key" VARCHAR(64) NOT NULL,
  "key_type" SMALLINT NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "rotated_at" TIMESTAMP DEFAULT NULL,
  "expire_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_channel_key_key_type CHECK ("key_type" IN (1,2)),
  CONSTRAINT ck_channel_key_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_channel_key" UNIQUE ("tenant_code", "channel_id", "app_key")
);
COMMENT ON TABLE "channel_key" IS '渠道密钥表';
COMMENT ON COLUMN "channel_key"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "channel_key"."channel_id" IS '渠道ID';
COMMENT ON COLUMN "channel_key"."app_key" IS '密钥（加密存储）';
COMMENT ON COLUMN "channel_key"."key_type" IS '类型码：1-渠道密钥、2-租户主密钥';
COMMENT ON COLUMN "channel_key"."status" IS '状态码：1-生效、2-已吊销';
COMMENT ON COLUMN "channel_key"."rotated_at" IS '最近轮换时间';
COMMENT ON COLUMN "channel_key"."expire_time" IS '过期时间';

CREATE TABLE "skill_group" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "name" VARCHAR(64) NOT NULL,
  "description" VARCHAR(255) DEFAULT NULL,
  "is_default" BOOLEAN NOT NULL DEFAULT FALSE,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "overflow_after_seconds" INTEGER NOT NULL DEFAULT 60,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_name" UNIQUE ("tenant_code", "name")
);
COMMENT ON TABLE "skill_group" IS '技能组表';
COMMENT ON COLUMN "skill_group"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "skill_group"."name" IS '技能组名称';
COMMENT ON COLUMN "skill_group"."description" IS '说明';
COMMENT ON COLUMN "skill_group"."is_default" IS '是否默认';

CREATE TABLE "agent_status" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "agent_id" BIGINT NOT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "max_concurrency" SMALLINT NOT NULL DEFAULT 5,
  "is_connected" BOOLEAN NOT NULL DEFAULT FALSE,
  "status_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_agent_status_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_agent_status" UNIQUE ("tenant_code", "agent_id")
);
COMMENT ON TABLE "agent_status" IS '坐席状态表';
COMMENT ON COLUMN "agent_status"."agent_id" IS '坐席用户ID';
COMMENT ON COLUMN "agent_status"."status" IS '状态码：1-在线、2-忙碌、3-小休';
COMMENT ON COLUMN "agent_status"."max_concurrency" IS '最多同时接待的会话数';
COMMENT ON COLUMN "agent_status"."is_connected" IS '长连接是否在线（由实时网关维护，路由只分配在线坐席）';
COMMENT ON COLUMN "agent_status"."status_time" IS '状态变更时间（同负载时优先分配给更久没换状态的坐席）';
CREATE INDEX "idx_agent_status_tenant_status" ON "agent_status" ("tenant_code", "status");

CREATE INDEX "idx_session_queue"
    ON "session" ("tenant_code", "start_time")
    WHERE "agent_id" IS NULL AND "is_deleted" = FALSE;

CREATE TABLE "channel_test_log" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "test_result" SMALLINT NOT NULL,
  "latency_ms" INT DEFAULT NULL,
  "summary" VARCHAR(255) DEFAULT NULL,
  "test_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_channel_test_log_test_result CHECK ("test_result" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "channel_test_log" IS '渠道联调记录表';
COMMENT ON COLUMN "channel_test_log"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "channel_test_log"."channel_id" IS '渠道ID';
COMMENT ON COLUMN "channel_test_log"."test_result" IS '结果码：1-通过、2-失败';
COMMENT ON COLUMN "channel_test_log"."latency_ms" IS '延迟（毫秒）';
COMMENT ON COLUMN "channel_test_log"."summary" IS '检查摘要';
COMMENT ON COLUMN "channel_test_log"."test_time" IS '测试时间';
CREATE INDEX "idx_channel_test_log_tenant_channel_time" ON "channel_test_log" ("tenant_code", "channel_id", "test_time");

CREATE TABLE "session" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_no" VARCHAR(40) NOT NULL,
  "channel_id" BIGINT NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "skill_group_id" BIGINT DEFAULT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "intent" VARCHAR(64) DEFAULT NULL,
  "emotion" VARCHAR(20) DEFAULT NULL,
  "bot_transfer_reason" VARCHAR(255) DEFAULT NULL,
  "source" VARCHAR(20) DEFAULT NULL,
  "start_time" TIMESTAMP NOT NULL,
  "end_time" TIMESTAMP DEFAULT NULL,
  "csat_score" SMALLINT DEFAULT NULL,
  "last_msg_seq" BIGINT NOT NULL DEFAULT 0,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_session_status CHECK ("status" IN (1,2,3,4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_session_no" UNIQUE ("tenant_code", "session_no")
);
COMMENT ON TABLE "session" IS '会话表';
COMMENT ON COLUMN "session"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "session"."session_no" IS '会话编号';
COMMENT ON COLUMN "session"."channel_id" IS '来源渠道ID';
COMMENT ON COLUMN "session"."last_msg_seq" IS '会话已分配的最大消息序号';
COMMENT ON COLUMN "session"."customer_id" IS '客户ID';
COMMENT ON COLUMN "session"."status" IS '状态码：1-排队中、2-机器人接待、3-人工接待、4-已结束';
COMMENT ON COLUMN "session"."skill_group_id" IS '技能组ID';
COMMENT ON COLUMN "session"."agent_id" IS '当前坐席用户ID';
COMMENT ON COLUMN "session"."intent" IS '智能客服识别出的意图（由 AI 客服大脑写入）';
COMMENT ON COLUMN "session"."emotion" IS '智能客服识别出的客户情绪：中性/焦虑/不满/愤怒';
COMMENT ON COLUMN "session"."bot_transfer_reason" IS '机器人转人工的原因（客户情绪激动 / 答不上来 / 命中转人工意图等）';
COMMENT ON COLUMN "session"."source" IS '来源：微信、App、网站等渠道';
COMMENT ON COLUMN "session"."start_time" IS '开始时间';
COMMENT ON COLUMN "session"."end_time" IS '结束时间';
COMMENT ON COLUMN "session"."csat_score" IS '满意度评分1-5';
CREATE INDEX "idx_session_tenant_status_time" ON "session" ("tenant_code", "status", "create_time");
-- 质检补扫用：按"已结束"找最近结束的会话
CREATE INDEX "idx_session_tenant_end_time"
    ON "session" ("tenant_code", "end_time" DESC)
    WHERE "status" = 4 AND "is_deleted" = FALSE;
CREATE INDEX "idx_session_tenant_customer" ON "session" ("tenant_code", "customer_id");

CREATE TABLE "session_message" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "msg_no" VARCHAR(40) NOT NULL,
  "client_msg_no" VARCHAR(64) DEFAULT NULL,
  "seq" BIGINT NOT NULL DEFAULT 0,
  "msg_type" SMALLINT NOT NULL,
  "sender_type" SMALLINT NOT NULL,
  "sender_id" BIGINT DEFAULT NULL,
  "content" TEXT,
  "ref_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "visible_to" SMALLINT NOT NULL DEFAULT 1,
  "send_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_session_message_msg_type CHECK ("msg_type" IN (1,2,3,4,5)),
  CONSTRAINT ck_session_message_sender_type CHECK ("sender_type" IN (1,2,3,4)),
  CONSTRAINT ck_session_message_status CHECK ("status" IN (1,2,3,4)),
  CONSTRAINT ck_session_message_visible_to CHECK ("visible_to" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_msg_no" UNIQUE ("tenant_code", "msg_no")
);

-- 幂等：同一个会话里同一个 client_msg_no 只允许一条；增量补拉按 (会话, seq) 走索引
CREATE UNIQUE INDEX "uk_session_client_msg"
    ON "session_message" ("tenant_code", "session_id", "client_msg_no")
    WHERE "client_msg_no" IS NOT NULL;

CREATE INDEX "idx_session_message_seq"
    ON "session_message" ("tenant_code", "session_id", "seq");
COMMENT ON TABLE "session_message" IS '会话消息表';
COMMENT ON COLUMN "session_message"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "session_message"."session_id" IS '会话ID';
COMMENT ON COLUMN "session_message"."msg_no" IS '消息编号（客户端幂等键）';
COMMENT ON COLUMN "session_message"."client_msg_no" IS '客户端消息号（幂等键，同一条消息重发只落一条）';
COMMENT ON COLUMN "session_message"."seq" IS '会话内序号（从 1 开始，双方按它排序）';
COMMENT ON COLUMN "session_message"."msg_type" IS '类型码：1-文本、2-图片、3-卡片、4-事件、5-系统';
COMMENT ON COLUMN "session_message"."sender_type" IS '发送方码：1-客户、2-坐席、3-机器人、4-系统';
COMMENT ON COLUMN "session_message"."sender_id" IS '发送人ID';
COMMENT ON COLUMN "session_message"."content" IS '消息内容（JSON，含图片/卡片结构）';
COMMENT ON COLUMN "session_message"."ref_id" IS '引用消息ID';
COMMENT ON COLUMN "session_message"."status" IS '状态码：1-已发送、2-已送达、3-已读、4-失败';
COMMENT ON COLUMN "session_message"."visible_to" IS '可见范围码：1-客户与坐席都可见、2-仅坐席可见（内部备注）';
COMMENT ON COLUMN "session_message"."send_time" IS '发送时间';
CREATE INDEX "idx_session_message_tenant_session_time" ON "session_message" ("tenant_code", "session_id", "send_time");

CREATE TABLE "session_event" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "event_type" SMALLINT NOT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "from_value" VARCHAR(64) DEFAULT NULL,
  "to_value" VARCHAR(64) DEFAULT NULL,
  "remark" VARCHAR(255) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_session_event_event_type CHECK ("event_type" IN (1,2,3,4,5,6)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "session_event" IS '会话事件表';
COMMENT ON COLUMN "session_event"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "session_event"."session_id" IS '会话ID';
COMMENT ON COLUMN "session_event"."event_type" IS '事件类型码：1-转接、2-升级、3-分配、4-关闭、5-超时、6-机器人转人工';
COMMENT ON COLUMN "session_event"."operator_id" IS '操作人ID';
COMMENT ON COLUMN "session_event"."from_value" IS '来源值（原坐席/原技能组）';
COMMENT ON COLUMN "session_event"."to_value" IS '目标值';
COMMENT ON COLUMN "session_event"."remark" IS '备注';
COMMENT ON COLUMN "session_event"."event_time" IS '事件时间';
CREATE INDEX "idx_session_event_tenant_session" ON "session_event" ("tenant_code", "session_id", "event_time");

CREATE TABLE "customer" (
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
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_level CHECK ("level" IN (1,2,3,4,5)),
  CONSTRAINT ck_customer_sentiment CHECK ("sentiment" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_customer_no" UNIQUE ("tenant_code", "customer_no")
);
COMMENT ON TABLE "customer" IS '客户表';
COMMENT ON COLUMN "customer"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "customer"."customer_no" IS '客户编号（匿名访客为 128 位随机串，不可枚举）';
COMMENT ON COLUMN "customer"."name" IS '客户姓名/昵称';
COMMENT ON COLUMN "customer"."phone" IS '手机号（脱敏）';
COMMENT ON COLUMN "customer"."level" IS '会员等级码：1-普通、2-银卡、3-金卡、4-铂金、5-企业';
COMMENT ON COLUMN "customer"."channel" IS '常用渠道';
COMMENT ON COLUMN "customer"."orders_count" IS '订单数';
COMMENT ON COLUMN "customer"."total_value" IS '累计消费';
COMMENT ON COLUMN "customer"."points" IS '会员积分';
COMMENT ON COLUMN "customer"."csat" IS '满意度均值';
COMMENT ON COLUMN "customer"."sentiment" IS '情绪标签码：1-正面、2-负面、3-中性';
COMMENT ON COLUMN "customer"."last_active" IS '最近活跃时间';
CREATE INDEX "idx_customer_tenant_level" ON "customer" ("tenant_code", "level");

CREATE TABLE "customer_tag" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_id" BIGINT NOT NULL,
  "tag_name" VARCHAR(32) NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_customer_tag" UNIQUE ("tenant_code", "customer_id", "tag_name")
);
COMMENT ON TABLE "customer_tag" IS '客户标签表';
COMMENT ON COLUMN "customer_tag"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "customer_tag"."customer_id" IS '客户ID';
COMMENT ON COLUMN "customer_tag"."tag_name" IS '标签名';

CREATE TABLE "ticket" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "ticket_no" VARCHAR(40) NOT NULL,
  "ticket_type" SMALLINT NOT NULL DEFAULT 1,
  "title" VARCHAR(128) NOT NULL,
  "customer_id" BIGINT DEFAULT NULL,
  "desc" TEXT,
  "priority" SMALLINT NOT NULL DEFAULT 2,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "assignee_id" BIGINT DEFAULT NULL,
  "group_id" BIGINT DEFAULT NULL,
  "source_session_id" BIGINT DEFAULT NULL,
  "sla_deadline" TIMESTAMP DEFAULT NULL,
  "sla_state" SMALLINT DEFAULT 1,
  "close_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_ticket_ticket_type CHECK ("ticket_type" IN (1,2)),
  CONSTRAINT ck_ticket_priority CHECK ("priority" IN (1,2,3,4)),
  CONSTRAINT ck_ticket_status CHECK ("status" IN (1,2,3,4)),
  CONSTRAINT ck_ticket_sla_state CHECK ("sla_state" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_ticket_no" UNIQUE ("tenant_code", "ticket_no")
);
COMMENT ON TABLE "ticket" IS '工单表';
COMMENT ON COLUMN "ticket"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "ticket"."ticket_no" IS '工单号';
COMMENT ON COLUMN "ticket"."ticket_type" IS '类型码：1-企业内部工单、2-平台支持工单';
COMMENT ON COLUMN "ticket"."customer_id" IS '客户ID（快照，分库冗余，便于按客户查工单）';
COMMENT ON COLUMN "ticket"."title" IS '工单主题';
COMMENT ON COLUMN "ticket"."desc" IS '问题描述';
COMMENT ON COLUMN "ticket"."priority" IS '优先级码：1-低、2-中、3-高、4-紧急';
COMMENT ON COLUMN "ticket"."status" IS '状态码：1-待处理、2-处理中、3-已解决、4-已关闭';
COMMENT ON COLUMN "ticket"."assignee_id" IS '处理人ID';
COMMENT ON COLUMN "ticket"."group_id" IS '处理技能组ID';
COMMENT ON COLUMN "ticket"."source_session_id" IS '来源会话ID';
COMMENT ON COLUMN "ticket"."sla_deadline" IS 'SLA 截止时间';
COMMENT ON COLUMN "ticket"."sla_state" IS 'SLA 状态码：1-正常、2-预警、3-超时';
COMMENT ON COLUMN "ticket"."close_time" IS '关闭时间';
CREATE INDEX "idx_ticket_tenant_status_time" ON "ticket" ("tenant_code", "status", "create_time");
CREATE INDEX "idx_ticket_tenant_assignee" ON "ticket" ("tenant_code", "assignee_id");

CREATE TABLE "ticket_event" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "ticket_id" BIGINT NOT NULL,
  "event_type" SMALLINT NOT NULL,
  "operator_id" BIGINT DEFAULT NULL,
  "content" VARCHAR(512) DEFAULT NULL,
  "event_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_ticket_event_event_type CHECK ("event_type" IN (1,2,3,4,5,6)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "ticket_event" IS '工单流转记录表';
COMMENT ON COLUMN "ticket_event"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "ticket_event"."ticket_id" IS '工单ID';
COMMENT ON COLUMN "ticket_event"."event_type" IS '事件码：1-创建、2-分配、3-回复、4-升级、5-关闭、6-重开';
COMMENT ON COLUMN "ticket_event"."operator_id" IS '操作人ID';
COMMENT ON COLUMN "ticket_event"."content" IS '事件内容';
CREATE INDEX "idx_ticket_event_tenant_ticket" ON "ticket_event" ("tenant_code", "ticket_id", "event_time");

CREATE TABLE "notification" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "notify_type" SMALLINT NOT NULL,
  "title" VARCHAR(128) NOT NULL,
  "content" TEXT,
  "link_view" VARCHAR(64) DEFAULT NULL,
  "target_type" SMALLINT DEFAULT 1,
  "target_id" BIGINT DEFAULT NULL,
  "publish_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_notification_notify_type CHECK ("notify_type" IN (1,2,3,4,5,6)),
  CONSTRAINT ck_notification_target_type CHECK ("target_type" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "notification" IS '消息表';
COMMENT ON COLUMN "notification"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "notification"."notify_type" IS '类型码：1-系统、2-工单、3-审核、4-质检、5-公告、6-账单';
COMMENT ON COLUMN "notification"."title" IS '标题';
COMMENT ON COLUMN "notification"."content" IS '内容';
COMMENT ON COLUMN "notification"."link_view" IS '跳转页面标识';
COMMENT ON COLUMN "notification"."target_type" IS '目标码：1-用户、2-企业';
COMMENT ON COLUMN "notification"."target_id" IS '目标ID';
COMMENT ON COLUMN "notification"."publish_time" IS '发布时间';
CREATE INDEX "idx_notification_tenant_type_time" ON "notification" ("tenant_code", "notify_type", "publish_time");

CREATE TABLE "notification_read" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "notify_id" BIGINT NOT NULL,
  "user_id" BIGINT NOT NULL,
  "read_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_notify_user" UNIQUE ("tenant_code", "notify_id", "user_id")
);
COMMENT ON TABLE "notification_read" IS '消息已读表';
COMMENT ON COLUMN "notification_read"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "notification_read"."notify_id" IS '消息ID';
COMMENT ON COLUMN "notification_read"."user_id" IS '用户ID';
COMMENT ON COLUMN "notification_read"."read_time" IS '已读时间';

CREATE TABLE "kb_category" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "parent_id" BIGINT NOT NULL DEFAULT 0,
  "name" VARCHAR(64) NOT NULL,
  "sort_no" INT NOT NULL DEFAULT 0,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "kb_category" IS '知识分类表';
COMMENT ON COLUMN "kb_category"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "kb_category"."parent_id" IS '父分类ID';
COMMENT ON COLUMN "kb_category"."name" IS '分类名称';
COMMENT ON COLUMN "kb_category"."sort_no" IS '排序';
CREATE INDEX "idx_kb_category_tenant_parent" ON "kb_category" ("tenant_code", "parent_id");

CREATE TABLE "kb_document" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_no" VARCHAR(40) NOT NULL,
  "category_id" BIGINT DEFAULT NULL,
  "title" VARCHAR(255) NOT NULL,
  "content" TEXT,
  "summary" VARCHAR(512) DEFAULT NULL,
  "source_type" SMALLINT DEFAULT 1,
  "file_id" BIGINT DEFAULT NULL,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "hit_count" INT NOT NULL DEFAULT 0,
  "useful_rate" NUMERIC(5,2) DEFAULT NULL,
  "author" VARCHAR(64) DEFAULT NULL,
  "publish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_kb_document_source_type CHECK ("source_type" IN (1,2,3)),
  CONSTRAINT ck_kb_document_status CHECK ("status" IN (1,2,3,4)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_doc_no" UNIQUE ("tenant_code", "doc_no")
);
COMMENT ON TABLE "kb_document" IS '知识文档表';
COMMENT ON COLUMN "kb_document"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "kb_document"."doc_no" IS '知识编号';
COMMENT ON COLUMN "kb_document"."category_id" IS '分类ID';
COMMENT ON COLUMN "kb_document"."title" IS '标题';
COMMENT ON COLUMN "kb_document"."content" IS '内容';
COMMENT ON COLUMN "kb_document"."summary" IS '摘要';
COMMENT ON COLUMN "kb_document"."source_type" IS '来源码：1-手工、2-导入、3-AI生成';
COMMENT ON COLUMN "kb_document"."file_id" IS '原文件ID';
COMMENT ON COLUMN "kb_document"."status" IS '状态码：1-草稿、2-审核中、3-已发布、4-已下线';
COMMENT ON COLUMN "kb_document"."hit_count" IS '命中次数';
COMMENT ON COLUMN "kb_document"."useful_rate" IS '采纳率';
COMMENT ON COLUMN "kb_document"."author" IS '维护人';
COMMENT ON COLUMN "kb_document"."publish_time" IS '发布时间';
COMMENT ON COLUMN "kb_document"."chunk_count" IS '切片数（向量化完成后回填）';
COMMENT ON COLUMN "kb_document"."index_status" IS '索引状态：1-未索引、2-索引中、3-已索引、4-索引失败';
COMMENT ON COLUMN "kb_document"."file_name" IS '原始文件名（导入的文档）';
CREATE INDEX "idx_kb_document_tenant_status" ON "kb_document" ("tenant_code", "status");
CREATE INDEX "idx_kb_document_tenant_category" ON "kb_document" ("tenant_code", "category_id");

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE "kb_chunk" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "chunk_no" INT NOT NULL,
  "content" TEXT NOT NULL,
  "char_count" INT NOT NULL DEFAULT 0,
  "token_count" INT NOT NULL DEFAULT 0,
  "embedding" vector(1024),
  "embedding_model" VARCHAR(64) DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "kb_chunk" IS '知识文档切片表（检索的最小单位）';
CREATE INDEX "idx_kb_chunk_tenant_doc" ON "kb_chunk" ("tenant_code", "doc_id", "chunk_no");
CREATE INDEX "idx_kb_chunk_embedding" ON "kb_chunk" USING hnsw ("embedding" vector_cosine_ops)
    WHERE "embedding" IS NOT NULL;

CREATE TABLE "kb_version" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "version_no" INT NOT NULL,
  "content_snapshot" TEXT,
  "change_log" VARCHAR(255) DEFAULT NULL,
  "publisher" VARCHAR(64) DEFAULT NULL,
  "publish_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "kb_version" IS '知识版本表';
COMMENT ON COLUMN "kb_version"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "kb_version"."doc_id" IS '文档ID';
COMMENT ON COLUMN "kb_version"."version_no" IS '版本号';
COMMENT ON COLUMN "kb_version"."content_snapshot" IS '内容快照';
COMMENT ON COLUMN "kb_version"."change_log" IS '变更说明';
COMMENT ON COLUMN "kb_version"."publisher" IS '发布人';
COMMENT ON COLUMN "kb_version"."publish_time" IS '发布时间';
CREATE INDEX "idx_kb_version_tenant_doc" ON "kb_version" ("tenant_code", "doc_id", "version_no");

CREATE TABLE "kb_audit" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "doc_id" BIGINT NOT NULL,
  "version_no" INT DEFAULT NULL,
  "auditor_id" BIGINT DEFAULT NULL,
  "result" SMALLINT DEFAULT NULL,
  "reason" VARCHAR(255) DEFAULT NULL,
  "submit_time" TIMESTAMP NOT NULL,
  "audit_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_kb_audit_result CHECK ("result" IN (1,2)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "kb_audit" IS '知识审核表';
COMMENT ON COLUMN "kb_audit"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "kb_audit"."doc_id" IS '文档ID';
COMMENT ON COLUMN "kb_audit"."version_no" IS '申请版本';
COMMENT ON COLUMN "kb_audit"."auditor_id" IS '审核人ID';
COMMENT ON COLUMN "kb_audit"."result" IS '结果码：1-通过、2-驳回';
COMMENT ON COLUMN "kb_audit"."reason" IS '原因';
COMMENT ON COLUMN "kb_audit"."submit_time" IS '提交时间';
COMMENT ON COLUMN "kb_audit"."audit_time" IS '审核时间';
CREATE INDEX "idx_kb_audit_tenant_doc" ON "kb_audit" ("tenant_code", "doc_id", "submit_time");

CREATE TABLE "qa_task" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "task_no" VARCHAR(40) NOT NULL,
  "session_id" BIGINT NOT NULL,
  "agent_id" BIGINT DEFAULT NULL,
  "ai_score" NUMERIC(5,2) DEFAULT NULL,
  "ai_result" VARCHAR(512) DEFAULT NULL,
  "risk_level" SMALLINT DEFAULT 1,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "reviewer_id" BIGINT DEFAULT NULL,
  "review_score" NUMERIC(5,2) DEFAULT NULL,
  "review_time" TIMESTAMP DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_task_risk_level CHECK ("risk_level" IN (1,2,3)),
  CONSTRAINT ck_qa_task_status CHECK ("status" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_task_no" UNIQUE ("tenant_code", "task_no")
);
COMMENT ON TABLE "qa_task" IS '质检任务表';
COMMENT ON COLUMN "qa_task"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "qa_task"."task_no" IS '任务编号';
COMMENT ON COLUMN "qa_task"."session_id" IS '关联会话ID';
COMMENT ON COLUMN "qa_task"."agent_id" IS '被检坐席ID';
COMMENT ON COLUMN "qa_task"."ai_score" IS 'AI 初检评分';
COMMENT ON COLUMN "qa_task"."ai_result" IS 'AI 命中说明（JSON）';
COMMENT ON COLUMN "qa_task"."risk_level" IS '风险码：1-低、2-中、3-高';
COMMENT ON COLUMN "qa_task"."status" IS '状态码：1-待复核、2-已通过、3-已驳回';
COMMENT ON COLUMN "qa_task"."reviewer_id" IS '复核人ID';
COMMENT ON COLUMN "qa_task"."review_score" IS '复核评分';
COMMENT ON COLUMN "qa_task"."review_time" IS '复核时间';
CREATE INDEX "idx_qa_task_tenant_status" ON "qa_task" ("tenant_code", "status");
CREATE INDEX "idx_qa_task_tenant_session" ON "qa_task" ("tenant_code", "session_id");
CREATE UNIQUE INDEX "uk_qa_task_tenant_session"
    ON "qa_task" ("tenant_code", "session_id")
    WHERE "is_deleted" = FALSE AND "session_id" IS NOT NULL;

CREATE TABLE "qa_rule" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "rule_name" VARCHAR(64) NOT NULL,
  "rule_type" SMALLINT NOT NULL,
  "rule_content" TEXT,
  "weight" INT NOT NULL DEFAULT 1,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "is_realtime" BOOLEAN NOT NULL DEFAULT TRUE,
  "hit_keywords" VARCHAR(512) DEFAULT NULL,
  "severity" SMALLINT NOT NULL DEFAULT 2,
  "timeout_seconds" INTEGER NOT NULL DEFAULT 60,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_qa_rule_rule_type CHECK ("rule_type" IN (1,2,3,4,5)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "qa_rule" IS '质检规则表';
COMMENT ON COLUMN "qa_rule"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "qa_rule"."rule_name" IS '规则名称';
COMMENT ON COLUMN "qa_rule"."rule_type" IS '类型码：1-敏感词、2-承诺规范、3-必答项、4-情绪识别';
COMMENT ON COLUMN "qa_rule"."rule_content" IS '规则内容（关键词/正则/描述）';
COMMENT ON COLUMN "qa_rule"."weight" IS '权重';
COMMENT ON COLUMN "qa_rule"."is_realtime" IS '是否参与实时质检：关掉就只在会话结束后批量质检';
COMMENT ON COLUMN "qa_rule"."hit_keywords" IS '命中词表（逗号分隔）：敏感词类规则命中任意一个就告警';
COMMENT ON COLUMN "qa_rule"."severity" IS '告警级别：1-提示、2-警告、3-严重';
COMMENT ON COLUMN "qa_rule"."timeout_seconds" IS '响应超时秒数（仅"5-响应超时"类规则使用）';
CREATE INDEX "idx_qa_rule_tenant_type" ON "qa_rule" ("tenant_code", "rule_type");

CREATE TABLE "qa_alert" (
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
CREATE INDEX "idx_qa_alert_tenant_session" ON "qa_alert" ("tenant_code", "session_no", "create_time");
CREATE INDEX "idx_qa_alert_tenant_status" ON "qa_alert" ("tenant_code", "status", "create_time");
CREATE UNIQUE INDEX "uk_qa_alert_message_rule"
    ON "qa_alert" ("tenant_code", "message_id", "rule_id")
    WHERE "is_deleted" = FALSE AND "message_id" IS NOT NULL AND "rule_id" IS NOT NULL;

CREATE TABLE "qa_review" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "task_id" BIGINT NOT NULL,
  "reviewer_id" BIGINT NOT NULL,
  "action" SMALLINT NOT NULL,
  "comment" VARCHAR(512) DEFAULT NULL,
  "review_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT ck_qa_review_action CHECK ("action" IN (1,2,3)),
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "qa_review" IS '质检复核记录表';
COMMENT ON COLUMN "qa_review"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "qa_review"."task_id" IS '质检任务ID';
COMMENT ON COLUMN "qa_review"."reviewer_id" IS '复核人ID';
COMMENT ON COLUMN "qa_review"."action" IS '动作码：1-通过、2-驳回、3-重检';
COMMENT ON COLUMN "qa_review"."comment" IS '复核意见';
CREATE INDEX "idx_qa_review_tenant_task" ON "qa_review" ("tenant_code", "task_id", "review_time");

CREATE TABLE "file_meta" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "file_no" VARCHAR(40) NOT NULL,
  "file_name" VARCHAR(255) NOT NULL,
  "object_key" VARCHAR(512) NOT NULL,
  "file_size" BIGINT NOT NULL DEFAULT 0,
  "mime_type" VARCHAR(128) DEFAULT NULL,
  "biz_type" SMALLINT NOT NULL,
  "biz_id" BIGINT DEFAULT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_file_meta_biz_type CHECK ("biz_type" IN (1,2,3,4,5)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_file_no" UNIQUE ("tenant_code", "file_no")
);
COMMENT ON TABLE "file_meta" IS '文件元数据表';
COMMENT ON COLUMN "file_meta"."id" IS '主键ID（雪花算法生成）';
COMMENT ON COLUMN "file_meta"."file_no" IS '文件编号';
COMMENT ON COLUMN "file_meta"."file_name" IS '原始文件名';
COMMENT ON COLUMN "file_meta"."object_key" IS 'OSS 对象键';
COMMENT ON COLUMN "file_meta"."file_size" IS '大小（字节）';
COMMENT ON COLUMN "file_meta"."mime_type" IS 'MIME 类型';
COMMENT ON COLUMN "file_meta"."biz_type" IS '业务类型码：1-头像、2-附件、3-导出、4-发票、5-聊天图片';
COMMENT ON COLUMN "file_meta"."biz_id" IS '业务ID';
CREATE INDEX "idx_file_meta_tenant_biz" ON "file_meta" ("tenant_code", "biz_type", "biz_id");

CREATE TABLE "quick_reply" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "title" VARCHAR(64) NOT NULL,
  "content" VARCHAR(512) NOT NULL,
  "category" VARCHAR(32) DEFAULT NULL,
  "sort_no" INT NOT NULL DEFAULT 0,
  "is_enabled" BOOLEAN NOT NULL DEFAULT TRUE,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY ("id")
);
COMMENT ON TABLE "quick_reply" IS '快捷回复话术表';
COMMENT ON COLUMN "quick_reply"."title" IS '话术标题（如：问候语）';
COMMENT ON COLUMN "quick_reply"."content" IS '话术内容';
COMMENT ON COLUMN "quick_reply"."category" IS '分组（如：售前/售后/致歉）';
CREATE INDEX "idx_quick_reply_tenant_sort" ON "quick_reply" ("tenant_code", "sort_no");

CREATE TABLE "skill_group_member" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "skill_group_id" BIGINT NOT NULL,
  "user_id" BIGINT NOT NULL,
  "is_leader" BOOLEAN NOT NULL DEFAULT FALSE,
  "status" SMALLINT NOT NULL DEFAULT 1,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_skill_group_member_status CHECK ("status" IN (1,2)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_skill_group_member" UNIQUE ("skill_group_id", "user_id")
);
COMMENT ON TABLE "skill_group_member" IS '技能组坐席绑定表';
COMMENT ON COLUMN "skill_group_member"."skill_group_id" IS '技能组ID';
COMMENT ON COLUMN "skill_group_member"."user_id" IS '坐席用户ID';
COMMENT ON COLUMN "skill_group_member"."is_leader" IS '是否组长';
COMMENT ON COLUMN "skill_group_member"."status" IS '状态码：1-在组、2-已移出';
CREATE INDEX "idx_skill_group_member_tenant" ON "skill_group_member" ("tenant_code", "skill_group_id");

CREATE TABLE "csat_record" (
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
COMMENT ON TABLE "csat_record" IS '满意度评价明细表';
COMMENT ON COLUMN "csat_record"."session_id" IS '会话ID';
COMMENT ON COLUMN "csat_record"."score" IS '评分 1-5';
COMMENT ON COLUMN "csat_record"."feedback" IS '评价内容';
CREATE INDEX "idx_csat_tenant_time" ON "csat_record" ("tenant_code", "evaluate_time");

CREATE TABLE "customer_order" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "customer_id" BIGINT NOT NULL,
  "order_no" VARCHAR(40) NOT NULL,
  "product_name" VARCHAR(128) DEFAULT NULL,
  "product_file_id" BIGINT DEFAULT NULL,
  "amount" NUMERIC(12,2) NOT NULL DEFAULT 0,
  "order_status" SMALLINT NOT NULL DEFAULT 1,
  "order_time" TIMESTAMP NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_customer_order_status CHECK ("order_status" IN (1,2,3,4,5)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_customer_order_no" UNIQUE ("tenant_code", "order_no")
);
COMMENT ON TABLE "customer_order" IS '客户订单表';
COMMENT ON COLUMN "customer_order"."customer_id" IS '客户ID';
COMMENT ON COLUMN "customer_order"."order_no" IS '订单号';
COMMENT ON COLUMN "customer_order"."product_name" IS '商品名称';
COMMENT ON COLUMN "customer_order"."product_file_id" IS '商品图文件ID';
COMMENT ON COLUMN "customer_order"."amount" IS '订单金额';
COMMENT ON COLUMN "customer_order"."order_status" IS '状态码：1-待付款、2-已付款、3-已发货、4-已完成、5-已退款';
COMMENT ON COLUMN "customer_order"."order_time" IS '下单时间';
CREATE INDEX "idx_customer_order_tenant_customer" ON "customer_order" ("tenant_code", "customer_id");

CREATE TABLE "tenant_config" (
  "id" BIGINT NOT NULL,
  "tenant_code" VARCHAR(16) NOT NULL,
  "config_type" SMALLINT NOT NULL DEFAULT 1,
  "config_json" JSON NOT NULL,
  "create_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "update_time" TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  "creator" VARCHAR(64) DEFAULT NULL,
  "editor" VARCHAR(64) DEFAULT NULL,
  "is_deleted" BOOLEAN NOT NULL DEFAULT FALSE,
  CONSTRAINT ck_tenant_config_type CHECK ("config_type" IN (1,2,3)),
  PRIMARY KEY ("id"),
  CONSTRAINT "uk_tenant_config_type" UNIQUE ("tenant_code", "config_type")
);
COMMENT ON TABLE "tenant_config" IS '租户级配置表（系统设置）';
COMMENT ON COLUMN "tenant_config"."config_type" IS '配置类型码：1-通知偏好、2-自动化工作流、3-安全设置';
COMMENT ON COLUMN "tenant_config"."config_json" IS '配置内容（JSON）';
```

### 文件：scripts/migrate-customer-db.sh

增量脚本执行器纳入新脚本：顺序在 `customer_db_bot_brain.sql` 之后

``` code-block-container
#!/usr/bin/env bash
# 把 customer_db 的所有增量脚本按顺序跑一遍（每个脚本都是"可重复执行"的，多跑无副作用）。
#
# 用法：
#   bash scripts/migrate-customer-db.sh
#   PGHOST=127.0.0.1 PGPORT=5432 PGUSER=mac PGPASSWORD=123456 DB=customer_db bash scripts/migrate-customer-db.sh
#
# 什么时候要跑：customer-service 启动日志里出现"数据库结构不完整"，
# 或者接口报 column "xxx" does not exist / relation "xxx" does not exist。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="${DB:-customer_db}"
PGHOST="${PGHOST:-127.0.0.1}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-mac}"

# 找不到 psql 就试着用 Postgres.app 自带的那份
PSQL="${PSQL:-$(command -v psql || true)}"
if [ -z "$PSQL" ] && [ -x "/Applications/Postgres.app/Contents/Versions/latest/bin/psql" ]; then
  PSQL="/Applications/Postgres.app/Contents/Versions/latest/bin/psql"
fi
if [ -z "$PSQL" ]; then
  echo "找不到 psql，请设置 PSQL=/path/to/psql 后重试" >&2
  exit 1
fi

export PGPASSWORD="${PGPASSWORD:-123456}"
export PGHOST PGPORT PGUSER

# 顺序按依赖排：先有的表先建，后加的列后补
SCRIPTS=(
  customer_db_security.sql
  customer_db_collab.sql
  customer_db_delivery.sql
  customer_db_qa.sql
  customer_db_routing.sql
  customer_db_realtime_qa.sql
  customer_db_qa_source.sql
  customer_db_qa_timeout.sql
  customer_db_kb.sql
  customer_db_bot_brain.sql
  customer_db_chat_image.sql
)

echo "目标库：${PGUSER}@${PGHOST}:${PGPORT}/${DB}"
for script in "${SCRIPTS[@]}"; do
  printf '  → %-32s' "$script"
  "$PSQL" -q -v ON_ERROR_STOP=1 -d "$DB" -f "${ROOT}/schema/${script}" > /tmp/migrate-$$.log 2>&1 \
    && echo "完成" \
    || { echo "失败"; tail -10 /tmp/migrate-$$.log; rm -f /tmp/migrate-$$.log; exit 1; }
done
rm -f /tmp/migrate-$$.log

echo
echo "全部完成。核对一下关键结构："
"$PSQL" -d "$DB" -c "select rule_name, rule_type, is_enabled, is_realtime, timeout_seconds
                       from qa_rule
                      where is_deleted = false
                      order by rule_type;"
"$PSQL" -d "$DB" -c "select column_name, data_type
                       from information_schema.columns
                      where table_name = 'session' and column_name = 'bot_transfer_reason';"
```

### 3.2 运行期副本

后端启动时要能自动补结构，所以 `resources/schema/` 下必须有一份内容完全一样的副本——这是第 22 篇定下的纪律（`scripts/check-schema-copies.sh` 会检查）：

### 复制：schema/customer\_db\_chat\_image.sql → yunti-backend/yunti-customer-service/src/main/resources/schema/customer\_db\_chat\_image.sql

运行期副本：后端启动自检发现约束没放开时，就是执行它来修。

源文件的权威内容就在前面（`schema/customer_db_chat_image.sql`），运行期这份副本内容一模一样——直接在工程里复制一份即可：`cp schema/customer_db_chat_image.sql yunti-backend/yunti-customer-service/src/main/resources/schema/customer_db_chat_image.sql`。

### 3.3 启动自检：把"约束没放开"提前到启动时说出来

第 22 篇的自检只查了"表在不在、列在不在、宽度够不够"。这次踩的坑说明还不够：**列都在、宽度也够，插入照样会被 CHECK 挡下来**。所以给自检加一类检查：约束定义里必须出现某个取值。

### 改动：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/SchemaMapper.java

改动点：新增查询：把所有 CHECK 约束的定义文本捞出来

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 24 行附近）**

原来是这样：

``` code-block-container
     * （file_meta.mime_type 就是这么被 Office 文档的 MIME 撑爆的）。</p>
     */
    List<Map<String, Object>> selectColumnLengths();
}
```

改成：

``` code-block-container
     * （file_meta.mime_type 就是这么被 Office 文档的 MIME 撑爆的）。</p>
     */
    List<Map<String, Object>> selectColumnLengths();

    /**
     * 约束定义："约束名" → 定义文本（pg_get_constraintdef 的结果）。
     *
     * <p>为什么要检查约束：列都在、宽度也够，插入照样可能被 CHECK 挡下来
     * （聊天图片的 biz_type=5 就是这么被 file_meta 的约束拦住的）。
     * 这种问题看代码看不出来，只能拿约束文本比。</p>
     */
    List<Map<String, Object>> selectConstraintDefs();
}
```

### 改动：yunti-backend/yunti-customer-service/src/main/resources/mapper/SchemaMapper.xml

改动点：对应的 SQL：`pg_get_constraintdef` 拿到约束原文

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 23 行附近）**

原来是这样：

``` code-block-container
           AND character_maximum_length IS NOT NULL
    </select>
</mapper>
```

改成：

``` code-block-container
           AND character_maximum_length IS NOT NULL
    </select>
    <!-- CHECK 约束的定义文本：自检据此判断"这个取值放开了没有" -->
    <select id="selectConstraintDefs" resultType="java.util.Map">
        SELECT c.conname                  AS constraint_name,
               pg_get_constraintdef(c.oid) AS definition
          FROM pg_constraint c
          JOIN pg_class t ON t.oid = c.conrelid
          JOIN pg_namespace n ON n.oid = t.relnamespace
         WHERE n.nspname = current_schema()
           AND c.contype = 'c'
    </select>
</mapper>
```

### 改动：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/config/SchemaGuard.java

改动点：启动自检新增"必需约束取值"：约束名 → 必须出现的字面量 + 来源脚本 + 说明；缺了就自动补，补不上就说清跑哪个脚本

这个文件一共 6 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 53 行附近）**

原来是这样：

``` code-block-container
            "customer_db_qa_source.sql",
            "customer_db_qa_timeout.sql",
            "customer_db_kb.sql",
            "customer_db_bot_brain.sql"
    );

    /** 代码依赖的表 → 来源脚本 */
```

改成：

``` code-block-container
            "customer_db_qa_source.sql",
            "customer_db_qa_timeout.sql",
            "customer_db_kb.sql",
            "customer_db_bot_brain.sql",
            "customer_db_chat_image.sql"
    );

    /** 代码依赖的表 → 来源脚本 */
```

**新增 2（第 77 行附近）**

原来是这样：

``` code-block-container
     */
    private static final Map<String, Integer> REQUIRED_MIN_LENGTH = new LinkedHashMap<>();

    static {
        REQUIRED_TABLES.put("agent_status", "customer_db_routing.sql");
        REQUIRED_TABLES.put("qa_rule", "customer_db_qa.sql");
```

改成：

``` code-block-container
     */
    private static final Map<String, Integer> REQUIRED_MIN_LENGTH = new LinkedHashMap<>();

    /**
     * 必需约束取值：约束名 → (必须出现的字面量, 来源脚本, 说明)。
     *
     * <p>列都在、宽度也够，插入仍然可能被 CHECK 挡下来——
     * 聊天图片的 `file_meta.biz_type=5` 就是这么被 `biz_type IN (1,2,3,4)` 拦住的，
     * 报错发生在插入那一刻，离"少跑一个脚本"隔着好几层调用栈。
     * 启动时拿约束文本比一下，就能在启动日志里说清楚。</p>
     *
     * <p>Postgres 会把 `IN (1,2,3,4)` 规范化成 `= ANY (ARRAY['1'::smallint, ...])`，
     * 所以这里比的是带引号的字面量。</p>
     */
    private static final Map<String, ConstraintRule> REQUIRED_CONSTRAINTS = new LinkedHashMap<>();

    private record ConstraintRule(String literal, String script, String note) {
    }

    static {
        REQUIRED_TABLES.put("agent_status", "customer_db_routing.sql");
        REQUIRED_TABLES.put("qa_rule", "customer_db_qa.sql");
```

**新增 3（第 117 行附近）**

原来是这样：

``` code-block-container
        REQUIRED_COLUMNS.put("session.bot_transfer_reason", "customer_db_bot_brain.sql");

        REQUIRED_MIN_LENGTH.put("file_meta.mime_type", 128);
    }

    private final SchemaMapper schemaMapper;
```

改成：

``` code-block-container
        REQUIRED_COLUMNS.put("session.bot_transfer_reason", "customer_db_bot_brain.sql");

        REQUIRED_MIN_LENGTH.put("file_meta.mime_type", 128);

        // 聊天图片要写进 file_meta，biz_type=5 必须在允许列表里
        REQUIRED_CONSTRAINTS.put("ck_file_meta_biz_type",
                new ConstraintRule("'5'", "customer_db_chat_image.sql",
                        "file_meta.biz_type 允许 5-聊天图片"));
    }

    private final SchemaMapper schemaMapper;
```

**新增 4（第 181 行附近）**

原来是这样：

``` code-block-container
    private void checkAndRepair() {
        Map<String, List<String>> missing = missingObjects();
        missing.putAll(missingLengths());
        if (missing.isEmpty()) {
            log.info("启动自检通过：customer_db 的表与列都齐全");
            return;
```

改成：

``` code-block-container
    private void checkAndRepair() {
        Map<String, List<String>> missing = missingObjects();
        missing.putAll(missingLengths());
        missing.putAll(missingConstraints());
        if (missing.isEmpty()) {
            log.info("启动自检通过：customer_db 的表与列都齐全");
            return;
```

**新增 5（第 209 行附近）**

原来是这样：

``` code-block-container
        Map<String, List<String>> stillMissing = missingObjects();
        stillMissing.putAll(missingLengths());
        if (stillMissing.isEmpty()) {
            log.info("已自动补齐数据库结构（执行的脚本：{}）", String.join("、", missing.keySet()));
            return;
```

改成：

``` code-block-container
        Map<String, List<String>> stillMissing = missingObjects();
        stillMissing.putAll(missingLengths());
        stillMissing.putAll(missingConstraints());
        if (stillMissing.isEmpty()) {
            log.info("已自动补齐数据库结构（执行的脚本：{}）", String.join("、", missing.keySet()));
            return;
```

**新增 6（第 239 行附近）**

原来是这样：

``` code-block-container
        return result;
    }

    private Map<String, List<String>> missingObjects() {
        Set<String> tables = new HashSet<>(schemaMapper.selectTables());
        Set<String> columns = new HashSet<>(schemaMapper.selectColumns());
```

改成：

``` code-block-container
        return result;
    }

    /**
     * 约束取值检查：约束在、但没放开我们要用的取值 → 照样要跑脚本。
     *
     * <p>查不到约束（比如表还没建）不算问题：那种情况上面的"缺表"检查会先报出来。</p>
     */
    private Map<String, List<String>> missingConstraints() {
        if (REQUIRED_CONSTRAINTS.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> definitions = new HashMap<>();
        try {
            for (Map<String, Object> row : schemaMapper.selectConstraintDefs()) {
                String name = row.get("constraint_name") == null ? null : String.valueOf(row.get("constraint_name"));
                if (name != null) {
                    definitions.put(name, row.get("definition") == null ? "" : String.valueOf(row.get("definition")));
                }
            }
        } catch (Exception e) {
            log.warn("读取约束定义失败，跳过约束自检：{}", e.getMessage());
            return new LinkedHashMap<>();
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, ConstraintRule> entry : REQUIRED_CONSTRAINTS.entrySet()) {
            String definition = definitions.get(entry.getKey());
            if (definition == null) {
                continue;   // 约束不存在：交给"缺表/缺列"那套检查去报
            }
            if (!definition.contains(entry.getValue().literal())) {
                result.computeIfAbsent(entry.getValue().script(), key -> new ArrayList<>())
                        .add(entry.getValue().note());
            }
        }
        return result;
    }

    private Map<String, List<String>> missingObjects() {
        Set<String> tables = new HashSet<>(schemaMapper.selectTables());
        Set<String> columns = new HashSet<>(schemaMapper.selectColumns());
```

## 四、yunti-ai：视觉模型接入层

### 4.1 配置：视觉模型和文本模型共用同一个千问密钥

### 改动：yunti-ai/ai/config.py

改动点：新增视觉模型名（默认 `qwen-vl-plus`）、一次最多几张图（3）、单张最大多少 MB（6）

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 28 行附近）**

原来是这样：

``` code-block-container
    qwen_api_key: str = ""
    qwen_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_model: str = "qwen-plus"

    # DeepSeek OpenAI 兼容接口
    deepseek_api_key: str = ""
```

改成：

``` code-block-container
    qwen_api_key: str = ""
    qwen_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    qwen_model: str = "qwen-plus"
    # 视觉模型（多模态）：客户发图片时用它做 OCR + 截图判读。
    # 千问 VL 走的是同一个 DashScope OpenAI 兼容接口，只是模型名不同。
    qwen_vl_model: str = "qwen-vl-plus"
    # 单次最多识别几张图、每张图最大多少 MB（转 base64 后 payload 会涨约 1/3，必须设上限）
    vision_max_images: int = 3
    vision_max_image_mb: int = 6

    # DeepSeek OpenAI 兼容接口
    deepseek_api_key: str = ""
```

### 改动：yunti-ai/.env.example

改动点：把视觉模型相关的开关写进示例配置：模型名可换、多图上限和单张上限可调

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 27 行附近）**

原来是这样：

``` code-block-container
YUNTI_AI_KB_CHUNK_SIZE=600
YUNTI_AI_KB_CHUNK_OVERLAP=80
# 知识库所在库（留空则从 ai_db 推导成 customer_db）
YUNTI_AI_KB_DATABASE_URL=
```

改成：

``` code-block-container
YUNTI_AI_KB_CHUNK_SIZE=600
YUNTI_AI_KB_CHUNK_OVERLAP=80
# 知识库所在库（留空则从 ai_db 推导成 customer_db）
YUNTI_AI_KB_DATABASE_URL=

# 视觉模型（客户发图时用它做 OCR / 截图判读，默认 qwen-vl-plus；和文本模型共用同一个千问密钥）
# YUNTI_AI_QWEN_VL_MODEL=qwen-vl-plus
# 一条消息最多看几张图 / 单张 data URL 上限（MB）：前端也卡 3 张，别只改一边
YUNTI_AI_VISION_MAX_IMAGES=3
YUNTI_AI_VISION_MAX_IMAGE_MB=6
```

### 4.2 多模态调用

### 文件：yunti-ai/ai/core/llm\_vision.py

多模态调用的收口层：挑模型（租户在「智能机器人 → 模型选择」里选的视觉模型优先，否则用平台默认 `qwen-vl-plus`）、卡张数与单张大小、按 OpenAI 兼容格式发请求、把完整入参出参打进日志（排障时"模型到底看到了什么"必须有据可查）。

``` code-block-container
"""多模态（视觉）调用：把图片交给视觉大模型做 OCR 与截图判读。

参考企业智能招聘系统的做法，分工是**Java 压图、Python 发请求**：

| 谁 | 干什么 | 为什么 |
| --- | --- | --- |
| Java（customer-service） | 从对象存储取原图 → 缩放成 JPEG → base64 data URL | 图片在 RustFS 里，Java 手里有对象存储客户端；压缩后 payload 可控 |
| Python（这里） | 按 OpenAI 兼容的多模态格式发出去，解析结构化结果 | 各家的多模态格式大同小异，收口在一层里，换模型不改业务 |

调用形态（DashScope 兼容模式，与千问文本接口同一套协议）：

```json
{
  "model": "qwen-vl-plus",
  "messages": [{
    "role": "user",
    "content": [
      {"type": "text", "text": "请识别这张图…"},
      {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}
    ]
  }]
}
```

**为什么不用传统 OCR API**：客服场景要的不只是"把字抠出来"，还要"看懂这张图在说什么" （这是报错截图？订单截图？商品破损照？），多模态模型一次就能给出"文字 + 判读"； 招 Java 侧的招聘系统也是这么干的（PDF → 图片 → qwen-vl）。 """

from **future** import annotations

import logging import time from typing import Any

import httpx

from ..config import get\_settings from ..core.db import connect from ..core.llm\_chat import provider\_conf from ..core.trace import get\_trace\_id

logger = logging.getLogger(**name**)

def vision\_provider(tenant\_code: str = "") -&gt; tuple\[dict\[str, str\] \| None, str\]: """挑一个可用的视觉模型。

``` code-block-container
优先级：租户在「智能机器人 → 模型选择」里配的**视觉模型**（`bot_model.model_type=2`）
→ 全局默认（`YUNTI_AI_QWEN_VL_MODEL`，默认 `qwen-vl-plus`）。

@return (供应商配置, 说明)；没有任何千问密钥时返回 (None, 原因)
"""
settings = get_settings()
if not settings.qwen_api_key:
    return None, "未配置千问密钥（YUNTI_AI_QWEN_API_KEY），视觉识别不可用"

tenant_model = _tenant_vision_model(tenant_code)
model = tenant_model or settings.qwen_vl_model
source = "租户配置" if tenant_model else "平台默认"
return (provider_conf("qwen", settings.qwen_api_key, settings.qwen_base_url, model),
        f"qwen:{model}（{source}）")
```

def \_tenant\_vision\_model(tenant\_code: str) -&gt; str \| None: """租户在模型选择里挑的视觉模型（没配返回 None）。

``` code-block-container
优先取「智能机器人 → 模型选择」里选中的那个（`bot_setting.model_key` 且类型是视觉），
选中的不是视觉模型、或者租户加过多个视觉模型时，退回"第一个启用的视觉模型"——
这样页面上换了模型，识别这里立刻跟着换，不用改配置重启。

读失败一律降级到平台默认：识别功能不能因为一张配置表读不到就整条不可用。
"""
if not tenant_code:
    return None
try:
    with connect() as conn:
        row = conn.execute(
            """
            SELECT m.model_key
              FROM bot_setting s
              JOIN bot_model m
                ON m.tenant_code = s.tenant_code
               AND m.is_deleted = FALSE
               AND m.is_enabled = TRUE
             WHERE s.tenant_code = %s AND s.is_deleted = FALSE AND m.model_type = 2
             ORDER BY (m.model_key = s.model_key) DESC, m.id
             LIMIT 1
            """,
            (tenant_code,),
        ).fetchone()
except Exception as exc:  # noqa: BLE001
    logger.debug("读取租户视觉模型失败 tenant=%s error=%s", tenant_code, exc)
    return None
return str(row["model_key"]) if row and row.get("model_key") else None
```

async def call\_vision(chosen: dict\[str, str\], prompt: str, images: list\[str\], timeout: int, \*, scene: str = "图片识别") -&gt; dict\[str, Any\]: """发一次多模态请求。

``` code-block-container
@param images 形如 ``data:image/jpeg;base64,...`` 的 data URL 列表
"""
settings = get_settings()
if not images:
    raise ValueError("多模态调用至少要有一张图片")
if len(images) > settings.vision_max_images:
    raise ValueError(f"一次最多识别 {settings.vision_max_images} 张图片")
# 单张大小也要卡：Java 侧压到 100~300KB 才送过来，这里防的是"别的调用方直接把原图 base64 塞进来"，
# 那会让 body 涨到十几 MB，网关截断后报出来的错误跟图片本身一点关系都没有，很难查
limit_mb = settings.vision_max_image_mb
oversized = [index for index, image in enumerate(images, start=1)
             if len(image) > limit_mb * 1024 * 1024]
if oversized:
    raise ValueError(
        f"第 {'、'.join(str(index) for index in oversized)} 张图超过 {limit_mb}MB"
        "（data URL 长度），请压缩后再送识别")

trace = get_trace_id() or "-"
content: list[dict[str, Any]] = [{"type": "text", "text": prompt}]
for index, image in enumerate(images, start=1):
    content.append({"type": "image_url", "image_url": {"url": image}})
    logger.info("%s入参 trace=%s provider=%s model=%s 第%d张图 bytes≈%d",
                scene, trace, chosen["provider"], chosen["model"], index, len(image))

payload = {
    "model": chosen["model"],
    "temperature": 0.1,
    "messages": [{"role": "user", "content": content}],
}
started = time.perf_counter()
async with httpx.AsyncClient(timeout=timeout) as client:
    response = await client.post(f"{chosen['base_url']}/chat/completions",
                                 headers={"Authorization": f"Bearer {chosen['api_key']}",
                                          "Content-Type": "application/json"},
                                 json=payload)
    if response.status_code >= 400:
        # 和文本调用一个原则：厂商的报错正文必须带出来（"Model not exist" 全在正文里）
        logger.error("%s调用失败 trace=%s provider=%s model=%s status=%d body=%s",
                     scene, trace, chosen["provider"], chosen["model"],
                     response.status_code, response.text[:600])
        raise RuntimeError(f"{chosen['provider']} 返回 {response.status_code}："
                           f"{response.text[:300]}")
    data = response.json()
logger.info("%s出参 trace=%s provider=%s model=%s cost=%dms body=%s",
            scene, trace, chosen["provider"], chosen["model"],
            int((time.perf_counter() - started) * 1000),
            str(data)[:1200])
return data
```

def message\_content(data: dict\[str, Any\]) -&gt; str: """从响应里取正文（多模态响应结构和文本一样，都是 choices\[0\].message.content）。""" choices = data.get("choices") or \[\] if not choices: return "" message = choices\[0\].get("message") or {} return str(message.get("content") or "").strip()

``` code-block-container
### 4.3 识别服务
### 文件：yunti-ai/ai/services/vision.py
识别服务：提示词要求模型**同时给两样东西**——`ocrText`（把图里的字逐条抄出来）和 `summary`（一句话判读），外加订单号/金额/报错原文/是否建议人工这几个能直接进业务的结构化字段。JSON 解析失败就退回纯文本摘要，绝不返回半截数据；没配密钥就如实说不可用，不编识别结果。

```python
"""图片识别 / OCR / 截图判读：把客户发来的图看懂，并给出一句话结论。

客服场景里客户爱发图，而且图里的信息往往比文字更关键：

| 客户发什么 | 需要模型给出什么 |
| --- | --- |
| 报错截图 | 报错原文（OCR）+ 这是哪类问题 |
| 订单/账单截图 | 订单号、金额、时间（结构化抽出来） |
| 商品破损照 | 一句话描述（不用 OCR） |
| 聊天记录截图 | 对话原文（OCR） |

所以这一层要求模型**同时给两样东西**：`ocrText`（把图里的字逐条抄出来）和 `summary`
（一句话判读），外加几个能直接进业务的结构化字段（订单号 / 金额 / 报错关键字）。

两个原则和第 21、22 篇一致：
    1. **没配密钥不假装能识别**：直接返回 `available=false` 并说明原因，绝不编一段"我看到图上写着…"；
    2. **模型的输出要能核对**：JSON 解析失败就退回纯文本（把正文当 summary），不让业务拿到半截数据。
"""

from __future__ import annotations

import json
import logging
import re
from typing import Any

from ..config import get_settings
from ..core.llm_vision import call_vision, message_content, vision_provider
from ..core.trace import get_trace_id

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """你是企业在线客服的图片识别助手。请阅读客户发来的图片，输出一个 JSON 对象。

要求：
1. `ocrText`：把图片里的文字**逐字抄下来**（保持原有换行，OCR 做不到别猜，抄不到就留空字符串）；
2. `summary`：一句话说明"这是什么图、客户想解决什么"，用中文，40 字以内；
3. `orderNo`：图里出现的订单号 / 单号（没有就空字符串）；
4. `amount`：图里出现的金额（形如 "199.00"，没有就空字符串）；
5. `errorText`：如果这是报错截图，抄下**报错原文**（没有就空字符串）；
6. `needHuman`：图片涉及退款失败、支付异常、账号被盗、投诉等敏感情形时为 true；
7. 只输出 JSON，不要用代码块包裹，不要解释推理过程。

格式：
{"ocrText": "...", "summary": "...", "orderNo": "", "amount": "", "errorText": "", "needHuman": false}"""


async def recognize(images: list[str], question: str = "", tenant_code: str = "") -> dict[str, Any]:
    """识别一组图片。

    @param images    data URL 列表（Java 侧压好 JPEG 再 base64 送来）
    @param question  客户随图说的一句话（可能为空）
    @return 统一结构：available / summary / ocrText / orderNo / amount / errorText / needHuman / provider / model
    """
    result: dict[str, Any] = {
        "available": False,
        "summary": "",
        "ocr_text": "",
        "order_no": "",
        "amount": "",
        "error_text": "",
        "need_human": False,
        "provider": "",
        "model": "",
        "hint": "",
    }
    if not images:
        result["hint"] = "没有可识别的图片"
        return result

    chosen, desc = vision_provider(tenant_code)
    if chosen is None:
        # 没有密钥就如实说，绝不编识别结果——编出来的"图上写着 XXX"比不识别更害人
        logger.warning("图片识别不可用 tenant=%s 原因=%s", tenant_code or "-", desc)
        result["hint"] = desc + "；配好 YUNTI_AI_QWEN_API_KEY 后即可识别图片"
        return result

    prompt = SYSTEM_PROMPT
    if question and question.strip():
        prompt += f"\n\n客户随图说的一句是：{question.strip()[:200]}"

    logger.info("图片识别请求 trace=%s tenant=%s 张数=%d 单张≈%dKB 模型=%s 客户随图说=%s",
                get_trace_id() or "-", tenant_code or "-", len(images),
                len(images[0]) // 1024 if images else 0, desc, (question or "")[:40])
    data = await call_vision(chosen, prompt, images, get_settings().llm_timeout, scene="图片识别")
    content = message_content(data)
    parsed = _parse_json(content)

    result.update({
        "available": True,
        "provider": chosen["provider"],
        "model": chosen["model"],
        "summary": _text(_pick(parsed, "summary")) or _fallback_summary(content),
        "ocr_text": _text(_pick(parsed, "ocrText", "ocr_text")),
        "order_no": _text(_pick(parsed, "orderNo", "order_no")),
        "amount": _text(_pick(parsed, "amount")),
        "error_text": _text(_pick(parsed, "errorText", "error_text")),
        "need_human": bool(_pick(parsed, "needHuman", "need_human") or False),
    })
    logger.info(
        "图片识别完成 trace=%s tenant=%s 模型=%s\n"
        "  判读：%s\n"
        "  订单号：%s 金额：%s 建议人工：%s\n"
        "  报错原文：%s\n"
        "  图中文字（共 %d 字）：\n%s",
        get_trace_id() or "-", tenant_code or "-", desc,
        result["summary"] or "（无）",
        result["order_no"] or "（无）", result["amount"] or "（无）", result["need_human"],
        _inline(result["error_text"], 200) or "（无）",
        len(result["ocr_text"]), _indent(result["ocr_text"]))
    return result


def _indent(text: str, limit: int = 800) -> str:
    """把 OCR 全文按行缩进打进日志（超长截断），方便直接和客户发的图对照"""
    if not text:
        return "    （没识别到文字）"
    lines = [line.strip() for line in text.splitlines() if line.strip()][:20]
    body = "\n".join("    " + line for line in lines)
    if len(body) > limit:
        body = body[:limit] + "\n    …（已截断）"
    return body


def _inline(text: str, limit: int) -> str:
    return re.sub(r"\s+", " ", (text or "")).strip()[:limit]


def _pick(parsed: dict[str, Any] | None, *keys: str) -> Any:
    """从解析结果里按多个候选键名取值（模型有时写驼峰、有时写下划线）。"""
    if not parsed:
        return None
    for key in keys:
        if key in parsed and parsed[key] not in (None, ""):
            return parsed[key]
    return None


def _text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (list, dict)):
        return json.dumps(value, ensure_ascii=False)
    return str(value).strip()


def _parse_json(text: str) -> dict[str, Any] | None:
    """从模型输出里抠 JSON：先整体解析，不行再取第一对花括号。"""
    if not text:
        return None
    candidate = text.strip()
    if candidate.startswith("```"):
        candidate = re.sub(r"^```[a-zA-Z]*\s*", "", candidate)
        candidate = re.sub(r"\s*```$", "", candidate)
    try:
        parsed = json.loads(candidate)
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        pass
    start = candidate.find("{")
    end = candidate.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        parsed = json.loads(candidate[start:end + 1])
        return parsed if isinstance(parsed, dict) else None
    except json.JSONDecodeError:
        return None


def _fallback_summary(content: str) -> str:
    """模型没按 JSON 输出时，把正文当摘要（截断），总比丢掉强。"""
    text = (content or "").strip().replace("\n", " ")
    return text[:120]
```

### 4.4 接口与体检

### 文件：yunti-ai/ai/api/vision.py

两个接口：`POST /api/ai/v1/agent/vision` 识别一组图；`GET /api/ai/v1/agent/vision/health` 体检（当前会用哪个视觉模型、密钥读到没有、一次最多几张）。

``` code-block-container
"""图片识别接口：客户发来的图，看懂它。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /api/ai/v1/agent/vision | 识别一组图片：OCR 原文 + 一句话判读 + 结构化字段 |
| GET | /api/ai/v1/agent/vision/health | 探活 + 体检（用的是哪个视觉模型、密钥读到没有） |

调用方是 customer-service：它把图片压成 JPEG 的 base64 data URL 送过来（图片存在 RustFS，Java 手里有存储客户端）。
"""

from __future__ import annotations

import logging
import time
from typing import Any

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field

from ..config import get_settings
from ..core.llm_vision import vision_provider
from ..core.trace import require_trace_id
from ..services import vision as vision_service

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/ai/v1/agent", tags=["vision"])


class VisionRequest(BaseModel):
    tenant_code: str = Field(min_length=1, max_length=32)
    session_id: str = Field(default="", max_length=64)
    # data URL 列表，形如 data:image/jpeg;base64,...；由 Java 侧压缩后送来
    images: list[str] = Field(default_factory=list)
    question: str = Field(default="", max_length=500)


@router.post("/vision")
async def vision(body: VisionRequest, trace_id: str = Depends(require_trace_id)) -> dict[str, Any]:
    started = time.perf_counter()
    logger.info("收到图片识别请求 trace=%s tenant=%s session=%s 张数=%d 随图说=%s",
                trace_id, body.tenant_code, body.session_id or "-", len(body.images),
                (body.question or "")[:40])
    if not body.images:
        raise HTTPException(status_code=400, detail="没有可识别的图片")
    try:
        result = await vision_service.recognize(
            images=body.images, question=body.question, tenant_code=body.tenant_code)
    except Exception as exc:  # noqa: BLE001
        logger.exception("图片识别失败 trace=%s：%s", trace_id, exc)
        raise HTTPException(status_code=500, detail=f"图片识别失败：{exc}") from exc
    logger.info("图片识别接口完成 trace=%s 可用=%s 模型=%s 判读=%s OCR=%d字 订单号=%s cost=%dms",
                trace_id, result.get("available"), result.get("model") or "-",
                (result.get("summary") or "（无）")[:50], len(result.get("ocr_text") or ""),
                result.get("order_no") or "（无）",
                int((time.perf_counter() - started) * 1000))
    return result


@router.get("/vision/health")
def vision_health(tenant_code: str | None = None) -> dict[str, Any]:
    """体检：当前会用哪个视觉模型、密钥读到没有。

    和文本模型一个口径：只报"有没有"，不回显密钥本身。
    """
    chosen, desc = vision_provider(tenant_code or "")
    return {
        "status": "UP" if chosen else "DEGRADED",
        "scene": "图片识别 / OCR / 截图判读",
        "visionKeyConfigured": chosen is not None,
        "visionModel": chosen["model"] if chosen else "",
        "visionSource": desc,
        "maxImages": get_settings().vision_max_images,
        "hint": "" if chosen else
                f"{desc}。配好 YUNTI_AI_QWEN_API_KEY 后即可识别图片；"
                "没配时机器人会如实告诉客户『暂时看不了图片』，不会编造识别结果。",
    }
```

### 改动：yunti-ai/ai/main.py

改动点：注册视觉路由：和 kb / rag / brain 并列

这个文件一共 2 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 14 行附近）**

原来是这样：

``` code-block-container
# 先初始化日志再导入 API：保证模块级组件（如 LLM 网关）的启动日志走统一格式
setup_logging()

from .api import bot, brain, chat, health, kb, qa, rag  # noqa: E402

logger = logging.getLogger(__name__)
```

改成：

``` code-block-container
# 先初始化日志再导入 API：保证模块级组件（如 LLM 网关）的启动日志走统一格式
setup_logging()

from .api import bot, brain, chat, health, kb, qa, rag, vision  # noqa: E402

logger = logging.getLogger(__name__)
```

**新增 2（第 87 行附近）**

原来是这样：

``` code-block-container
    app.include_router(kb.router, prefix=settings.api_prefix)
    app.include_router(rag.router, prefix=settings.api_prefix)
    app.include_router(brain.router, prefix=settings.api_prefix)
    return app

```

改成：

``` code-block-container
    app.include_router(kb.router, prefix=settings.api_prefix)
    app.include_router(rag.router, prefix=settings.api_prefix)
    app.include_router(brain.router, prefix=settings.api_prefix)
    app.include_router(vision.router, prefix=settings.api_prefix)
    return app

```

### 4.5 顺带修掉的两个坑

客户发图之后，"图里的内容到底进没进大脑"是联调最常问的一句话。所以把"这一轮收到的问题"整段打进日志（图片场景下它会是一段多行文本）：

### 改动：yunti-ai/ai/agents/brain.py

改动点：新增"客服大脑输入"日志，把真正收到的问题（含图片上下文）打出来

这个文件一共 2 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 77 行附近）**

原来是这样：

``` code-block-container
    steps: list[dict]


def _step(state: BrainState, node: str, detail: str, **extra: Any) -> list[dict]:
    steps = list(state.get("steps") or [])
    steps.append({"node": node, "detail": detail, **extra})
```

改成：

``` code-block-container
    steps: list[dict]


def _indent_question(question: str, limit: int = 400) -> str:
    """问题可能带图片上下文（多行），压成一行并截断，日志才不会被刷乱"""
    text = " / ".join(line.strip() for line in (question or "").splitlines() if line.strip())
    return text[:limit] + ("…" if len(text) > limit else "")


def _step(state: BrainState, node: str, detail: str, **extra: Any) -> list[dict]:
    steps = list(state.get("steps") or [])
    steps.append({"node": node, "detail": detail, **extra})
```

**新增 2（第 154 行附近）**

原来是这样：

``` code-block-container
    logger.info("客服大脑意图 tenant=%s session=%s 意图=%s 置信度=%.2f 来源=%s 槽位=%s",
                state["tenant_code"], state.get("session_no") or "-",
                (intent or {}).get("name") or "其他", confidence, source, slots)
    return {
        "setting": setting,
        "intents": intents,
```

改成：

``` code-block-container
    logger.info("客服大脑意图 tenant=%s session=%s 意图=%s 置信度=%.2f 来源=%s 槽位=%s",
                state["tenant_code"], state.get("session_no") or "-",
                (intent or {}).get("name") or "其他", confidence, source, slots)
    # 把这一轮真正收到的问题打出来：客户发图片时，这里会看到"[客户发来一张图片] 判读：… 图中文字：…"，
    # 是"图里的内容有没有进大脑"的直接证据
    logger.info("客服大脑输入 tenant=%s session=%s 内容=%s",
                state["tenant_code"], state.get("session_no") or "-",
                _indent_question(state["question"]))
    return {
        "setting": setting,
        "intents": intents,
```

另一个是第 22 篇留下的 Postgres 类型坑：意图命中统计里 `ROUND(%s, 2)` 在 psycopg 下会报`function round(double precision, int) does not exist`（Postgres 只有 `round(numeric, int)`），而异常被 `try` 兜住只留一行 WARN，现象是"命中次数一直不涨"——光看接口发现不了：

### 改动：yunti-ai/ai/services/brain.py

改动点：两个参数显式 `::numeric`，让整条表达式落在 numeric 上；顺带在验证脚本里加了一条"命中次数已累计"的断言

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 148 行附近）**

原来是这样：

``` code-block-container
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """
                UPDATE bot_intent
                   SET hit_count = hit_count + 1,
                       confidence = ROUND(((COALESCE(confidence, %s) * hit_count) + %s)
                                          / (hit_count + 1), 2),
                       update_time = CURRENT_TIMESTAMP
                 WHERE tenant_code = %s AND intent_code = %s AND is_deleted = FALSE
                """,
```

改成：

``` code-block-container
        with connect() as conn, conn.cursor() as cur:
            cur.execute(
                """
                -- 参数要显式 ::numeric：psycopg 把 Python 的 float 当 double precision 传，
                -- 而 PostgreSQL 只有 round(numeric, int)，没有 round(double precision, int)。
                -- 不转的话这里会报：function round(double precision, int) does not exist，
                -- 而且因为它被下面的 try 兜住，只留一行 WARN——"意图命中次数一直不涨"就是这么来的。
                UPDATE bot_intent
                   SET hit_count = hit_count + 1,
                       confidence = ROUND(
                           ((COALESCE(confidence, %s::numeric) * hit_count) + %s::numeric)
                           / (hit_count + 1), 2),
                       update_time = CURRENT_TIMESTAMP
                 WHERE tenant_code = %s AND intent_code = %s AND is_deleted = FALSE
                """,
```

## 五、customer-service：上传、识别、回填

### 5.1 聊天图片的上传与读取

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/service/SessionAttachmentService.java

本篇后端最核心的一个类，四件事：① 上传到 RustFS 并登记 `file_meta`（`biz_type=5`）；② 读取（访客与坐席共用）；③ **送模型前的压缩**：最大边 1280 + JPEG 质量 0.8，手机直出照片从 MB 级压到 100\~300KB；④ **多图并发预处理但顺序不变**：`invokeAll` 按提交顺序返回 Future，第 1 张永远排第 1（否则模型看到的图与客户的问法就对不上了）。

``` code-block-container
package cn.net.susan.customer.service;

import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.FileMeta;
import cn.net.susan.customer.mapper.FileMetaMapper;
import cn.net.susan.customer.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 会话图片附件：客户在聊天窗口里发的图片，存 RustFS 并登记 file_meta。
 *
 * <p>这里最值钱的不是"上传"，而是**送进模型之前的那步转码**（和第 23 篇参考的招聘系统同一套做法）：</p>
 *
 * <ol>
 *   <li>手机直出照片动辄 4~8 MB，直接 base64 塞进模型请求，body 会涨到十几 MB——又慢又容易被网关截断；</li>
 *   <li>所以先缩放（最大边 1280）、再按质量 0.8 转 JPEG，正常能压到 100~300 KB；</li>
 *   <li>模型只关心"字认不认得出来、图里是什么"，这个分辨率完全够用。</li>
 * </ol>
 *
 * <p>另外注意：**只有图片才走视觉识别**，PDF/Word 这类属于文档，该走知识库那条链路（第 20 篇）。</p>
 */
@Service
public class SessionAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(SessionAttachmentService.class);

    /** 聊天图片的对象键前缀 */
    private static final String PREFIX = "chat";
    /** file_meta.biz_type：5-聊天图片（约束里登记的取值，别随手写别的数字） */
    private static final int BIZ_TYPE_CHAT_IMAGE = 5;
    /** 送进模型前缩放到的最大边长（保持比例） */
    private static final int VISION_MAX_EDGE = 1280;
    /** JPEG 质量：0.8 是"肉眼几乎无损、体积砍到 1/10"的常用档 */
    private static final float JPEG_QUALITY = 0.8f;

    private final ObjectStorage objectStorage;
    private final FileMetaMapper fileMetaMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final int maxImageMb;

    public SessionAttachmentService(
            ObjectStorage objectStorage,
            FileMetaMapper fileMetaMapper,
            SnowflakeIdGenerator idGenerator,
    @Value("${yunti.chat.image-max-mb:8}") int maxImageMb
    ) {
        this.objectStorage = objectStorage;
        this.fileMetaMapper = fileMetaMapper;
        this.idGenerator = idGenerator;
        this.maxImageMb = maxImageMb <= 0 ? 8 : maxImageMb;
    }

    /**
     * 图片预处理线程池：一条消息最多 3 张（AI 侧 vision_max_images 也是 3），所以 3 个线程够用。
     *
     * <p>用固定池 + 守护线程：线程只干"下载 + 压缩"这种短活儿，业务一停就跟着结束，
     * 不会吊住 JVM；也不去占公共 ForkJoinPool（那里面是计算型任务，塞阻塞 IO 会拖累别人）。</p>
     */
    private final ExecutorService visionPool = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "chat-image-prep");
        thread.setDaemon(true);
        return thread;
    });

    /** 应用关闭时收掉线程池，别留悬挂线程 */
    @PreDestroy
    public void shutdownVisionPool() {
        visionPool.shutdown();
    }

    /**
     * 客户上传一张聊天图片。
     *
     * @param tenantCode 租户（由渠道密钥解析出来，不信任前端）
     * @param sessionNo  会话号（只用于拼对象键，便于按会话归档）
     * @return 上传结果（文件 ID、访问地址、文件名、大小）
     */
    @Transactional
    public UploadResult upload(String tenantCode, String sessionNo, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(40001, "请选择要发送的图片");
        }
        if (file.getSize() > (long) maxImageMb * 1024 * 1024) {
            throw new BizException(40001, "图片不能超过 " + maxImageMb + "MB");
        }
        String original = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
        String ext = extension(original);
        if (ext == null || !ext.matches("(?i)png|jpe?g|webp|gif|bmp")) {
            throw new BizException(40001, "聊天只支持图片（PNG / JPG / WEBP / GIF / BMP）；"
                    + "PDF、Word 这类文档请到「企业知识库」上传");
        }

        long fileId = idGenerator.nextId();
        String key = String.format("%s/%s/%s/%d_%s", PREFIX, tenantCode, safeSegment(sessionNo), fileId,
                safeSegment(original));
        String mimeType = mimeOf(ext);
        try (InputStream in = file.getInputStream()) {
            objectStorage.upload(key, in, file.getSize(), mimeType);
        } catch (Exception e) {
            log.error("聊天图片上传对象存储失败 tenant={} session={} error={}", tenantCode, sessionNo, e.getMessage());
            throw new BizException(50001, "图片上传失败：" + e.getMessage());
        }

        LocalDateTime now = LocalDateTime.now();
        FileMeta meta = FileMeta.builder()
                .id(fileId)
                .tenantCode(tenantCode)
                .fileNo("IMG" + fileId)
                .fileName(original)
                .objectKey(key)
                .fileSize(file.getSize())
                .mimeType(mimeType)
                // bizType：5-聊天图片（1-头像、2-附件、3-导出、4-发票见 file_meta 的约束与注释）
                .bizType(BIZ_TYPE_CHAT_IMAGE)
                .createTime(now)
                .updateTime(now)
                .creator("VISITOR")
                .deleted(false)
                .build();
        fileMetaMapper.insert(meta);
        log.info("聊天图片已上传 tenant={} session={} fileId={} name={} bytes={}",
                tenantCode, sessionNo, fileId, original, file.getSize());
        return new UploadResult(String.valueOf(fileId), accessUrl(fileId), original, file.getSize(), mimeType);
    }

    /**
     * 读取图片字节（给前端渲染用）。
     *
     * <p>聊天图片要同时给访客和坐席看：访客没有登录态，所以这个接口不带鉴权，
     * 靠**不可猜的雪花 ID** 当凭据。生产环境应换成带时效的签名 URL。</p>
     */
    public FileContent load(long fileId) {
        FileMeta meta = fileMetaMapper.selectOne(Wrappers.<FileMeta>lambdaQuery()
                .eq(FileMeta::getId, fileId)
                .eq(FileMeta::getDeleted, false)
                .last("LIMIT 1"));
        if (meta == null) {
            throw new BizException(40401, "图片不存在或已删除");
        }
        try (InputStream in = objectStorage.download(meta.getObjectKey())) {
            return new FileContent(meta.getFileName(), meta.getMimeType(), in.readAllBytes());
        } catch (Exception e) {
            throw new BizException(50001, "读取图片失败：" + e.getMessage());
        }
    }

    /**
     * 把图片转成"可以直接喂给视觉模型"的 data URL 列表。
     *
     * <p>这一步就是参考企业智能招聘系统的做法：**先把图压小、再 base64**，
     * 不然手机照片直传会让模型请求体膨胀到十几 MB。</p>
     *
     * <p><b>多张图并发处理，但顺序不变</b>：每张图都要"对象存储下载 → 解码 → 缩放 → 转 JPEG"，
     * 张与张之间毫无依赖，一张一张串着来就是白白排队（3 张图 ≈ 3 倍等待）。
     * 这里用 {@link ExecutorService#invokeAll} 并发跑——它**按提交顺序返回 Future**，
     * 所以图片清单的顺序仍然是客户选图的顺序，模型看到的第 1 张还是第 1 张。</p>
    */
    public List<String> toVisionImages(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        // 单张不用开线程：一张图的场景最频繁，别为它付线程池的调度开销
        if (fileIds.size() == 1) {
            return List.of(toDataUrl(fileIds.get(0)));
        }
        List<Callable<String>> tasks = new ArrayList<>(fileIds.size());
        for (Long fileId : fileIds) {
            tasks.add(() -> toDataUrl(fileId));
        }
        try {
            List<Future<String>> futures = visionPool.invokeAll(tasks);
            List<String> images = new ArrayList<>(futures.size());
            for (Future<String> future : futures) {
                // 顺序就是客户选图的顺序：第 1 张永远排第 1，模型看到的顺序不乱
                images.add(future.get());
            }
            return images;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("图片预处理被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("图片预处理失败：" + e.getCause().getMessage(), e);
        }
    }

    private String toDataUrl(long fileId) {
        FileContent content = load(fileId);
        byte[] payload = compress(content.payload(), fileId);
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(payload);
    }

    /** 缩放 + 转 JPEG；万一解不出来（少见格式）就原样返回，让模型自己去试。 */
    private byte[] compress(byte[] raw, long fileId) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
            if (image == null) {
                log.warn("图片无法解码，按原字节发送 fileId={} bytes={}", fileId, raw.length);
                return raw;
            }
            BufferedImage scaled = scaleDown(image, VISION_MAX_EDGE);
            byte[] jpeg = toJpeg(scaled);
            if (jpeg.length >= raw.length) {
                return raw;
            }
            log.info("图片已压缩 fileId={} {}KB → {}KB（{}×{}）",
                    fileId, raw.length / 1024, jpeg.length / 1024, scaled.getWidth(), scaled.getHeight());
            return jpeg;
        } catch (Exception e) {
            log.warn("图片压缩失败，按原字节发送 fileId={} error={}", fileId, e.getMessage());
            return raw;
        }
    }

    /** 按最大边等比缩放（只缩不放） */
    private BufferedImage scaleDown(BufferedImage source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longer = Math.max(width, height);
        if (longer <= maxEdge) {
            return source;
        }
        double ratio = (double) maxEdge / longer;
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return target;
    }

    /** 按指定质量写 JPEG（ImageIO 默认质量偏大，这里显式设 0.8） */
    private byte[] toJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("当前 JVM 没有 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** 聊天图片的访问地址：走 customer-service 自己的直链接口 */
    private String accessUrl(long fileId) {
        return "/api/customer/sessions/attachments/" + fileId;
    }

    private String extension(String fileName) {
        int index = fileName == null ? -1 : fileName.lastIndexOf('.');
        return index < 0 ? null : fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String mimeOf(String ext) {
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }

    /** 对象键里只允许安全字符，避免把路径分隔符带进去 */
    private String safeSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 上传结果 */
    public record UploadResult(String fileId, String url, String name, long size, String mimeType) {
    }

    /** 文件内容 */
    public record FileContent(String fileName, String mimeType, byte[] payload) {
    }
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/controller/SessionAttachmentController.java

两个接口：访客上传（用渠道密钥证明"这条会话来自我的渠道"，租户由密钥解析，不信前端传的参数）、读取图片字节（访客没有登录态，靠不可猜的雪花 ID 当凭据；生产环境应换成带时效的签名 URL）。

``` code-block-container
package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.customer.entity.Channel;
import cn.net.susan.customer.entity.ChannelKey;
import cn.net.susan.customer.mapper.ChannelKeyMapper;
import cn.net.susan.customer.mapper.ChannelMapper;
import cn.net.susan.customer.service.SessionAttachmentService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 聊天图片附件：客户在访客窗口发图。
 *
 * <p>两个接口，分工不同：</p>
 *
 * <ul>
 *   <li><b>上传</b>：访客没有登录态，所以用**渠道密钥**（appKey）证明"这条会话来自我的渠道"。
 *       租户由密钥解析出来，不信前端传的任何租户参数——这是多租户系统的底线；</li>
 *   <li><b>读取</b>：聊天图片要同时给访客和坐席看，所以不能要求登录态，
 *       靠不可猜的雪花 fileId 当凭据。生产环境应换成带时效的签名 URL（这里留了注释说明）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/customer/sessions/attachments")
public class SessionAttachmentController {

    private final SessionAttachmentService attachmentService;
    private final ChannelKeyMapper channelKeyMapper;
    private final ChannelMapper channelMapper;

    public SessionAttachmentController(
            SessionAttachmentService attachmentService,
            ChannelKeyMapper channelKeyMapper,
            ChannelMapper channelMapper
    ) {
        this.attachmentService = attachmentService;
        this.channelKeyMapper = channelKeyMapper;
        this.channelMapper = channelMapper;
    }

    /**
     * 访客上传一张聊天图片（聊天窗口里点「发送图片」）。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SessionAttachmentService.UploadResult> upload(
            @RequestParam("appKey") String appKey,
            @RequestParam("sessionNo") String sessionNo,
            @RequestPart("file") MultipartFile file
    ) {
        String tenant = tenantOf(appKey);
        return ApiResponse.ok(attachmentService.upload(tenant, sessionNo, file));
    }

    /**
     * 按文件 ID 读取图片（消息气泡里直接当 img src 用）。
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> load(@PathVariable long fileId) throws IOException {
        SessionAttachmentService.FileContent content = attachmentService.load(fileId);
        String encoded = URLEncoder.encode(content.fileName() == null ? "image" : content.fileName(),
                StandardCharsets.UTF_8).replace("+", "%20");
        MediaType mediaType = content.mimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(content.mimeType());
        return ResponseEntity.ok()
                // inline：让浏览器直接显示，而不是弹下载
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                // 图片按 fileId 不可变，缓存一天，消息列表反复渲染时不再回源
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .contentType(mediaType)
                .body(content.payload());
    }

    /** 渠道密钥 → 租户：和访客开会话用的是同一套校验 */
    private String tenantOf(String appKey) {
        ChannelKey channelKey = channelKeyMapper.selectOne(Wrappers.<ChannelKey>lambdaQuery()
                .eq(ChannelKey::getAppKey, appKey)
                .eq(ChannelKey::getStatus, 1)
                .eq(ChannelKey::getDeleted, false)
                .last("LIMIT 1"));
        if (channelKey == null) {
            throw new BizException(40401, "渠道密钥无效或已停用");
        }
        if (channelKey.getExpireTime() != null && channelKey.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BizException(40301, "渠道密钥已过期，请联系企业管理员重新生成");
        }
        Channel channel = channelMapper.selectById(channelKey.getChannelId());
        if (channel == null || Boolean.TRUE.equals(channel.getDeleted())) {
            throw new BizException(40401, "渠道不存在");
        }
        if (!Integer.valueOf(1).equals(channel.getStatus())) {
            throw new BizException(40301, "渠道已停用，暂时无法发送图片");
        }
        return channelKey.getTenantCode();
    }
}
```

### 5.2 视觉服务客户端

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/internal/VisionAiClient.java

调用 yunti-ai 的 `/api/ai/v1/agent/vision`。两个细节：超时给到 90 秒（比文本慢得多，要传图还要"看图说话"）；日志**只打结论不打 base64**，不然一张图就能把日志刷成几十 MB。

``` code-block-container
package cn.net.susan.customer.internal;

import cn.net.susan.common.exception.BizException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * yunti-ai「图片识别」客户端：把图片交给视觉大模型（千问 VL）做 OCR 与截图判读。
 *
 * <p>为什么图片要 Java 侧先压好再送过去：图片存在 RustFS，只有 customer-service 手里有对象存储客户端；
 * 而且压缩这步放在 Java 能保证 payload 可控（base64 会让体积涨约 1/3）。
 * 这和"企业智能招聘系统"里的做法一致——那边也是 Java 把 PDF/图片渲染成 JPEG 的 data URL，再交给模型识别。</p>
 */
@Component
public class VisionAiClient {

    private static final Logger log = LoggerFactory.getLogger(VisionAiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final boolean logPayload;
    private final int timeoutSeconds;

    public VisionAiClient(
            @Value("${yunti.ai.vision-base-url:http://127.0.0.1:9100}") String baseUrl,
            @Value("${yunti.ai.log-payload:true}") boolean logPayload,
            @Value("${yunti.ai.vision-timeout-seconds:90}") int timeoutSeconds
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "http://127.0.0.1:9100" : baseUrl.replaceAll("/+$", "");
        this.logPayload = logPayload;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 90 : timeoutSeconds;
    }

    /**
     * 识别一组图片。
     *
     * @param images data URL 列表（`data:image/jpeg;base64,...`）
     * @return 识别结果（available / summary / ocr_text / order_no / amount / error_text / need_human …）
     */
    public Map<String, Object> recognize(String tenantCode, String sessionNo,
                                         List<String> images, String question) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant_code", tenantCode);
        body.put("session_id", sessionNo == null ? "" : sessionNo);
        body.put("images", images);
        body.put("question", question == null ? "" : question);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/agent/vision"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("X-Tenant-Code", tenantCode)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (logPayload) {
                // 图片 base64 不能整段打进日志；这里按字段打，识别结论一眼可见
                Map<String, Object> parsed = objectMapper.readValue(response.body(), new TypeReference<>() {
                });
                log.info("图片识别响应 tenant={} session={} status={} 可用={} 模型={} 判读={} 订单号={} OCR={}字",
                        tenantCode, sessionNo, response.statusCode(), parsed.get("available"),
                        parsed.get("model"), parsed.get("summary"),
                        parsed.get("order_no"),
                        parsed.get("ocr_text") == null ? 0 : String.valueOf(parsed.get("ocr_text")).length());
                if (logPayload && parsed.get("ocr_text") != null) {
                    log.info("图片识别 OCR 原文 tenant={} session={}：{}",
                            tenantCode, sessionNo, safe(String.valueOf(parsed.get("ocr_text"))));
                }
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(50001, parseDetail(response.body()));
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {
            });
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "图片识别调用失败：" + friendly(e));
        }
    }

    private String parseDetail(String body) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {
            });
            Object detail = parsed.get("detail");
            if (detail != null) {
                return String.valueOf(detail);
            }
        } catch (Exception ignored) {
            // 解析不出来就退回原文
        }
        return safe(body);
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 400 ? value.substring(0, 400) + "..." : value;
    }

    private String friendly(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("Connection refused") || message.contains("I/O error")) {
            return "AI 服务（yunti-ai）没启动";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
```
