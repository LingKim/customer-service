---
title: "23 多模态客服：图片识别、OCR 与截图判读（千问 VL 接入）（二）"
source: "https://articles.zsxq.com/id_jlwhrzbvpdia.html"
author:
  - "[[苏三]]"
published:
created: 2026-09-25
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

### 5.3 识别编排

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/service/VisionService.java

把"读图 → 调模型 → 拼成大脑能吃的上下文"串起来，并做两件关键的事：① 预处理与模型**分开计时**，客户说"发图后好慢"时先看是卡在本地压图还是卡在模型；② `toBrainQuestion` 把 OCR 原文 + 判读 + 订单号/金额/报错原文 + "建议人工"拼成一段文字，让它等价于"客户把图里的内容打出来发了过来"——这样意图识别、知识库检索、引用溯源全都能照常工作。

``` code-block-container
package cn.net.susan.customer.service;

import cn.net.susan.customer.internal.VisionAiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 图片识别的业务编排：取图 → 压图 → 交给视觉模型 → 把结论交给客服大脑。
 *
 * <p>它自己不"看"图，只做三件事：</p>
 * <ol>
 *   <li>把图片从对象存储取出来、压成模型能吃的 JPEG data URL（{@link SessionAttachmentService}）；</li>
 *   <li>调 yunti-ai 的视觉接口；</li>
 *   <li>把"识别结论"整理成一句能塞进对话的问题上下文，交给客服大脑去做意图判断与回答。</li>
 * </ol>
 *
 * <p>降级原则和第 22 篇一致：**识别不了就如实说，不编**。
 * 没配视觉密钥时返回的 `available=false`，调用方据此给客户一句"暂时看不了图片，已为你转人工/请描述一下"。</p>
 */
@Service
public class VisionService {

    private static final Logger log = LoggerFactory.getLogger(VisionService.class);

    private final SessionAttachmentService attachmentService;
    private final VisionAiClient aiClient;

    public VisionService(SessionAttachmentService attachmentService, VisionAiClient aiClient) {
        this.attachmentService = attachmentService;
        this.aiClient = aiClient;
    }

    /**
     * 识别一组图片。
     *
     * @param question 客户随图说的一句话（可能为空）
     * @return 识别结果；AI 不可用时返回 {@link VisionResult#unavailable}，调用方走降级
     */
    public VisionResult recognize(String tenantCode, String sessionNo, List<Long> fileIds, String question) {
        List<String> images;
        // 两段时间分开计时：客户发完图"感觉慢"时，先看是卡在"下载 + 压缩"还是卡在模型，
        // 别一上来就怀疑模型——3 张图 2 秒里有一半可能是本地预处理
        long prepareStarted = System.currentTimeMillis();
        try {
            images = attachmentService.toVisionImages(fileIds);
        } catch (Exception e) {
            log.warn("读取聊天图片失败 tenant={} session={} error={}", tenantCode, sessionNo, e.getMessage());
            return VisionResult.unavailable("图片读取失败：" + e.getMessage());
        }
        long preparedCost = System.currentTimeMillis() - prepareStarted;
        if (images.isEmpty()) {
            return VisionResult.unavailable("没有可识别的图片");
        }
        try {
            // 把"客户随图说的那句话"也打出来：客户发的图和他说的话，是判断"答得对不对"的两个前提
            log.info("[图片识别] 开始识别 tenant={} session={} 张数={} 预处理={}ms（并发） 随图那句话={}",
                    tenantCode, sessionNo, images.size(),
                    preparedCost,
                    question == null || question.isBlank() ? "（客户没写字，只发了图）" : question);
            long modelStarted = System.currentTimeMillis();
            Map<String, Object> result = aiClient.recognize(tenantCode, sessionNo, images, question);
            long modelCost = System.currentTimeMillis() - modelStarted;
            VisionResult vision = VisionResult.from(result);
            log.info("[图片识别] 结果 tenant={} session={} 可用={} 模型={} 耗时={}ms（预处理 {}ms + 模型 {}ms）\n"
                            + "  判读：{}\n  订单号：{} 金额：{} 建议人工：{}\n  图中文字（{}字）：\n{}",
                    tenantCode, sessionNo, vision.available(), vision.model(),
                    preparedCost + modelCost, preparedCost, modelCost,
                    vision.summary().isEmpty() ? "（无）" : vision.summary(),
                    vision.orderNo().isEmpty() ? "（无）" : vision.orderNo(),
                    vision.amount().isEmpty() ? "（无）" : vision.amount(),
                    vision.needHuman(), vision.ocrText().length(), indent(vision.ocrText()));
            return vision;
        } catch (Exception e) {
            log.warn("图片识别失败 tenant={} session={} error={}", tenantCode, sessionNo, e.getMessage());
            return VisionResult.unavailable(e.getMessage());
        }
    }

    /**
     * 把识别结果整理成"喂给客服大脑"的上下文。
     *
     * <p>为什么要拼成这种格式：大脑那边是按"客户说了什么"来判断意图、检索知识的。
     * 图片本身没法直接参与检索，所以把**OCR 原文 + 判读结论 + 抽出来的订单号/金额**拼成一段文字，
     * 让它等价于"客户把图里的内容打出来发给我们"——这样意图识别、知识库检索、引用溯源全都能照常工作。</p>
     */
    public String toBrainQuestion(VisionResult result, String question) {
        StringBuilder builder = new StringBuilder();
        if (question != null && !question.isBlank()) {
            builder.append(question.trim());
        }
        builder.append("\n[客户发来一张图片]");
        if (!result.available()) {
            builder.append("（图片识别暂不可用：").append(result.hint()).append("）");
            return builder.toString();
        }
        if (!result.summary().isBlank()) {
            builder.append(" 判读：").append(result.summary());
        }
        if (!result.orderNo().isBlank()) {
            builder.append(" 订单号：").append(result.orderNo());
        }
        if (!result.amount().isBlank()) {
            builder.append(" 金额：").append(result.amount());
        }
        if (!result.errorText().isBlank()) {
            builder.append(" 报错原文：").append(result.errorText());
        }
        // 模型判了"这单该找人工"（退款失败 / 支付异常 / 投诉这类）就把这句话也带上：
        // 大脑转不转人工由它自己决定，但信号不能吞掉——截图是敏感问题时，客户那几行字往往看不出来
        if (result.needHuman()) {
            builder.append("\n（这张图涉及退款失败、支付异常或投诉这类敏感情形，建议人工介入）");
        }
        if (!result.ocrText().isBlank()) {
            builder.append("\n图中文字：").append(truncate(result.ocrText(), 600));
        }
        String questionText = builder.toString();
        // 把"真正喂给大脑的那段话"打进日志：客户发图后，机器人答得对不对，
        // 第一件事就是看它到底收到了什么（图里的文字有没有被带进去）
        log.info("[图片识别] 交给大脑的上下文（{}字）：\n{}",
                questionText.length(), indent(questionText));
        return questionText;
    }

    /** 多行文本缩进后打进日志（截断），方便和客户发的图逐行对照 */
    private String indent(String value) {
        if (value == null || value.isBlank()) {
            return "  （没识别到文字）";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return "  " + (text.length() <= 500 ? text : text.substring(0, 500) + "…");
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /**
     * 识别结果。
     *
     * @param available  是否真的识别了（false 表示没配密钥 / 调用失败，调用方要降级）
     * @param summary    一句话判读
     * @param ocrText    图里的文字（OCR 原文）
     * @param orderNo    抽出来的订单号
     * @param amount     抽出来的金额
     * @param errorText  报错截图里的报错原文
     * @param needHuman  模型判断"这单该找人工"（涉及退款失败、支付异常、投诉等）
     */
    public record VisionResult(
            boolean available,
            String summary,
            String ocrText,
            String orderNo,
            String amount,
            String errorText,
            boolean needHuman,
            String provider,
            String model,
            String hint
    ) {
        public static VisionResult unavailable(String hint) {
            return new VisionResult(false, "", "", "", "", "", false, "", "", hint);
        }

        public static VisionResult from(Map<String, Object> data) {
            return new VisionResult(
                    Boolean.TRUE.equals(data.get("available")),
                    str(data.get("summary")),
                    str(data.get("ocr_text")),
                    str(data.get("order_no")),
                    str(data.get("amount")),
                    str(data.get("error_text")),
                    Boolean.TRUE.equals(data.get("need_human")),
                    str(data.get("provider")),
                    str(data.get("model")),
                    str(data.get("hint")));
        }

        /** 图片消息里回填的识别信息（前端拿它展示"AI 判读"） */
        public java.util.Map<String, Object> toMessagePatch() {
            java.util.Map<String, Object> patch = new java.util.LinkedHashMap<>();
            patch.put("aiSummary", summary);
            patch.put("aiOcrText", ocrText);
            patch.put("aiOrderNo", orderNo);
            patch.put("aiAmount", amount);
        patch.put("aiErrorText", errorText);
        patch.put("aiNeedHuman", needHuman);
        patch.put("aiAvailable", available);
        patch.put("aiModel", model);
        return patch;
    }

        private static String str(Object value) {
            return value == null ? "" : String.valueOf(value);
        }
    }
}
```

### 5.4 消息链路：图片消息怎么触发识别、回填、推送

### 改动：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/service/SessionService.java

改动点：图片消息走独立的编排：识别 → 回填 → 推送 → 交给大脑；识别期间给客户亮"正在输入"；"多条图一条消息"的去重与解析；历史里不再把 JSON 喂给模型

这一段是"客户点了发送之后到底发生了什么"，每一步都有日志：识别开始/结束、回填后的消息推送、交给大脑的上下文。另外**识别 + 回答算一个回合**：从收到图片那一刻就亮"正在输入"，中间不熄，否则客户看到的就是"图发出去了没人理"（3 张图的识别要好几秒）。

这个文件一共 8 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 16 行附近）**

原来是这样：

``` code-block-container
import cn.net.susan.customer.mapper.SessionMessageMapper;
import cn.net.susan.customer.mapper.SessionEventMapper;
import cn.net.susan.customer.security.VisitorTokenService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

改成：

``` code-block-container
import cn.net.susan.customer.mapper.SessionMessageMapper;
import cn.net.susan.customer.mapper.SessionEventMapper;
import cn.net.susan.customer.security.VisitorTokenService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

**新增 2（第 33 行附近）**

原来是这样：

``` code-block-container
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
```

改成：

``` code-block-container
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
```

**新增 3（第 66 行附近）**

原来是这样：

``` code-block-container
    public static final int STATUS_AGENT = 3;
    public static final int STATUS_CLOSED = 4;

    /** 消息发送方码：1-客户、2-坐席、3-机器人、4-系统 */
    public static final int SENDER_CUSTOMER = 1;
    public static final int SENDER_AGENT = 2;
```

改成：

``` code-block-container
    public static final int STATUS_AGENT = 3;
    public static final int STATUS_CLOSED = 4;

    /** 消息类型码：2-图片（客户发的截图/照片，走视觉识别） */
    public static final int MSG_TYPE_IMAGE = 2;

    /** 消息发送方码：1-客户、2-坐席、3-机器人、4-系统 */
    public static final int SENDER_CUSTOMER = 1;
    public static final int SENDER_AGENT = 2;
```

**新增 4（第 116 行附近）**

原来是这样：

``` code-block-container
    /** AI 客服大脑：机器人接待一轮（意图 / 情绪 / 回复 / 是否转人工） */
    private final ObjectProvider<BotBrainService> botBrainProvider;

    /**
     * "依赖没装配上"这类问题的告警开关：一旦发生，每条消息都会命中同一条分支，
     * 按消息打日志会把日志刷爆；但完全不打又会让"功能悄悄不干活"。
```

改成：

``` code-block-container
    /** AI 客服大脑：机器人接待一轮（意图 / 情绪 / 回复 / 是否转人工） */
    private final ObjectProvider<BotBrainService> botBrainProvider;

    /** 图片识别：客户发来的图先看懂，再交给大脑回答 */
    private final ObjectProvider<VisionService> visionServiceProvider;

    /** 回填识别结论后要通知长连接，让双方不用刷新就能看到"AI 判读" */
    private final cn.net.susan.customer.internal.RealtimeNotifyClient notifyClient;

    /** 图片消息的正文是 JSON（fileId / url / name + 识别结论），要解析与回填 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * "依赖没装配上"这类问题的告警开关：一旦发生，每条消息都会命中同一条分支，
     * 按消息打日志会把日志刷爆；但完全不打又会让"功能悄悄不干活"。
```

**修改 5（第 156 行附近）**

原来是这样：

``` code-block-container
            ObjectProvider<RoutingService> routingProvider,
            ObjectProvider<RealtimeQaService> realtimeQaProvider,
            ObjectProvider<QaService> qaServiceProvider,
            ObjectProvider<BotBrainService> botBrainProvider
    ) {
        this.sessionMapper = sessionMapper;
        this.sessionMessageMapper = sessionMessageMapper;
```

改成：

``` code-block-container
            ObjectProvider<RoutingService> routingProvider,
            ObjectProvider<RealtimeQaService> realtimeQaProvider,
            ObjectProvider<QaService> qaServiceProvider,
            ObjectProvider<BotBrainService> botBrainProvider,
            ObjectProvider<VisionService> visionServiceProvider,
            cn.net.susan.customer.internal.RealtimeNotifyClient notifyClient
    ) {
        this.sessionMapper = sessionMapper;
        this.sessionMessageMapper = sessionMessageMapper;
```

**新增 6（第 172 行附近）**

原来是这样：

``` code-block-container
        this.realtimeQaProvider = realtimeQaProvider;
        this.qaServiceProvider = qaServiceProvider;
        this.botBrainProvider = botBrainProvider;
    }

    /** 是否开启机器人首轮接待（全局开关，租户级开关由 AI 侧的 bot_setting 决定）。 */
```

改成：

``` code-block-container
        this.realtimeQaProvider = realtimeQaProvider;
        this.qaServiceProvider = qaServiceProvider;
        this.botBrainProvider = botBrainProvider;
        this.visionServiceProvider = visionServiceProvider;
        this.notifyClient = notifyClient;
    }

    /** 是否开启机器人首轮接待（全局开关，租户级开关由 AI 侧的 bot_setting 决定）。 */
```

**新增 7（第 494 行附近）**

原来是这样：

``` code-block-container
        if (!Integer.valueOf(SENDER_CUSTOMER).equals(message.getSenderType())) {
            return;
        }
        // 下面两种跳过必须留日志：现象都是"客户发了消息、机器人一声不吭"，
        // 没有日志就只能靠猜——这两行是最容易被问到的"为什么不回话"。
        if (!botReceptionEnabled()) {
```

改成：

``` code-block-container
        if (!Integer.valueOf(SENDER_CUSTOMER).equals(message.getSenderType())) {
            return;
        }
        // 客户发的图片（msgType=2）：先让视觉模型看懂图，再把结论拼成上下文交给大脑。
        // 顺序不能反——大脑是按"客户说了什么"来判断意图、检索知识的，图里的内容必须先变成文字。
        if (Integer.valueOf(MSG_TYPE_IMAGE).equals(message.getMsgType())) {
            scheduleImageUnderstanding(tenantCode, session, message);
            return;
        }
        // 下面两种跳过必须留日志：现象都是"客户发了消息、机器人一声不吭"，
        // 没有日志就只能靠猜——这两行是最容易被问到的"为什么不回话"。
        if (!botReceptionEnabled()) {
```

**新增 8（第 544 行附近）**

原来是这样：

``` code-block-container
    }

    /**
     * 实时质检：挂在事务提交之后执行。
     *
     * <p>两个考虑：一是别让质检拖慢消息本身；二是坐席收到预警点进会话时，
```

改成：

``` code-block-container
    }

    /**
     * 客户发来图片：异步识别 → 把结论写回这条消息 → 再触发大脑回答。
     *
     * <p>三步都在异步线程里做，理由和机器人接待一样：识别要调视觉模型（秒级），
     * 不能拖住"图片消息发送成功"这条响应。</p>
     *
     * <p>写回消息这一步很关键：坐席在工作台看到的就不是一张"光秃秃的图"，
     * 而是**图片 + AI 判读 + OCR 原文**——客户发的是报错截图时，坐席一眼就知道问题在哪。</p>
     */
    private void scheduleImageUnderstanding(String tenantCode, Session session, SessionMessage message) {
        if (!botReceptionEnabled() || session.getAgentId() != null) {
            // 人工已经在接待：机器人不插话，但图片识别对坐席同样有用，所以照样识别、照样回填
            log.info("人工接待中，图片只做识别不回话 tenant={} sessionNo={}", tenantCode, session.getSessionNo());
        }
        String sessionNo = session.getSessionNo();
        Runnable task = () -> {
            VisionService vision = visionServiceProvider.getIfAvailable();
            if (vision == null) {
                return;
            }
            // 识别 + 回答算"一个回合"：从这一刻起就给客户亮"正在输入"。
            // 三张图要下载、压缩、调视觉模型，再交给大脑回答——中间只要熄一次，
            // 客户看到的就是"图发出去了，没人理"（这正是"响应好慢"的那种体感）。
            notifyClient.notifyTyping(tenantCode, sessionNo, "BOT", true);
            // 交给大脑之后由大脑收尾（它自己会亮一次、结束时熄灭），这里就不能再插手，
            // 否则两边一熄一亮，客户看到的输入提示会闪一下
            boolean handedOffToBrain = false;
            try {
                List<Long> fileIds = imageFileIds(message.getContent());
                if (fileIds.isEmpty()) {
                    log.warn("图片消息里没有解析出文件 ID tenant={} sessionNo={} content={}",
                            tenantCode, sessionNo, preview(message.getContent()));
                    return;
                }
                // 客户随图说的那句话：没写字就是空串，视觉模型只按图判断
                String caption = imageCaption(message.getContent());
                VisionService.VisionResult result = vision.recognize(
                        tenantCode, sessionNo, fileIds, caption);
                // ① 把识别结论写回图片消息（坐席与质检都读得到），并推一份"更新后的消息"出去
                MessageVO patched = patchImageMessage(tenantCode, message.getId(), result);
                if (patched != null) {
                    notifyClient.notifyMessage(tenantCode, sessionNo, toMessageMap(patched), true);
                }
                // ② 再把"图里的内容"当成客户说的话，交给大脑回答
                if (session.getAgentId() == null) {
                    BotBrainService brain = botBrainProvider.getIfAvailable();
                    if (brain != null) {
                        handedOffToBrain = true;
                        brain.handleCustomerMessageAsync(tenantCode, sessionNo,
                                vision.toBrainQuestion(result, caption));
                    }
                }
            } catch (Exception e) {
                log.warn("图片识别失败 tenant={} sessionNo={} error={}", tenantCode, sessionNo, e.getMessage());
            } finally {
                if (!handedOffToBrain) {
                    // 人工接待中 / 大脑不可用 / 识别失败：这一轮到此为止，提示得收掉
                    notifyClient.notifyTyping(tenantCode, sessionNo, "BOT", false);
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    /**
     * 从图片消息的正文里解析文件 ID。
     *
     * <p>图片消息的 content 是一段 JSON（前端发过来的）：`{"fileId":"...","url":"...","name":"..."}`。
     * 解析失败不抛异常，只记日志——一张图发失败不该影响整条会话。</p>
     */
    private List<Long> imageFileIds(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        // 用有序 Set 去重：前端发多条图的消息时，顶层 fileId 是第一张（兼容老消息），
        // fileIds 里又是完整清单——不去重的话第一张会被识别两遍
        Set<Long> ids = new LinkedHashSet<>();
        try {
            Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<>() {
            });
            Object single = parsed.get("fileId");
            if (single != null) {
                ids.add(Long.parseLong(String.valueOf(single)));
            }
            // 一条消息多张图（最多 3 张，前端限制）：清单在这里
            Object many = parsed.get("fileIds");
            if (many instanceof List<?> list) {
                for (Object item : list) {
                    ids.add(Long.parseLong(String.valueOf(item)));
                }
            }
        } catch (Exception e) {
            log.warn("解析图片消息正文失败：{}", e.getMessage());
        }
        return new ArrayList<>(ids);
    }

    /**
     * 从图片消息的正文里取"客户随图说的那句话"。
     *
     * <p>正文形如 `{"fileId":"...","url":"...","name":"...","text":"这个订单为什么一直失败"}`。
     * 客户可能就是不想写字（只发一张截图），所以取不到 `text` 时返回空串，不是错——
     * 视觉模型只按图判断，大脑那边也就只有图里的内容可用。</p>
     */
    String imageCaption(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<>() {
            });
            Object text = parsed.get("text");
            return text == null ? "" : String.valueOf(text).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 图片消息在**对话历史**里的文本（喂给客服大脑的那份）。
     *
     * <p>历史里的 `content` 是那段 JSON，直接丢给模型就是让它读 `{"fileId":"22..."}`——
     * 既浪费 token 又干扰判断。这里换成"客户说的那句话"，没写字就说明只发了图。</p>
     */
    String imageHistoryText(String content) {
        String caption = imageCaption(content);
        return caption.isEmpty() ? "[客户发来一张图片]" : caption;
    }

    /** 日志里的单行预览（换行压平 + 截断） */
    private String inline(String value) {
        if (value == null || value.isBlank()) {
            return "（没识别到文字）";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }

    /** 消息对象转成"跨服务传的普通 Map"（长连接只认 JSON，不认 record） */
    private Map<String, Object> toMessageMap(MessageVO message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("msgId", message.msgId());
        data.put("msgNo", message.msgNo());
        data.put("clientMsgNo", message.clientMsgNo());
        data.put("seq", message.seq());
        data.put("sessionId", message.sessionId());
        data.put("senderType", message.senderType());
        data.put("senderId", message.senderId());
        data.put("msgType", message.msgType());
        data.put("content", message.content());
        data.put("visibleTo", message.visibleTo());
        data.put("sendTime", message.sendTime());
        return data;
    }

    /**
     * 把识别结论并回图片消息的正文（保留原有的 fileId / url / name）。
     *
     * @return 更新后的消息；没更新成功返回 null（调用方据此决定要不要推送）
     */
    private MessageVO patchImageMessage(String tenantCode, Long messageId, VisionService.VisionResult result) {
        SessionMessage current = sessionMessageMapper.selectById(messageId);
        if (current == null) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    current.getContent() == null ? "{}" : current.getContent(),
                    new TypeReference<Map<String, Object>>() {
                    });
            payload.putAll(result.toMessagePatch());
            SessionMessage update = new SessionMessage();
            update.setId(messageId);
            update.setContent(objectMapper.writeValueAsString(payload));
            update.setUpdateTime(LocalDateTime.now());
            sessionMessageMapper.updateById(update);
            log.info("图片识别结论已回填 tenant={} messageId={}\n  判读：{}\n  订单号：{} 金额：{} 图中文字（{}字）：{}",
                    tenantCode, messageId,
                    result.summary().isEmpty() ? "（无）" : result.summary(),
                    result.orderNo().isEmpty() ? "（无）" : result.orderNo(),
                    result.amount().isEmpty() ? "（无）" : result.amount(),
                    result.ocrText().length(), inline(result.ocrText()));
            current.setContent(update.getContent());
            return toMessageVO(current);
        } catch (Exception e) {
            log.warn("回填图片识别结论失败 messageId={} error={}", messageId, e.getMessage());
            return null;
        }
    }

    /**
     * 实时质检：挂在事务提交之后执行。
     *
     * <p>两个考虑：一是别让质检拖慢消息本身；二是坐席收到预警点进会话时，
```

### 5.5 机器人基于图片作答

### 改动：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/service/BotBrainService.java

改动点：大脑接待支持"额外上下文"（图片识别结论）；对话历史里图片消息换成"客户说的那句话"；跳过本轮时也要收掉"正在输入"

两个改动都值得说：① 历史里的图片消息原本是一串 JSON，直接喂给模型等于让它读 `{"fileId":"22…"}`，既费 token 又干扰判断，现在换成"客户说的那句话"（没写字就是"\[客户发来一张图片\]"）；② 跳过的分支必须收掉输入提示——客户发图时提示是先亮起来的（识别要好几秒），大脑要是判断"这轮不归我管"就直接返回，提示会一直挂着。

这个文件一共 10 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 17 行附近）**

原来是这样：

``` code-block-container
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
```

改成：

``` code-block-container
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
```

**修改 2（第 145 行附近）**

原来是这样：

``` code-block-container
     * <p>由 {@code SessionService} 在消息事务提交后调用，所以这里读到的历史一定包含刚发的那句。</p>
     */
    public void handleCustomerMessageAsync(String tenantCode, String sessionNo) {
        if (!receptionEnabled()) {
            return;
        }
        executor.execute(() -> handleCustomerMessage(tenantCode, sessionNo));
    }

    /** 一轮接待的实际处理（同会话串行）。 */
    void handleCustomerMessage(String tenantCode, String sessionNo) {
        String lockKey = tenantCode + '#' + sessionNo;
        Object lock = sessionLocks.computeIfAbsent(lockKey, key -> new Object());
        try {
            synchronized (lock) {
                runOneTurn(tenantCode, sessionNo);
            }
        } catch (Exception e) {
            // 必须在这里兜住：这是线程池里的任务，异常跑出去只会打到 stderr，
```

改成：

``` code-block-container
     * <p>由 {@code SessionService} 在消息事务提交后调用，所以这里读到的历史一定包含刚发的那句。</p>
     */
    public void handleCustomerMessageAsync(String tenantCode, String sessionNo) {
        handleCustomerMessageAsync(tenantCode, sessionNo, null);
    }

    /**
     * 客户说了一句（或发了一张图）→ 机器人接一轮。
     *
     * @param extraContext 额外上下文：客户发图片时，图片识别的结论拼在这里。
     *                     大脑是按"客户说了什么"来判断意图、检索知识的，图片本身参与不了检索，
     *                     所以把 OCR 原文与判读结论拼成一段文字塞给它——效果等价于"客户把图里的内容打出来发了过来"。
     */
    public void handleCustomerMessageAsync(String tenantCode, String sessionNo, String extraContext) {
        if (!receptionEnabled()) {
            return;
        }
        if (extraContext != null && !extraContext.isBlank()) {
            log.info("[机器人接待] 本轮带图片上下文 tenant={} sessionNo={} 上下文={}字",
                    tenantCode, sessionNo, extraContext.length());
        }
        executor.execute(() -> handleCustomerMessage(tenantCode, sessionNo, extraContext));
    }

    /** 一轮接待的实际处理（同会话串行）。 */
    void handleCustomerMessage(String tenantCode, String sessionNo) {
        handleCustomerMessage(tenantCode, sessionNo, null);
    }

    void handleCustomerMessage(String tenantCode, String sessionNo, String extraContext) {
        String lockKey = tenantCode + '#' + sessionNo;
        Object lock = sessionLocks.computeIfAbsent(lockKey, key -> new Object());
        try {
            synchronized (lock) {
                runOneTurn(tenantCode, sessionNo, extraContext);
            }
        } catch (Exception e) {
            // 必须在这里兜住：这是线程池里的任务，异常跑出去只会打到 stderr，
```

**修改 3（第 189 行附近）**

原来是这样：

``` code-block-container
    }

    /** 一轮接待的主体：先确认该不该机器人管，再跑大脑。 */
    private void runOneTurn(String tenantCode, String sessionNo) {
        SessionService sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            log.warn("跳过机器人接待：SessionService 还没就绪 tenant={} sessionNo={}", tenantCode, sessionNo);
            return;
        }
        Session session;
```

改成：

``` code-block-container
    }

    /** 一轮接待的主体：先确认该不该机器人管，再跑大脑。 */
    private void runOneTurn(String tenantCode, String sessionNo, String extraContext) {
        SessionService sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            log.warn("跳过机器人接待：SessionService 还没就绪 tenant={} sessionNo={}", tenantCode, sessionNo);
            clearTyping(tenantCode, sessionNo);
            return;
        }
        Session session;
```

**新增 4（第 201 行附近）**

原来是这样：

``` code-block-container
            session = sessions.requireSession(tenantCode, sessionNo);
        } catch (BizException e) {
            log.warn("跳过机器人接待：{} tenant={} sessionNo={}", e.getMessage(), tenantCode, sessionNo);
            return;
        }
        String reason = skipReason(session);
```

改成：

``` code-block-container
            session = sessions.requireSession(tenantCode, sessionNo);
        } catch (BizException e) {
            log.warn("跳过机器人接待：{} tenant={} sessionNo={}", e.getMessage(), tenantCode, sessionNo);
            clearTyping(tenantCode, sessionNo);
            return;
        }
        String reason = skipReason(session);
```

**修改 5（第 209 行附近）**

原来是这样：

``` code-block-container
            // 说清楚"为什么不回"：绝大多数"机器人不回话"的疑问，答案就在这一行
            log.info("机器人不接这一轮：{} tenant={} sessionNo={} status={} agentId={}",
                    reason, tenantCode, sessionNo, session.getStatus(), session.getAgentId());
            return;
        }
        // 先亮"正在输入…"再去想：这一轮要检索 + 调模型，几秒钟里界面必须有反馈，
        // 否则客户会以为消息没发出去（这是"交互不友好"的根子）
        notifyClient.notifyTyping(tenantCode, sessionNo, "BOT", true);
        try {
            reply(tenantCode, session, sessions);
        } finally {
            // 无论成功、失败还是转人工，都要把提示收掉；消息本身也会让前端熄灭它
            notifyClient.notifyTyping(tenantCode, sessionNo, "BOT", false);
        }
    }

    /** 该不该由机器人接；不该接就返回原因（同时用于日志）。 */
    private String skipReason(Session session) {
        if (session.getAgentId() != null) {
```

改成：

``` code-block-container
            // 说清楚"为什么不回"：绝大多数"机器人不回话"的疑问，答案就在这一行
            log.info("机器人不接这一轮：{} tenant={} sessionNo={} status={} agentId={}",
                    reason, tenantCode, sessionNo, session.getStatus(), session.getAgentId());
            clearTyping(tenantCode, sessionNo);
            return;
        }
        // 先亮"正在输入…"再去想：这一轮要检索 + 调模型，几秒钟里界面必须有反馈，
        // 否则客户会以为消息没发出去（这是"交互不友好"的根子）
        notifyClient.notifyTyping(tenantCode, sessionNo, "BOT", true);
        try {
            reply(tenantCode, session, sessions, extraContext);
        } finally {
            // 无论成功、失败还是转人工，都要把提示收掉；消息本身也会让前端熄灭它
            notifyClient.notifyTyping(tenantCode, sessionNo, "BOT", false);
        }
    }

    /**
     * 收掉"正在输入"。
     *
     * <p>为什么跳过的分支也要收：客户发图片时，SessionService 会先亮起输入提示再把这一轮交给大脑
     * （图片识别要好几秒），如果大脑判断"这轮不归我管"就直接返回，提示会一直挂着，
     * 客户看到的就是"客服一直在输入、就是不说"。</p>
     */
    private void clearTyping(String tenantCode, String sessionNo) {
        notifyClient.notifyTyping(tenantCode, sessionNo, "BOT", false);
    }

    /** 该不该由机器人接；不该接就返回原因（同时用于日志）。 */
    private String skipReason(Session session) {
        if (session.getAgentId() != null) {
```

**修改 6（第 261 行附近）**

原来是这样：

``` code-block-container
    }

    /** 真正跑一轮大脑并把结果落到会话上。 */
    private void reply(String tenantCode, Session session, SessionService sessions) {
        String sessionNo = session.getSessionNo();
        List<Map<String, String>> messages = buildHistory(tenantCode, sessionNo, sessions);
        Map<String, Object> result;
        try {
            result = aiClient.think(tenantCode, sessionNo, messages, topK);
```

改成：

``` code-block-container
    }

    /** 真正跑一轮大脑并把结果落到会话上。 */
    private void reply(String tenantCode, Session session, SessionService sessions, String extraContext) {
        String sessionNo = session.getSessionNo();
        List<Map<String, String>> messages = buildHistory(tenantCode, sessionNo, sessions, extraContext);
        Map<String, Object> result;
        try {
            result = aiClient.think(tenantCode, sessionNo, messages, topK);
```

**修改 7（第 307 行附近）**

原来是这样：

``` code-block-container
                    tenantCode, sessionNo, SessionService.SENDER_BOT, null, msgType, content);
            notifyClient.notifyMessage(tenantCode, sessionNo, toMap(saved), true);
        }
        log.info("机器人接待完成 tenant={} sessionNo={} 意图={} 情绪={} 转人工={} 原因={}",
                tenantCode, sessionNo, intent, emotion, needHuman,
                result.get("transfer_reason"));
        if (needHuman) {
            // 交接话术已经由机器人那条回复说了，这里不再补一句一模一样的系统提示
            escalate(sessions, tenantCode, sessionNo,
```

改成：

``` code-block-container
                    tenantCode, sessionNo, SessionService.SENDER_BOT, null, msgType, content);
            notifyClient.notifyMessage(tenantCode, sessionNo, toMap(saved), true);
        }
        log.info("机器人接待完成 tenant={} sessionNo={} 意图={} 情绪={} 转人工={} 原因={} 问题={}",
                tenantCode, sessionNo, intent, emotion, needHuman,
                result.get("transfer_reason"), previewOf(extraContext));
        log.info("机器人回答 tenant={} sessionNo={} 编排={} 引用={}条 回答={}",
                tenantCode, sessionNo, result.get("engine"),
                ((List<?>) (result.get("citations") == null ? List.of() : result.get("citations"))).size(),
                previewOf(reply));
        // "图片里的内容到底用上了没有"——发图场景最常问的一句话，日志里直接给结论：
        // 回答里如果出现了图里的订单号/报错原文片段，就说明模型真的读进去了
        logImageGrounding(tenantCode, sessionNo, extraContext, reply);
        if (needHuman) {
            // 交接话术已经由机器人那条回复说了，这里不再补一句一模一样的系统提示
            escalate(sessions, tenantCode, sessionNo,
```

**新增 8（第 387 行附近）**

原来是这样：

``` code-block-container
        }
    }

    /** 再查一次会话状态：只有仍然没人接待、且没结束时，机器人才发这条回复。 */
    private boolean stillBotResponsible(String tenantCode, String sessionNo, SessionService sessions) {
        try {
```

改成：

``` code-block-container
        }
    }

    /** 日志里的内容预览：换行压平、超长截断 */
    private String previewOf(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }

    /**
     * 判断"回答有没有用上图片里的内容"。
     *
     * <p>这不是硬校验，只是给排障的人一个明确信号：客户发的是报错截图时，
     * 如果回答里出现了图里的订单号或报错原文，那基本可以确定模型读进去了；
     * 没有任何重合就只能说明"图里的信息没被用上"，需要去看识别结果或提示词。</p>
     */
    private void logImageGrounding(String tenantCode, String sessionNo, String extraContext, String reply) {
        if (extraContext == null || extraContext.isBlank() || reply == null || reply.isBlank()) {
            return;
        }
        List<String> keys = new ArrayList<>();
        Matcher digits = Pattern.compile("\\d{8,}").matcher(extraContext);
        while (digits.find()) {
            keys.add(digits.group());
        }
        Matcher quoted = Pattern.compile("[\\u4e00-\\u9fa5]{4,}").matcher(extraContext);
        while (quoted.find() && keys.size() < 12) {
            keys.add(quoted.group());
        }
        List<String> hit = keys.stream().distinct().filter(reply::contains).limit(3).toList();
        if (hit.isEmpty()) {
            log.warn("[图片识别] 回答里没有出现图片中的任何关键信息（订单号/原文片段），"
                            + "可能没真正用上：tenant={} sessionNo={}", tenantCode, sessionNo);
            return;
        }
        log.info("[图片识别] 回答用上了图片中的信息 tenant={} sessionNo={} 命中={}",
                tenantCode, sessionNo, hit);
    }

    /** 再查一次会话状态：只有仍然没人接待、且没结束时，机器人才发这条回复。 */
    private boolean stillBotResponsible(String tenantCode, String sessionNo, SessionService sessions) {
        try {
```

**修改 9（第 442 行附近）**

原来是这样：

``` code-block-container
     * （"正在为您转接人工客服"这种话喂给模型，只会干扰它对客户诉求的判断）。</p>
     */
    private List<Map<String, String>> buildHistory(String tenantCode, String sessionNo,
                                                   SessionService sessions) {
        List<SessionService.MessageVO> rows =
                sessions.historyByTenant(tenantCode, sessionNo, null, MAX_HISTORY, false);
        List<Map<String, String>> messages = new ArrayList<>(rows.size());
```

改成：

``` code-block-container
     * （"正在为您转接人工客服"这种话喂给模型，只会干扰它对客户诉求的判断）。</p>
     */
    private List<Map<String, String>> buildHistory(String tenantCode, String sessionNo,
                                                   SessionService sessions, String extraContext) {
        List<SessionService.MessageVO> rows =
                sessions.historyByTenant(tenantCode, sessionNo, null, MAX_HISTORY, false);
        List<Map<String, String>> messages = new ArrayList<>(rows.size());
```

**新增 10（第 455 行附近）**

原来是这样：

``` code-block-container
            if (content == null || content.isBlank()) {
                continue;
            }
            String role = sender == SessionService.SENDER_CUSTOMER ? "user" : "assistant";
            messages.add(BotAiClient.message(role, content));
        }
        return messages;
    }
```

改成：

``` code-block-container
            if (content == null || content.isBlank()) {
                continue;
            }
            // 图片消息的正文是 JSON（fileId + url + 客户随图说的那句话）：
            // 直接把 JSON 喂给模型，等于让它读 `{"fileId":"22..."}`——既费 token 又干扰判断。
            // 换成"客户说的那句话"更贴近真实对话，图里的内容由 extraContext 补在后面。
            if (Integer.valueOf(SessionService.MSG_TYPE_IMAGE).equals(row.msgType())) {
                content = sessions.imageHistoryText(content);
            }
            String role = sender == SessionService.SENDER_CUSTOMER ? "user" : "assistant";
            messages.add(BotAiClient.message(role, content));
        }
        // 图片识别结论挂在最后一条客户消息上：大脑拿到的还是"客户说的一句话"，
        // 只是这句话里现在包含了图里的内容——意图识别与知识检索都能正常工作
        if (extraContext != null && !extraContext.isBlank() && !messages.isEmpty()) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).get("role"))) {
                    messages.get(i).put("content",
                            messages.get(i).get("content") + "\n" + extraContext.trim());
                    break;
                }
            }
        }
        return messages;
    }
```

### 5.6 质检也要看得到图里的内容

### 改动：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/service/QaService.java

改动点：质检送检文本：图片消息取"判读 + 报错 + 图中文字"，而不是 JSON

质检要判的正是图里的内容（截图里那句违规话术），原样丢 JSON 进去模型读不懂。

这个文件一共 2 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 736 行附近）**

原来是这样：

``` code-block-container
            try {
                Map<String, Object> map = JSON.readValue(text, new TypeReference<Map<String, Object>>() {
                });
                Object value = map.containsKey("text") ? map.get("text") : map.get("content");
                if (value != null) {
                    return String.valueOf(value);
```

改成：

``` code-block-container
            try {
                Map<String, Object> map = JSON.readValue(text, new TypeReference<Map<String, Object>>() {
                });
                // 图片消息：送检的不是那串 JSON，而是"图片 + 识别出来的文字"——
                // 质检要判的正是图里的内容（比如截图里那句违规话术），原样丢 JSON 进去模型也读不懂
                if (map.containsKey("url") || map.containsKey("fileId")) {
                    StringBuilder image = new StringBuilder("[图片]");
                    appendIfPresent(image, map.get("aiSummary"), "判读：");
                    appendIfPresent(image, map.get("aiErrorText"), "报错：");
                    appendIfPresent(image, map.get("aiOcrText"), "图中文字：");
                    return image.toString();
                }
                Object value = map.containsKey("text") ? map.get("text") : map.get("content");
                if (value != null) {
                    return String.valueOf(value);
```

**新增 2（第 755 行附近）**

原来是这样：

``` code-block-container
        }
        return text;
    }
    /**
     * 规则列表。
     */
```

改成：

``` code-block-container
        }
        return text;
    }
    /** 拼"[标签] 值"（值为空就跳过），给图片消息的送检文本用 */
    private void appendIfPresent(StringBuilder builder, Object value, String label) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return;
        }
        builder.append(' ').append(label).append(text.length() > 300 ? text.substring(0, 300) + "…" : text);
    }

    /**
     * 规则列表。
     */
```

### 5.7 配置项

### 改动：yunti-backend/yunti-customer-service/src/main/resources/application.yml

改动点：视觉服务地址与超时、聊天图片大小上限

这个文件一共 2 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 42 行附近）**

原来是这样：

``` code-block-container
    kb-base-url: ${YUNTI_AI_KB_URL:http://127.0.0.1:9100}
    # AI 客服大脑（意图识别 / 情绪识别 / 多轮对话 / 转人工决策）也在 yunti-ai 里
    bot-base-url: ${YUNTI_AI_BOT_URL:http://127.0.0.1:9100}
    # 大脑一次要"识别 + 检索 + 生成"，比单次问答慢，超时给宽一点
    bot-timeout-seconds: ${YUNTI_AI_BOT_TIMEOUT:60}
    # 是否打印 AI 调用的完整入参/出参（排障用；生产环境可置 false 降低日志量）
```

改成：

``` code-block-container
    kb-base-url: ${YUNTI_AI_KB_URL:http://127.0.0.1:9100}
    # AI 客服大脑（意图识别 / 情绪识别 / 多轮对话 / 转人工决策）也在 yunti-ai 里
    bot-base-url: ${YUNTI_AI_BOT_URL:http://127.0.0.1:9100}
    # 图片识别（OCR / 截图判读，走千问 VL）也在 yunti-ai 里
    vision-base-url: ${YUNTI_AI_VISION_URL:http://127.0.0.1:9100}
    # 视觉识别比文本慢：要传图、要"看图说话"，超时给宽一点
    vision-timeout-seconds: ${YUNTI_AI_VISION_TIMEOUT:90}
    # 大脑一次要"识别 + 检索 + 生成"，比单次问答慢，超时给宽一点
    bot-timeout-seconds: ${YUNTI_AI_BOT_TIMEOUT:60}
    # 是否打印 AI 调用的完整入参/出参（排障用；生产环境可置 false 降低日志量）
```

**新增 2（第 57 行附近）**

原来是这样：

``` code-block-container
    reception-enabled: ${YUNTI_BOT_RECEPTION_ENABLED:true}
    # 问答时召回的知识切片条数
    top-k: ${YUNTI_BOT_TOP_K:5}

mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
```

改成：

``` code-block-container
    reception-enabled: ${YUNTI_BOT_RECEPTION_ENABLED:true}
    # 问答时召回的知识切片条数
    top-k: ${YUNTI_BOT_TOP_K:5}
  chat:
    # 客户发的聊天图片大小上限（MB）：图片会先压缩再送视觉模型，这里只挡"离谱的大文件"
    image-max-mb: ${YUNTI_CHAT_IMAGE_MAX_MB:8}

mybatis-plus:
  mapper-locations: classpath*:/mapper/**/*.xml
```

## 六、前端：发图、看图、判读

### 6.1 图片消息的类型与解析

### 改动：yunti-frontend/src/api/customer/session.ts

改动点：图片消息正文类型（含多图清单与 `text`）、`imagesOf` 统一取图、`messageTextOf` 复制/导出用文本、上传接口

`imagesOf()` 是这次加的关键工具：老消息只有顶层 `fileId/url`，新消息多图清单在 `images` 里，页面渲染、图片预览、复制都必须走它——直接读 `content.url` 的话，多图只会显示第一张。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 22 行附近）**

原来是这样：

``` code-block-container
  msgCount?: number | null
}

/** 消息：senderType 1-客户、2-坐席、3-机器人、4-系统 */
export interface SessionMessageItem {
  msgId: string
```

改成：

``` code-block-container
  msgCount?: number | null
}

/** 一条消息里的一张图（多张时挂在 `images` 里） */
export interface ChatImageItem {
  fileId?: string
  url?: string
  name?: string
  size?: number
}

/**
 * 图片消息的正文结构（msgType=2）。
 *
 * `fileId / url / name / size / fileIds / images / text` 是前端发消息时写进去的；
 * `ai*` 那几个是**后端识别完之后回填**的（所以一条图片消息会先到一次、识别完再更新一次）。
 *
 * 一张图时顶层 `fileId/url` 就够了（老消息长的就是这个样子）；**多张图（最多 3 张）**
 * 顶层仍然写第一张（老代码/老消息渲染都不至于瞎），完整清单在 `images` 与 `fileIds` 里。
 */
export interface ImageMessageContent extends ChatImageItem {
  fileIds?: string[]
  /** 这条消息里的全部图片（最多 3 张，顺序就是客户选图的顺序） */
  images?: ChatImageItem[]
  /**
   * 客户随图说的那句话（可以为空）。
   *
   * 大厂客服的图片消息都是"文字 + 图片"一条消息：客户先打字、再选图，点发送才一起发出去。
   * 这句话不是装饰——服务端会把它当作"客户的问题"交给视觉模型（识别时带上它更准）
   * 和客服大脑（意图识别、知识库检索都要用它）。
   */
  text?: string
  /** AI 一句话判读（"这是一张支付失败截图"） */
  aiSummary?: string
  /** 图里的文字（OCR 原文） */
  aiOcrText?: string
  aiOrderNo?: string
  aiAmount?: string
  aiErrorText?: string
  /** 模型建议人工介入（退款失败 / 支付异常 / 投诉这类敏感截图），坐席端会打标 */
  aiNeedHuman?: boolean
  aiAvailable?: boolean
  aiModel?: string
}

/** 解析图片消息正文；解析失败返回 null（老消息 / 脏数据都不会把页面搞崩） */
export function parseImageContent(content?: string | null): ImageMessageContent | null {
  if (!content) {
    return null
  }
  try {
    const parsed = JSON.parse(content) as ImageMessageContent
    return parsed && (parsed.url || parsed.fileId) ? parsed : null
  } catch {
    return null
  }
}

/**
 * 一条图片消息里的所有图片（最多 3 张）。
 *
 * 老消息只有顶层 `fileId/url` 一个字段，新消息多张时清单在 `images` 里——
 * 页面渲染、图片预览、复制都得走这里，别自己直接读 `content.url`（多图会只显示第一张）。
 */
export function imagesOf(message: { msgType?: number | null; content?: string | null }): ChatImageItem[] {
  const image = message.msgType === 2 ? parseImageContent(message.content) : null
  if (!image) {
    return []
  }
  const many = (image.images || []).filter((item) => item && (item.url || item.fileId))
  if (many.length) {
    return many
  }
  return image.url || image.fileId ? [image] : []
}

/**
 * 一条消息"给人看"的文本（复制、导出会话记录用）。
 *
 * 图片消息的正文是一段 JSON，直接复制出去就是 `{"fileId":"22...","url":"..."}`，
 * 粘到工单里没法看；所以图片消息取"随图说的那句话"，客户没写字就退回图片地址。
 */
export function messageTextOf(message: { msgType?: number | null; content?: string | null }): string {
  const image = message.msgType === 2 ? parseImageContent(message.content) : null
  if (!image) {
    return message.content || ''
  }
  const urls = imagesOf(message).map((item) => item.url).filter(Boolean)
  return (image.text || '').trim() || urls.join(' ') || image.url || image.name || '[图片]'
}

/** 聊天图片上传结果 */
export interface ChatImageUploadResult {
  fileId: string
  url: string
  name: string
  size: number
  mimeType?: string
}

/**
 * 访客上传聊天图片。
 *
 * 访客没有登录态，所以用**渠道密钥**（appKey）证明这条会话来自哪个渠道——租户由服务端从密钥解析，
 * 前端传什么都不影响租户归属。
 */
export function uploadChatImage(data: {
  appKey: string
  sessionNo: string
  file: File
}): Promise<ChatImageUploadResult> {
  const form = new FormData()
  form.append('file', data.file)
  return request<ChatImageUploadResult>({
    url: '/customer/sessions/attachments',
    method: 'post',
    params: { appKey: data.appKey, sessionNo: data.sessionNo },
    data: form,
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 60000,
  })
}

/** 消息：senderType 1-客户、2-坐席、3-机器人、4-系统 */
export interface SessionMessageItem {
  msgId: string
```

### 6.2 访客窗口：先暂存、再一起发

### 改动：yunti-frontend/src/views/visitor/index.vue

改动点：选图（多选、最多 3 张）→ 并行上传 → 暂存成附件卡（逐张可删）→ 点发送才把"文字 + 3 张图"合成一条消息；图片消息渲染（文字在上、多图等大并排）；点图在**本窗口**弹层看大图（不跳新标签页）

这里有两个刻意的产品决定：① **不替客户做"选完图立刻发"的决定**——客户想说的是"这个订单为什么一直失败"配一张截图，图先发出去、文字还在输入框里，服务端会先按"只有图"接一轮、文字到了再答一次，两轮都对不上上下文；② 图片消息**不走蓝色气泡**（改成白底媒体卡片）——客户的文字气泡是蓝底白字，随图那句话要是落在蓝底上，就变成"深色背景 + 深色字"，读起来发闷。

这个文件一共 15 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 48 行附近）**

原来是这样：

``` code-block-container
              <div class="v-main">
                <div v-if="msg.senderType !== 1 && senderText(msg)" class="v-meta">{{ senderText(msg) }}</div>
                <div class="v-bubble" :class="{ 'is-pending': msg.pending, 'is-failed': msg.failed }">
                  <!-- 卡片消息：正文 + 可点动作（比如"转人工客服"）。点了才转，不替客户做主 -->
                  <template v-if="cardOf(msg)">
                    <div class="v-content">{{ cardOf(msg)?.text }}</div>
                    <div v-if="cardOf(msg)?.actions?.length" class="v-actions">
                      <el-button
```

改成：

``` code-block-container
              <div class="v-main">
                <div v-if="msg.senderType !== 1 && senderText(msg)" class="v-meta">{{ senderText(msg) }}</div>
                <div
                  class="v-bubble"
                  :class="{ 'is-pending': msg.pending, 'is-failed': msg.failed, 'is-image': !!imageOf(msg) }"
                >
                  <!-- 图片消息：先随图说的那句话，再一排缩略图（最多 3 张），点开在本窗口弹层里看大图 -->
                  <template v-if="imagesOf(msg).length">
                    <div v-if="imageOf(msg)?.text" class="v-image-text">{{ imageOf(msg)?.text }}</div>
                    <div class="v-images" :class="{ 'is-multi': imagesOf(msg).length > 1 }">
                      <button
                        v-for="(image, index) in imagesOf(msg)"
                        :key="image.fileId || image.url || index"
                        type="button"
                        class="v-image"
                        :title="image.name || '查看大图'"
                        @click="openImage(image)"
                      >
                        <img :src="image.url" :alt="image.name || '图片'" loading="lazy" />
                      </button>
                    </div>
                  </template>
                  <!-- 卡片消息：正文 + 可点动作（比如"转人工客服"）。点了才转，不替客户做主 -->
                  <template v-else-if="cardOf(msg)">
                    <div class="v-content">{{ cardOf(msg)?.text }}</div>
                    <div v-if="cardOf(msg)?.actions?.length" class="v-actions">
                      <el-button
```

**新增 2（第 128 行附近）**

原来是这样：

``` code-block-container
        <!-- 输入区 -->
        <div class="v-input">
          <el-input
            v-model="draft"
            type="textarea"
```

改成：

``` code-block-container
        <!-- 输入区 -->
        <div class="v-input">
          <!-- 待发送的图片：先选图（这时就上传好），文字和图片一起点"发送"才发出去，最多 3 张 -->
          <div v-if="pendingImages.length" class="vi-attach">
            <div v-for="item in pendingImages" :key="item.previewUrl" class="vi-attach-item">
              <div class="vi-attach-thumb">
                <img :src="item.previewUrl" :alt="item.name" />
                <div v-if="!item.uploaded" class="vi-attach-mask">
                  <el-icon class="is-loading" :size="15"><Loading /></el-icon>
                </div>
                <button
                  class="vi-attach-remove"
                  type="button"
                  :title="item.uploaded ? '移除这张图片' : '取消这张图片'"
                  @click="removePendingImage(item)"
                >
                  <el-icon :size="11"><Close /></el-icon>
                </button>
              </div>
              <div class="vi-attach-name">{{ item.name }}</div>
            </div>
            <div class="vi-attach-hint">
              {{ uploading ? '图片上传中…' : attachHint }}
            </div>
          </div>
          <el-input
            v-model="draft"
            type="textarea"
```

**修改 3（第 162 行附近）**

原来是这样：

``` code-block-container
            @keydown.enter.exact.prevent="send"
          />
          <div class="vi-actions">
            <span class="vi-tip">{{ connectionTip }}</span>
            <el-button v-if="sessionClosed" @click="restart">重新发起咨询</el-button>
            <el-button
              v-else
              type="primary"
              :loading="sending"
              :disabled="!sessionOpened"
              @click="send"
            >
              发送
```

改成：

``` code-block-container
            @keydown.enter.exact.prevent="send"
          />
          <div class="vi-actions">
            <div class="vi-left">
              <input
                ref="fileRef"
                class="vi-file"
                type="file"
                multiple
                accept="image/png,image/jpeg,image/webp,image/gif,image/bmp"
                @change="onPickImage"
              />
              <el-button
                size="small"
                :disabled="sessionClosed || !sessionOpened || !canPickMore"
                :title="canPickMore
                  ? `发送截图或照片（一条消息最多 ${MAX_IMAGES_PER_MESSAGE} 张）`
                  : `一条消息最多 ${MAX_IMAGES_PER_MESSAGE} 张图片，先发出去或移除一张`"
                @click="pickImage"
              >
                <el-icon :size="14"><Picture /></el-icon>
                <span class="vi-btn-text">图片</span>
              </el-button>
            </div>
            <span class="vi-tip">{{ connectionTip }}</span>
            <el-button v-if="sessionClosed" @click="restart">重新发起咨询</el-button>
            <el-button
              v-else
              type="primary"
              :loading="sending"
              :disabled="!sessionOpened || uploading"
              @click="send"
            >
              发送
```

**修改 4（第 198 行附近）**

原来是这样：

``` code-block-container
        </div>
      </template>
    </div>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CopyDocument, Loading, Service, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { listSessionMessages, openVisitorSession, type SessionMessageItem } from '../../api/customer/session'
import { RealtimeClient, type RealtimeMessage, type RealtimeState } from '../../utils/realtime'
import { copyText } from '../../utils/clipboard'
import { useChatScroll } from '../../utils/chatScroll'
```

改成：

``` code-block-container
        </div>
      </template>
    </div>
    <!-- 图片预览：就在这个窗口里弹一层，右上角有关闭（也支持点遮罩、按 Esc 关） -->
    <el-image-viewer
      v-if="previewOpen"
      :url-list="previewUrls"
      :initial-index="previewIndex"
      :hide-on-click-modal="true"
      teleported
      @close="previewOpen = false"
    />
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Close, CopyDocument, Loading, Picture, Service, WarningFilled } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import {
  imagesOf,
  listSessionMessages,
  messageTextOf,
  openVisitorSession,
  parseImageContent,
  uploadChatImage,
  type ChatImageItem,
  type ChatImageUploadResult,
  type SessionMessageItem,
} from '../../api/customer/session'
import { RealtimeClient, type RealtimeMessage, type RealtimeState } from '../../utils/realtime'
import { copyText } from '../../utils/clipboard'
import { useChatScroll } from '../../utils/chatScroll'
```

**修改 5（第 326 行附近）**

原来是这样：

``` code-block-container
onMounted(openSession)

onUnmounted(() => client?.close())

async function openSession() {
  // appId 是正式参数名；key 是早期版本留下的别名，继续兼容老地址
```

改成：

``` code-block-container
onMounted(openSession)

onUnmounted(() => {
  client?.close()
  // 待发送图片的本地预览地址要一起释放（objectURL 不释放会一直占着内存）
  clearPendingImages()
})

async function openSession() {
  // appId 是正式参数名；key 是早期版本留下的别名，继续兼容老地址
```

**新增 6（第 462 行附近）**

原来是这样：

``` code-block-container
      applySession(message.data)
      if (!wasClosed && sessionClosed.value) {
        ElMessage.info('本次咨询已结束，如需继续可点右下角重新发起')
      }
      break
    }
```

改成：

``` code-block-container
      applySession(message.data)
      if (!wasClosed && sessionClosed.value) {
        ElMessage.info('本次咨询已结束，如需继续可点右下角重新发起')
        // 会话关了就发不出去了：清掉待发送区，别让客户对着点不动的附件发呆
        clearPendingImages()
      }
      break
    }
```

**修改 7（第 486 行附近）**

原来是这样：

``` code-block-container
  }
}

function send() {
  const content = draft.value.trim()
  if (!content) {
    return
  }
  draft.value = ''
  void sendText(content)
}

/** 真正发一条消息（卡片上的"转人工客服"也走这里，保证行为完全一致） */
async function sendText(text: string) {
  const content = text.trim()
  if (!content || sessionClosed.value) {
    return
  }
  if (!client || !sessionOpened.value) {
    ElMessage.warning('正在接入客服，稍等一下再发送')
    return
  }
  // 消息号由页面生成并挂到气泡上：长连接那边不管连没连上都先收进发件箱，
```

改成：

``` code-block-container
  }
}

/**
 * 点"发送"（也走 Enter）。
 *
 * <p>大厂客服的图片消息是**一条消息**：客户输入的文字 + 待发送的图片，点发送才一起出去。
 * 所以这里先看有没有待发送的图片——有就走图片消息（文字挂在 `text` 字段上），
 * 没有才是纯文本。不替客户做"选完图立刻发出去"的决定。</p>
 */
function send() {
  const content = draft.value.trim()
  const images = pendingImages.value
  if (images.some((item) => !item.uploaded)) {
    ElMessage.info('图片还在上传，稍等一下再发送')
    return
  }
  if (!images.length && !content) {
    return
  }
  if (!readyClient()) {
    return
  }
  if (!images.length) {
    draft.value = ''
    void sendText(content)
    return
  }
  // 正文结构和后端约定的一致：第一张放在顶层（老消息/老渲染都认），
  // 完整清单在 `images` 与 `fileIds` 里；`text` 就是客户打的字（可以是空的）
  const uploaded = images.map((item) => item.uploaded!)
  const payload = {
    fileId: uploaded[0].fileId,
    url: uploaded[0].url,
    name: uploaded[0].name,
    size: uploaded[0].size,
    fileIds: uploaded.map((item) => item.fileId),
    images: uploaded.map((item) => ({
      fileId: item.fileId,
      url: item.url,
      name: item.name,
      size: item.size,
    })),
    ...(content ? { text: content } : {}),
  }
  draft.value = ''
  clearPendingImages()
  void sendPayload(JSON.stringify(payload), 2)
}

/**
 * 现在能不能发消息：不能发就提示一句并返回 null，能发就把长连接客户端给你。
 *
 * <p>为什么要在"发送前"判一次：连上之前发不出去，而待发送的图片已经上传到对象存储了，
 * 得留着让客户连上后再点一次，不能悄悄丢掉。</p>
 */
function readyClient(): RealtimeClient | null {
  if (sessionClosed.value) {
    return null
  }
  if (!client || !sessionOpened.value) {
    ElMessage.warning('正在接入客服，稍等一下再发送')
    return null
  }
  return client
}

/** 真正发一条消息（卡片上的"转人工客服"也走这里，保证行为完全一致） */
async function sendText(text: string) {
  const content = text.trim()
  if (!content) {
    return
  }
  await sendPayload(content, 1)
}

/**
 * 真正发一条消息（文本、图片都走这里，保证行为完全一致）。
 *
 * @param msgType 1-文本、2-图片（图片的 content 是 `{fileId,url,name,size,text}` 的 JSON，
 *                `text` 就是客户随图打的那句话——服务端拿它当"客户的问题"用）
 */
async function sendPayload(content: string, msgType: number) {
  const rt = readyClient()
  if (!rt) {
    return
  }
  // 消息号由页面生成并挂到气泡上：长连接那边不管连没连上都先收进发件箱，
```

**修改 8（第 579 行附近）**

原来是这样：

``` code-block-container
    msgNo: `local-${clientMsgNo}`,
    clientMsgNo,
    senderType: 1,
    msgType: 1,
    content,
    sendTime: new Date().toISOString(),
    pending: true,
```

改成：

``` code-block-container
    msgNo: `local-${clientMsgNo}`,
    clientMsgNo,
    senderType: 1,
    msgType,
    content,
    sendTime: new Date().toISOString(),
    pending: true,
```

**修改 9（第 588 行附近）**

原来是这样：

``` code-block-container
  scrollToBottom(true)
  sending.value = true
  try {
    const ack = await client.send({
      type: 'SEND',
      sessionNo: sessionNo.value,
      msgType: 1,
      content,
      clientMsgNo,
    })
```

改成：

``` code-block-container
  scrollToBottom(true)
  sending.value = true
  try {
    const ack = await rt.send({
      type: 'SEND',
      sessionNo: sessionNo.value,
      msgType,
      content,
      clientMsgNo,
    })
```

**新增 10（第 608 行附近）**

原来是这样：

``` code-block-container
  }
}

/**
 * 复制一条消息。
 *
```

改成：

``` code-block-container
  }
}

/** 图片消息解析（msgType=2 的正文是一段 JSON） */
function imageOf(message: SessionMessageItem) {
  return message.msgType === 2 ? parseImageContent(message.content) : null
}

/**
 * 图片预览：点缩略图在**本窗口**的弹层里看原图。
 *
 * <p>以前是 `<a target="_blank">`，会在浏览器里多开一个标签页——小程序/嵌入式窗口里
 * 那是直接跳走，客户看完还得自己找回客服窗口。改成弹层后：右上角有关闭、
 * 点遮罩或按 Esc 也能关；会话里所有图排成一条链，弹层里能左右切换（左下角有 1/6 这样的计数），
 * 点第几张就从第几张开始看。</p>
 */
const previewOpen = ref(false)
const previewIndex = ref(0)
const previewUrls = computed(() =>
  messages.value
    .flatMap((item) => imagesOf(item).map((image) => image.url))
    .filter((url): url is string => !!url),
)

function openImage(image: ChatImageItem) {
  const url = image?.url
  if (!url) {
    return
  }
  const index = previewUrls.value.indexOf(url)
  previewIndex.value = index >= 0 ? index : 0
  previewOpen.value = true
}

const fileRef = ref<HTMLInputElement>()

/** 一条消息最多带几张图（和后端、AI 侧的 vision_max_images 对齐，别各写各的） */
const MAX_IMAGES_PER_MESSAGE = 3
/** 单张图片大小上限（后端也有一道，前端先拦一下省得白传） */
const MAX_IMAGE_BYTES = 8 * 1024 * 1024

/** 待发送的图片：选了图就先挂在这里，点"发送"才和文字一起变成一条消息 */
interface PendingImage {
  name: string
  size: number
  /** 本地预览地址（objectURL）：上传中也能立刻看到缩略图，不用等对象存储 */
  previewUrl: string
  /** 上传完成后才有（fileId + 对象存储地址）；为 null 表示还在传 */
  uploaded: ChatImageUploadResult | null
}

const pendingImages = ref<PendingImage[]>([])
/** 还有图片在上传：这期间不让发送，否则发出去的会是一条缺图的残缺消息 */
const uploading = computed(() => pendingImages.value.some((item) => !item.uploaded))
/** 还能不能再选图（一条消息最多 3 张） */
const canPickMore = computed(() => pendingImages.value.length < MAX_IMAGES_PER_MESSAGE)
const attachHint = computed(() => {
  const left = MAX_IMAGES_PER_MESSAGE - pendingImages.value.length
  return left > 0
    ? `已就绪，点“发送”随文字一起发出（还可再选 ${left} 张）`
    : '已到上限（一条消息最多 3 张），点「发送」发出去'
})

function pickImage() {
  if (sessionClosed.value || !sessionOpened.value || !canPickMore.value) {
    return
  }
  fileRef.value?.click()
}

/** 从待发送区移除这张图：本地预览地址一起释放，别让 objectURL 一直占着内存 */
function removePendingImage(item: PendingImage) {
  const index = pendingImages.value.indexOf(item)
  if (index < 0) {
    return
  }
  URL.revokeObjectURL(item.previewUrl)
  pendingImages.value.splice(index, 1)
}

/** 清空待发送区（发送成功、会话结束、离开页面时都要走这里，否则 objectURL 会漏） */
function clearPendingImages() {
  pendingImages.value.slice().forEach(removePendingImage)
}

/**
 * 选了图（可一次选多张，最多 3 张）：**先上传到对象存储**，然后挂在输入框上方，等客户点"发送"。
 *
 * <p>为什么要先传后发：长连接只传小文本帧，图片字节走 HTTP 上传到对象存储，消息里只带
 * `fileId + url`。大图上传要好几秒，等点"发送"那一刻才开始传，按钮只能一直转圈；
 * 先传完再发，客户点下去就是"嗖"地出去。断线重发、历史记录也都只是几百字节的 JSON。</p>
 *
 * <p>为什么不再"选完图立刻发一条图片消息"：那是替客户做了决定。客户想说的大多是
 * "这个订单为什么一直失败"配一张截图——图先发出去、文字还在输入框里，服务端会先按
 * "只有图"接一轮（识别 + 回答），等文字到了再答一次，两轮都对不上上下文。
 * 改成"文字 + 图片一条消息"，视觉识别和客服大脑拿到的才是完整的那句话。</p>
 *
 * <p>多张图怎么发：一次上传接口只收一个文件，所以这里**并行**传（每张各自转圈、各自可移除），
 * 全部传完点发送时合成**一条**消息——顶层是第一张（兼容老消息），`images` 里是完整清单。
 * 视觉模型一次最多看 3 张（AI 侧 vision_max_images），所以上限卡在 3，多了就提示先发出去。</p>
 */
async function onPickImage(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) {
    return
  }
  const appKey = String(route.query.appId ?? route.query.key ?? '').trim()
  if (!appKey) {
    ElMessage.warning('缺少渠道密钥，无法发送图片')
    return
  }
  const room = MAX_IMAGES_PER_MESSAGE - pendingImages.value.length
  if (room <= 0) {
    ElMessage.warning(`一条消息最多 ${MAX_IMAGES_PER_MESSAGE} 张图片，先发出去再选`)
    return
  }
  const picked = files.slice(0, room)
  if (files.length > room) {
    ElMessage.warning(`一条消息最多 ${MAX_IMAGES_PER_MESSAGE} 张图片，这次先放前 ${room} 张`)
  }
  // 并行上传：三张 200KB 的图比"一张一张来"快得多，而且每张都能单独移除
  await Promise.all(picked.map((file) => stageImage(file, appKey)))
}

/** 把一张图挂进待发送区并开始上传（上传完原地补上 fileId / 地址，失败就自己撤掉） */
async function stageImage(file: File, appKey: string) {
  if (file.size > MAX_IMAGE_BYTES) {
    ElMessage.warning(`「${file.name}」超过 8MB，先压缩一下再发`)
    return
  }
  const previewUrl = URL.createObjectURL(file)
  const item: PendingImage = { name: file.name, size: file.size, previewUrl, uploaded: null }
  pendingImages.value.push(item)
  try {
    const uploaded: ChatImageUploadResult = await uploadChatImage({
      appKey,
      sessionNo: sessionNo.value,
      file,
    })
    // 上传是异步的：这期间客户可能已经把这张移除了，认"还在不在列表里"，别认变量
    if (pendingImages.value.includes(item)) {
      item.uploaded = uploaded
    }
  } catch (e) {
    removePendingImage(item)
    const reason = e instanceof Error ? e.message : '请重试'
    ElMessage.error(`「${file.name}」上传失败：${reason}`)
  }
}

/**
 * 复制一条消息。
 *
```

**修改 11（第 764 行附近）**

原来是这样：

``` code-block-container
 * 或者把聊天记录发给同事；允许选中复制是底线，再补一个悬停即用的复制按钮。</p>
 */
async function copyMessage(message: SessionMessageItem) {
  const ok = await copyText(message.content || '')
  if (ok) {
    ElMessage.success('已复制')
  } else {
```

改成：

``` code-block-container
 * 或者把聊天记录发给同事；允许选中复制是底线，再补一个悬停即用的复制按钮。</p>
 */
async function copyMessage(message: SessionMessageItem) {
  // 图片消息的正文是 JSON，复制 JSON 给客户没用——取"随图说的那句话"
  const ok = await copyText(messageTextOf(message))
  if (ok) {
    ElMessage.success('已复制')
  } else {
```

**修改 12（第 903 行附近）**

原来是这样：

``` code-block-container
  if (!message) {
    return
  }
  if (messages.value.some((item) => item.msgId === message.msgId)) {
    return
  }
  // 断线重发 / ACK 迟到时，服务端回来的这条和本地那条"乐观气泡"是同一个 clientMsgNo：
```

改成：

``` code-block-container
  if (!message) {
    return
  }
  // 同一条消息再次推送：**就地更新**而不是丢弃。
  // 图片消息就是这样——先到一次（只有图片），识别完服务端会带着"AI 判读"再推一次。
  const existing = messages.value.findIndex((item) => item.msgId === message.msgId)
  if (existing >= 0) {
    messages.value.splice(existing, 1, { ...messages.value[existing], ...message })
    return
  }
  // 断线重发 / ACK 迟到时，服务端回来的这条和本地那条"乐观气泡"是同一个 clientMsgNo：
```

**修改 13（第 1321 行附近）**

原来是这样：

``` code-block-container
  border-top-right-radius: 4px;
}

.v-meta {
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 3px;
}

.v-time {
  margin-top: 4px;
  font-size: 11px;
  color: #a3aec2;
  text-align: right;
  opacity: 0;
  transition: opacity 0.15s ease;
}

/* 时间只在需要时出现：悬停这条消息（或触屏设备）就能看到 */
.v-row:hover .v-time,
.v-row:focus-within .v-time,
.v-bubble.is-pending .v-time,
.v-bubble.is-failed .v-time {
  opacity: 1;
}

@media (hover: none) {
  .v-time { opacity: 0.75; }
}

.is-self .v-time {
  color: #c7dbff;
}

.is-system .v-time {
  color: #94a3b8;
  text-align: left;
```

改成：

``` code-block-container
  border-top-right-radius: 4px;
}

/* 图片消息（文字 + 图）不走蓝色气泡：它是"媒体卡片"，
   白底 + 深色文字才读得清，图片也不会被蓝底框住 */
.is-self .v-bubble.is-image {
  background: #fff;
  border-color: #e8eef6;
}

.v-meta {
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 3px;
}

/* 时间直接显示：聊天记录要能一眼看出"这句是什么时候说的"（悬停才出现的做法，客户根本不知道有）
   颜色用 slate-400，和"客户名 / 有新消息"这些辅助信息同一个灰 */
.v-time {
  margin-top: 4px;
  font-size: 11px;
  color: #94a3b8;
  text-align: right;
}

.is-self .v-time {
  color: #c7dbff;
}

/* 蓝气泡上的浅蓝时间，放到白底卡片上就看不见了，单独调回来 */
.is-self .v-bubble.is-image .v-time {
  color: #94a3b8;
}

.is-system .v-time {
  color: #94a3b8;
  text-align: left;
```

**新增 14（第 1371 行附近）**

原来是这样：

``` code-block-container
  text-align: left;
}

.v-actions {
  display: flex;
  gap: 8px;
```

改成：

``` code-block-container
  text-align: left;
}

.v-image {
  display: block;
  margin: 2px 0;
  padding: 0;
  border: 0;
  background: none;
  text-align: left;
}

/* 随图说的那句话：显示在图片上方（白底 + 深色字），和客户输入的阅读顺序一致 */
.v-image-text {
  display: block;
  margin-bottom: 6px;
  color: #1f2937;
  white-space: pre-wrap;
  word-break: break-word;
}

.v-image img {
  display: block;
  max-width: 220px;
  max-height: 220px;
  border-radius: 8px;
  background: #f1f5f9;
  cursor: zoom-in;
}

/* 多张图（最多 3 张）：并排一行，尺寸统一，像相册一条 */
.v-images {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.v-images.is-multi .v-image img {
  width: 104px;
  height: 104px;
  object-fit: cover;
}

/* 待发送的图片：贴在输入框上方的一排"附件"（缩略图 + 文件名 + 移除） */
.vi-attach {
  display: flex;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 8px;
  padding: 8px 10px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  background: #f8fafc;
}

.vi-attach-item {
  width: 76px;
}

.vi-attach-thumb {
  position: relative;
  width: 76px;
  height: 58px;
  border-radius: 8px;
  overflow: hidden;
  background: #e2e8f0;
}

.vi-attach-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

/* 上传中的遮罩：转圈盖在缩略图上，客户一眼知道"还在传" */
.vi-attach-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: rgba(15, 23, 42, 0.45);
}

.vi-attach-name {
  margin-top: 4px;
  font-size: 12px;
  color: #64748b;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.vi-attach-hint {
  flex: 1 1 130px;
  min-width: 120px;
  align-self: center;
  font-size: 11px;
  color: #94a3b8;
}

.vi-attach-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  color: #fff;
  background: rgba(15, 23, 42, 0.55);
  cursor: pointer;
}

.vi-attach-remove:hover {
  background: #ef4444;
}

/* 发图按钮：和输入框同一行，左侧 */
.vi-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.vi-file {
  display: none;
}

.vi-btn-text {
  margin-left: 4px;
}

.v-actions {
  display: flex;
  gap: 8px;
```

**修改 15（第 1558 行附近）**

原来是这样：

``` code-block-container
.vi-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}

.vi-tip {
  font-size: 12px;
  color: #94a3b8;
}

.v-error {
```

改成：

``` code-block-container
.vi-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 10px;
}

.vi-tip {
  flex: 1;
  min-width: 0;
  font-size: 12px;
  color: #94a3b8;
  text-align: right;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.v-error {
```

### 6.3 聊天区滚动：图片加载完要贴回底部

### 改动：yunti-frontend/src/utils/chatScroll.ts

改动点：内容变高（图片加载完、气泡换行）时自动贴回底部；但客户在看历史时不许硬拽

图片消息的高度是**分两次长出来**的：气泡先出现（`<img>` 高度接近 0），图片下载完才撑开。双 rAF 那一刻量到的高度不是最终高度，滚出来就差一截，下面内容被挡住；而且一旦没滚到底，后面客服回复时"用户还在不在底部"的判断也变成否，连新消息也不再自动滚。所以除了 rAF，还要接"内容变高"的信号：容器上挂一个**捕获阶段**的 load 监听（img 的 load 不冒泡，但父元素捕获阶段收得到），再补几个延时校对。

这个文件一共 6 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 1 行附近）**

原来是这样：

``` code-block-container
import { nextTick, onUnmounted, ref, type Ref } from 'vue'

/**
 * 聊天区自动滚动（工作台和访客窗口共用）。
```

改成：

``` code-block-container
import { nextTick, onUnmounted, ref, watch, type Ref } from 'vue'

/**
 * 聊天区自动滚动（工作台和访客窗口共用）。
```

**新增 2（第 10 行附近）**

原来是这样：

``` code-block-container
 *       消息插入、气泡换行、正在输入那三个点消失，都会在之后改变容器高度；
 *       早一步设置 scrollTop，结果就是"滚了一点但没到底"，下面那条新消息还露在可视区外。
 *       这里用双 requestAnimationFrame，等这一帧的布局真正算完再滚。</li>
 *   <li><b>不能抢用户</b>：坐席往上翻历史时，新消息一到就把人拽到底部，是最招人烦的交互之一。
 *       所以只在"用户本来就在底部附近"时自动滚；否则只挂一个"有新消息 ↓"的提示，让用户自己决定。</li>
 * </ol>
```

改成：

``` code-block-container
 *       消息插入、气泡换行、正在输入那三个点消失，都会在之后改变容器高度；
 *       早一步设置 scrollTop，结果就是"滚了一点但没到底"，下面那条新消息还露在可视区外。
 *       这里用双 requestAnimationFrame，等这一帧的布局真正算完再滚。</li>
 *   <li><b>图片是"加载完才长高"的</b>：`<img>` 刚插进来时高度接近 0（`loading="lazy"` 还要等
 *       进入可视区才开始下载），双 rAF 那一刻量到的高度不是最终高度，滚出来自然差一截，
 *       下面那部分内容就被挡住了。所以除了 rAF，还要接"内容变高"的信号：在滚动容器上挂一个
 *       **捕获阶段**的 load 监听（img 的 load 不冒泡，但父元素在捕获阶段收得到），
 *       再补几个延时校对，兜住字体、折叠面板这类同样"晚一步才长高"的内容。</li>
 *   <li><b>不能抢用户</b>：坐席往上翻历史时，新消息一到就把人拽到底部，是最招人烦的交互之一。
 *       所以只在"用户本来就在底部附近"时自动滚；否则只挂一个"有新消息 ↓"的提示，让用户自己决定。</li>
 * </ol>
```

**新增 3（第 23 行附近）**

原来是这样：

``` code-block-container
/** 距离底部多少像素以内算"还在底部"（滚轮抖动、亚像素误差都不至于误判） */
const NEAR_BOTTOM_PX = 80

export interface ChatScroller {
  /** 当前是否贴着底部 */
  atBottom: Ref<boolean>
```

改成：

``` code-block-container
/** 距离底部多少像素以内算"还在底部"（滚轮抖动、亚像素误差都不至于误判） */
const NEAR_BOTTOM_PX = 80

/** 贴底之后的"校对时刻"（毫秒）：图片、字体、折叠面板都是加载完才长高，双 rAF 只保证那一刻的高度 */
const SETTLE_DELAYS_MS = [120, 400, 900, 1800]

export interface ChatScroller {
  /** 当前是否贴着底部 */
  atBottom: Ref<boolean>
```

**修改 4（第 43 行附近）**

原来是这样：

``` code-block-container
  const atBottom = ref(true)
  const hasNewBelow = ref(false)
  let rafId = 0

  function distanceToBottom(el: HTMLElement) {
    return el.scrollHeight - el.scrollTop - el.clientHeight
  }

  function scrollToBottom(force = false) {
    const el = scroller.value
    if (!el) {
      return
    }
    if (!force && !atBottom.value) {
      // 用户正在看上面的历史：不打扰，只提示下面有新内容
      hasNewBelow.value = true
      return
    }
    // 双 rAF：第一帧 Vue 把 DOM 改完，第二帧布局算完，这时候再滚才准
    cancelAnimationFrame(rafId)
    void nextTick(() => {
```

改成：

``` code-block-container
  const atBottom = ref(true)
  const hasNewBelow = ref(false)
  let rafId = 0
  const settleTimers: number[] = []

  function distanceToBottom(el: HTMLElement) {
    return el.scrollHeight - el.scrollTop - el.clientHeight
  }

  function cancelSettleTimers() {
    settleTimers.splice(0).forEach((id) => window.clearTimeout(id))
  }

  /** 内容长高了（图片加载完、气泡换行）：客户本来就在底部就跟着往下贴，他在看历史就不动他 */
  function pinIfStillAtBottom() {
    const el = scroller.value
    if (!el || !atBottom.value) {
      return
    }
    if (distanceToBottom(el) <= 1) {
      return
    }
    el.scrollTop = el.scrollHeight
    hasNewBelow.value = false
  }

  /**
   * 直接贴到底，并在随后的几个时刻再校对几次。
   *
   * <p>校对要解决的正是"图片消息"：气泡先出现、图片后加载，高度是分两次长出来的。
   * 每次校对都先看客户还在不在底部——他要是这几百毫秒里自己翻上去了，就绝不把他拽下来。</p>
   */
  function pinToBottom() {
    // 双 rAF：第一帧 Vue 把 DOM 改完，第二帧布局算完，这时候再滚才准
    cancelAnimationFrame(rafId)
    void nextTick(() => {
```

**新增 5（第 88 行附近）**

原来是这样：

``` code-block-container
        })
      })
    })
  }

  function onScroll() {
```

改成：

``` code-block-container
        })
      })
    })
    cancelSettleTimers()
    SETTLE_DELAYS_MS.forEach((delay) => {
      settleTimers.push(window.setTimeout(pinIfStillAtBottom, delay))
    })
  }

  function scrollToBottom(force = false) {
    const el = scroller.value
    if (!el) {
      return
    }
    if (!force && !atBottom.value) {
      // 用户正在看上面的历史：不打扰，只提示下面有新内容
      hasNewBelow.value = true
      return
    }
    pinToBottom()
  }

  function onScroll() {
```

**修改 6（第 122 行附近）**

原来是这样：

``` code-block-container
    scrollToBottom(true)
  }

  onUnmounted(() => cancelAnimationFrame(rafId))

  return { atBottom, hasNewBelow, scrollToBottom, onScroll, jumpToBottom }
}
```

改成：

``` code-block-container
    scrollToBottom(true)
  }

  // 滚动容器上挂"内容变高"的信号：<img> 的 load 事件不冒泡，但父元素在捕获阶段收得到。
  // 图片消息就靠它把"长高之后"的位置补回来（`loading="lazy"` 时图加载得晚，更是必须）。
  let bound: HTMLElement | null = null
  const onImageLoaded = () => pinIfStillAtBottom()
  watch(scroller, (el, _previous, onCleanup) => {
    bound?.removeEventListener('load', onImageLoaded, true)
    bound = el ?? null
    bound?.addEventListener('load', onImageLoaded, true)
    onCleanup(() => {
      bound?.removeEventListener('load', onImageLoaded, true)
      bound = null
    })
  }, { immediate: true, flush: 'post' })

  onUnmounted(() => {
    cancelAnimationFrame(rafId)
    cancelSettleTimers()
  })

  return { atBottom, hasNewBelow, scrollToBottom, onScroll, jumpToBottom }
}
```

### 6.4 坐席工作台：AI 判读卡

### 改动：yunti-frontend/src/views/workspace/index.vue

改动点：多图缩略图 + "AI 判读"卡（判读 / 订单号 / 金额 / 建议人工介入 / 报错原文 / 可折叠 OCR 原文）+ 页内预览

坐席看到的不是"一张光秃秃的图"：客户发报错截图时，坐席一眼就知道问题在哪、要不要人工介入（模型判了敏感情形就打个橙色标）。

这个文件一共 14 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**修改 1（第 260 行附近）**

原来是这样：

``` code-block-container
                    <span v-if="msg.visibleTo === 2" class="note-tag">内部备注</span>
                    <span v-if="riskySeqs.has(Number(msg.seq ?? -1))" class="qa-tag">质检命中</span>
                  </div>
                  <div class="msg-bubble" :class="{ 'is-pending': msg.pending, 'is-failed': msg.failed }">
                    <!-- 卡片消息：正文照常显示；"转人工客服"按钮是给客户点的，坐席这边只做提示 -->
                    <div class="msg-content">{{ cardOf(msg)?.text ?? msg.content }}</div>
                    <div v-if="cardOf(msg)?.actions?.length" class="msg-card-note">
                      已向客户提供「{{ cardOf(msg)?.actions?.[0]?.label }}」入口
                    </div>
```

改成：

``` code-block-container
                    <span v-if="msg.visibleTo === 2" class="note-tag">内部备注</span>
                    <span v-if="riskySeqs.has(Number(msg.seq ?? -1))" class="qa-tag">质检命中</span>
                  </div>
                  <div
                    class="msg-bubble"
                    :class="{ 'is-pending': msg.pending, 'is-failed': msg.failed, 'is-image': !!imageOf(msg) }"
                  >
                    <!-- 图片消息：缩略图 + AI 判读（坐席一眼知道客户发的什么图、图里写了什么） -->
                    <template v-if="imagesOf(msg).length">
                      <!-- 客户随图说的那句话：坐席看图和看文字的顺序，和客户发的时候一致 -->
                      <div v-if="imageOf(msg)?.text" class="msg-image-text">{{ imageOf(msg)?.text }}</div>
                      <div class="msg-images" :class="{ 'is-multi': imagesOf(msg).length > 1 }">
                        <button
                          v-for="(image, index) in imagesOf(msg)"
                          :key="image.fileId || image.url || index"
                          type="button"
                          class="msg-image"
                          :title="image.name || '查看大图'"
                          @click="openImage(image)"
                        >
                          <img :src="image.url" :alt="image.name || '图片'" loading="lazy" />
                        </button>
                      </div>
                      <div v-if="imageOf(msg)?.aiSummary" class="msg-ai">
                        <div class="msg-ai-head">
                          <el-icon :size="12"><MagicStick /></el-icon>
                          AI 判读
                          <span v-if="imageOf(msg)?.aiOrderNo" class="msg-ai-tag">
                            订单号 {{ imageOf(msg)?.aiOrderNo }}
                          </span>
                          <span v-if="imageOf(msg)?.aiAmount" class="msg-ai-tag">
                            金额 {{ imageOf(msg)?.aiAmount }}
                          </span>
                          <!-- 模型判了"这单该找人工"：给坐席一个显眼的标，别靠翻 OCR 原文才发现 -->
                          <span v-if="imageOf(msg)?.aiNeedHuman" class="msg-ai-tag is-warn">
                            建议人工介入
                          </span>
                        </div>
                        <div class="msg-ai-text">{{ imageOf(msg)?.aiSummary }}</div>
                        <div v-if="imageOf(msg)?.aiErrorText" class="msg-ai-error">
                          报错原文：{{ imageOf(msg)?.aiErrorText }}
                        </div>
                        <el-collapse v-if="imageOf(msg)?.aiOcrText" class="msg-ai-ocr">
                          <el-collapse-item title="查看图中文字（OCR）" :name="msg.msgId">
                            <pre class="msg-ai-pre">{{ imageOf(msg)?.aiOcrText }}</pre>
                          </el-collapse-item>
                        </el-collapse>
                      </div>
                      <div v-else-if="imageOf(msg)?.aiAvailable === false" class="msg-ai muted">
                        图片识别暂不可用（没配视觉模型密钥）
                      </div>
                      <div v-else class="msg-ai muted">正在识别图片…</div>
                    </template>
                    <!-- 卡片消息：正文照常显示；"转人工客服"按钮是给客户点的，坐席这边只做提示 -->
                    <template v-else-if="cardOf(msg)">
                      <div class="msg-content">{{ cardOf(msg)?.text }}</div>
                    </template>
                    <div v-else class="msg-content">{{ msg.content }}</div>
                    <div v-if="cardOf(msg)?.actions?.length" class="msg-card-note">
                      已向客户提供「{{ cardOf(msg)?.actions?.[0]?.label }}」入口
                    </div>
```

**新增 2（第 454 行附近）**

原来是这样：

``` code-block-container
      insertable
      @insert="onInsertKnowledge"
    />
  </div>
</template>
```

改成：

``` code-block-container
      insertable
      @insert="onInsertKnowledge"
    />

    <!-- 图片预览：客户发的截图在**本页**弹层里看大图，右上角有关闭（点遮罩、按 Esc 也能关） -->
    <el-image-viewer
      v-if="previewOpen"
      :url-list="previewUrls"
      :initial-index="previewIndex"
      :hide-on-click-modal="true"
      teleported
      @close="previewOpen = false"
    />
  </div>
</template>
```

**新增 3（第 474 行附近）**

原来是这样：

``` code-block-container
  Clock,
  CopyDocument,
  Iphone,
  Monitor,
  Promotion,
  Refresh,
```

改成：

``` code-block-container
  Clock,
  CopyDocument,
  Iphone,
  MagicStick,
  Monitor,
  Promotion,
  Refresh,
```

**新增 4（第 495 行附近）**

原来是这样：

``` code-block-container
import {
  fetchSessionWorkload,
  getSessionDetail,
  listSessionMessages,
  listSessions,
  type SessionItem,
  type SessionMessageItem,
} from '../../api/customer/session'
```

改成：

``` code-block-container
import {
  fetchSessionWorkload,
  getSessionDetail,
  imagesOf,
  messageTextOf,
  parseImageContent,
  listSessionMessages,
  listSessions,
  type ChatImageItem,
  type SessionItem,
  type SessionMessageItem,
} from '../../api/customer/session'
```

**修改 5（第 1335 行附近）**

原来是这样：

``` code-block-container
 * 需要带来源的整段记录用右上角的「复制会话」。</p>
 */
async function copyMessage(msg: SessionMessageItem) {
  const ok = await copyText(msg.content || '')
  if (ok) {
    ElMessage.success('已复制这条消息')
  } else {
```

改成：

``` code-block-container
 * 需要带来源的整段记录用右上角的「复制会话」。</p>
 */
async function copyMessage(msg: SessionMessageItem) {
  // 图片消息的正文是 JSON，复制 JSON 没意义——取"随图说的那句话"
  const ok = await copyText(messageTextOf(msg))
  if (ok) {
    ElMessage.success('已复制这条消息')
  } else {
```

**修改 6（第 1361 行附近）**

原来是这样：

``` code-block-container
    const time = fullTime(msg.sendTime)
    const who = senderText(msg)
    const note = msg.visibleTo === 2 ? '[内部备注] ' : ''
    return `[${time}] ${who}：${note}${msg.content}`
  })
  const ok = await copyText([header, ...lines].join('\n'))
  if (ok) {
```

改成：

``` code-block-container
    const time = fullTime(msg.sendTime)
    const who = senderText(msg)
    const note = msg.visibleTo === 2 ? '[内部备注] ' : ''
    return `[${time}] ${who}：${note}${messageTextOf(msg)}`
  })
  const ok = await copyText([header, ...lines].join('\n'))
  if (ok) {
```

**新增 7（第 1386 行附近）**

原来是这样：

``` code-block-container
  actions?: MessageCardAction[]
}

function cardOf(message: SessionMessageItem): MessageCard | null {
  if (message.msgType !== 3 || !message.content) {
    return null
```

改成：

``` code-block-container
  actions?: MessageCardAction[]
}

/** 图片消息解析（msgType=2 的正文是 JSON：fileId / url / name + 识别结论） */
function imageOf(message: SessionMessageItem) {
  return message.msgType === 2 ? parseImageContent(message.content) : null
}

/**
 * 图片预览：点缩略图在**本页**弹层里看原图（原来 `target="_blank"` 会多开一个标签页，
 * 坐席聊到一半被带走还得找回来）。右上角有关闭，点遮罩、按 Esc 也能关；
 * 本会话里的图排成一条链，弹层里能左右切换（客户一条消息发 3 张图时，坐席能挨个看）。
 */
const previewOpen = ref(false)
const previewIndex = ref(0)
const previewUrls = computed(() =>
  messages.value
    .flatMap((item) => imagesOf(item).map((image) => image.url))
    .filter((url): url is string => !!url),
)

function openImage(image: ChatImageItem) {
  const url = image?.url
  if (!url) {
    return
  }
  const index = previewUrls.value.indexOf(url)
  previewIndex.value = index >= 0 ? index : 0
  previewOpen.value = true
}

function cardOf(message: SessionMessageItem): MessageCard | null {
  if (message.msgType !== 3 || !message.content) {
    return null
```

**修改 8（第 1486 行附近）**

原来是这样：

``` code-block-container
  if (!message) {
    return
  }
  if (messages.value.some((item) => item.msgId === message.msgId)) {
    return
  }
  messages.value.push(message)
```

改成：

``` code-block-container
  if (!message) {
    return
  }
  // 同一条消息再次推送就地更新：图片消息识别完会被回填并重推一次（带上 AI 判读与 OCR 原文）
  const existing = messages.value.findIndex((item) => item.msgId === message.msgId)
  if (existing >= 0) {
    messages.value.splice(existing, 1, { ...messages.value[existing], ...message })
    return
  }
  messages.value.push(message)
```

**新增 9（第 1635 行附近）**

原来是这样：

``` code-block-container
}

function preview(item: SessionItem) {
  if (!item.lastContent) {
    return '暂无消息'
  }
```

改成：

``` code-block-container
}

function preview(item: SessionItem) {
  if (item.lastContent && item.lastContent.trimStart().startsWith('{')) {
    // 图片/卡片消息的正文是 JSON，直接显示会是一串大括号——列表里统一显示成人话
    try {
      const parsed = JSON.parse(item.lastContent) as { url?: string; text?: string }
      if (parsed?.url) {
        return item.lastSenderType === 1 ? '[图片] 客户发来一张图片' : '[图片]'
      }
      if (parsed?.text) {
        return parsed.text
      }
    } catch {
      // 解析不了就按普通文本走
    }
  }
  if (!item.lastContent) {
    return '暂无消息'
  }
```

**删除 10（第 1661 行附近）**

原来是这样：

``` code-block-container
  }
  return text
}

/** 这张会话是不是我在接待 */
function isMySession(item: SessionItem) {
  return !!item.agentId && Number(item.agentId) === Number(userStore.userId)
}
```

改成：

``` code-block-container
  }
  return text
}
function isMySession(item: SessionItem) {
  return !!item.agentId && Number(item.agentId) === Number(userStore.userId)
}
```

**新增 11（第 2750 行附近）**

原来是这样：

``` code-block-container
  border-top-right-radius: 4px;
}

.is-note .msg-bubble {
  background: #fff7e6;
  border: 1px dashed #f0b429;
```

改成：

``` code-block-container
  border-top-right-radius: 4px;
}

/* 图片消息（文字 + 图）不走蓝色气泡：它是"媒体卡片"，
   白底 + 深色文字才读得清，下面的"AI 判读"也是照白底设计的 */
.is-customer .msg-bubble.is-image {
  background: #fff;
  border-color: #e8eef6;
}

.is-note .msg-bubble {
  background: #fff7e6;
  border: 1px dashed #f0b429;
```

**新增 12（第 2796 行附近）**

原来是这样：

``` code-block-container
  30% { transform: translateY(-4px); opacity: 1; }
}

.msg-card-note {
  margin-top: 6px;
  font-size: 11px;
```

改成：

``` code-block-container
  30% { transform: translateY(-4px); opacity: 1; }
}

/* 图片消息：缩略图 + AI 判读块 */
.msg-image img {
  display: block;
  max-width: 260px;
  max-height: 260px;
  border-radius: 8px;
  background: #f1f5f9;
  cursor: zoom-in;
}

/* 客户一条消息带多张图（最多 3 张）：并排一行，尺寸统一 */
.msg-images {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.msg-images.is-multi .msg-image img {
  width: 118px;
  height: 118px;
  object-fit: cover;
}

.msg-image {
  display: block;
  padding: 0;
  border: 0;
  background: none;
  text-align: left;
}

/* 客户随图说的那句话：显示在图片上方，坐席看图和看文字的顺序和客户发的时候一致 */
.msg-image-text {
  display: block;
  margin-bottom: 6px;
  color: #1f2937;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-ai {
  margin-top: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e8eef6;
  font-size: 12px;
  color: #475569;
}

.msg-ai.muted {
  color: #94a3b8;
}

.msg-ai-head {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #1d4ed8;
  font-weight: 600;
  margin-bottom: 4px;
}

.msg-ai-tag {
  padding: 1px 6px;
  border-radius: 4px;
  background: #eff6ff;
  color: #1d4ed8;
  font-weight: 500;
}

/* "建议人工介入"：暖色，和蓝色信息标区分开 */
.msg-ai-tag.is-warn {
  background: #fef3c7;
  color: #b45309;
}

.msg-ai-text {
  line-height: 1.6;
}

.msg-ai-error {
  margin-top: 4px;
  color: #b45309;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.msg-ai-ocr {
  margin-top: 4px;
}

.msg-ai-pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  color: #475569;
  max-height: 220px;
  overflow: auto;
}

.msg-card-note {
  margin-top: 6px;
  font-size: 11px;
```

**修改 13（第 2911 行附近）**

原来是这样：

``` code-block-container
  margin-bottom: 3px;
}

/* 客户说的话是蓝底，meta 行（客户名）要用浅蓝，别再用默认灰 */
.is-customer .msg-meta {
  color: #c7dbff;
}

/* 时间统一放在消息下方：先看内容，时间只是辅助信息 */
.msg-time {
  margin-top: 4px;
  font-size: 11px;
  color: #a3aec2;
  text-align: left;
  opacity: 0;
  transition: opacity 0.15s ease;
}

/* 时间只在需要时出现：悬停这条消息（或触屏）就能看到 */
.msg-row:hover .msg-time,
.msg-row:focus-within .msg-time,
.msg-bubble.is-pending .msg-time,
.msg-bubble.is-failed .msg-time {
  opacity: 1;
}

@media (hover: none) {
  .msg-time { opacity: 0.75; }
}

.is-customer .msg-time {
```

改成：

``` code-block-container
  margin-bottom: 3px;
}

/* 客户说的话是蓝底、气泡外面是浅色页面：名字用可读的灰，
   别再用浅蓝（原来那版在浅底上几乎看不见） */
.is-customer .msg-meta {
  color: #94a3b8;
}

/* 时间统一放在消息下方：先看内容，时间只是辅助信息 */
/* 时间直接显示：坐席要能一眼看出"客户这句是什么时候说的"，别做成悬停才出现 */
.msg-time {
  margin-top: 4px;
  font-size: 11px;
  color: #94a3b8;
  text-align: left;
}

.is-customer .msg-time {
```

**新增 14（第 2931 行附近）**

原来是这样：

``` code-block-container
  text-align: right;
}

.is-system .msg-time {
  color: #94a3b8;
  text-align: left;
```

改成：

``` code-block-container
  text-align: right;
}

/* 蓝气泡上的浅蓝时间，放到白底卡片上就看不见了，单独调回来 */
.is-customer .msg-bubble.is-image .msg-time {
  color: #94a3b8;
}

.is-system .msg-time {
  color: #94a3b8;
  text-align: left;
```

## 七、运行与验证

### 7.1 起服务与配密钥

``` code-block-container
# 1) 密钥：视觉识别和文本模型共用同一个千问密钥（推荐写进 yunti-ai/.env）
cd yunti-ai && cp .env.example .env && $EDITOR .env
#    YUNTI_AI_QWEN_API_KEY=sk-...      必填：识别 + 回答 + 向量化都用它
#    YUNTI_AI_QWEN_VL_MODEL=qwen-vl-plus   可选：换成 qwen-vl-max 更准但更贵

# 2) 起服务：yunti-ai(9100) + customer-service(9093) + realtime(9096) + 网关(9090) + 前端(5173)

# 3) 视觉模型体检：密钥读到没有、实际用哪个模型、一次最多几张图
curl -s "http://127.0.0.1:9100/api/ai/v1/agent/vision/health?tenant_code=T202609050000002"

# 4) 离线自检：不连库不连服务，识别解析 / 降级 / 多图上限 / 超限拦截逐个断言
cd yunti-ai && ./.venv/bin/python ../scripts/verify-vision.py --offline

# 5) 真实链路：上传 3 张图 → 当一条消息发进会话 → 断言识别回填与机器人回答
python scripts/verify-vision.py
```

### 7.2 页面里怎么测

**多图一条消息**：一次选 3 张（也可以一张张加），输入一句话再发送。

  

<img src="https://article-images.zsxq.com/FuR8xp2wKzpmtlTOmvG11ysgTk5P" class="tiptap-image" alt="图片.png" />

  

<img src="https://article-images.zsxq.com/FmeEZl6Z0tKxGMskbib8OhOiCwTl" class="tiptap-image" alt="图片.png" />

  

<img src="https://article-images.zsxq.com/Fp7DddTFYUEYhaqK_IRA92nDFR5g" class="tiptap-image" alt="图片.png" />

预期：一条消息带 3 张缩略图；日志里 `张数=3`、`预处理=…ms（并发）`、`耗时=…ms（预处理 … + 模型 …）`。

可以正确读取图片中的文字：

  

<img src="https://article-images.zsxq.com/FoRQ-uIkogf-lzcJ_bs66flp3WpV" class="tiptap-image" alt="图片.png" />

### 总结

这一篇让机器人从"听得懂"变成"看得见"：

1.  **分层要清晰**：Java 负责"把图变小"，Python 负责"把图看懂"。压图放在有对象存储客户端的一侧、模型格式收口在一层里，换模型只改一个文件。

2.  **交互要替客户想一步**：发图不是"选完就发"，而是"暂存 → 和文字一起发"。一个动作拆成两步，服务端收到的才是完整上下文（客户问了什么 + 图里是什么）。

3.  **图片消息是"会变的"**：先到一次（只有图）、识别完再更新一次带 `ai*`。所以前端遇到同一条消息要**就地更新**而不是丢弃，坐席端才能实时看到判读。

4.  **慢的地方要把时间拆开**：3 张图的"下载 + 压缩"并发做（顺序不变），模型仍是一次调用（要看图与图的关系）；日志把"预处理"和"模型"分开打，下次说"慢"的时候先看是哪一半。
