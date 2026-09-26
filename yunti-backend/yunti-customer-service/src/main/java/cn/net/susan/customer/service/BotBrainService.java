package cn.net.susan.customer.service;

import cn.net.susan.common.exception.BizException;
import cn.net.susan.customer.entity.Session;
import cn.net.susan.customer.internal.BotAiClient;
import cn.net.susan.customer.internal.RealtimeNotifyClient;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 客服大脑的**业务编排**：把"机器人接一轮"这件事落到会话上。
 *
 * <p>它自己不判断意图、不判断情绪——那些在 Python 侧（yunti-ai 的 /agent/brain）。
 * 这边只做四件事，都是 Java 侧的活：</p>
 *
 * <ol>
 *   <li><b>取上下文</b>：把这个会话最近若干条消息整理成对话历史喂给大脑；</li>
 *   <li><b>落库</b>：机器人的回复作为一条 senderType=3 的消息落进 session_message；</li>
 *   <li><b>更新会话</b>：把识别出的意图/情绪写进 session（工作台列表要用）；</li>
 *   <li><b>转人工</b>：大脑说要转 → 会话回到"1-排队中"并立刻尝试分配坐席。</li>
 * </ol>
 *
 * <p>降级策略是这一篇的重点之一：<b>AI 不可用 ≠ 客户没人管</b>。
 * 大脑调用失败时直接转人工，绝不让客户对着一个不回话的机器人干等。</p>
 */
@Service
public class BotBrainService {

    private static final Logger log = LoggerFactory.getLogger(BotBrainService.class);

    /** 传给大脑的历史消息条数：够模型看懂上下文，又不至于把 token 烧光 */
    private static final int MAX_HISTORY = 12;

    /** 卡片消息要拼 JSON，复用一份 ObjectMapper（线程安全） */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final BotAiClient aiClient;
    private final RealtimeNotifyClient notifyClient;
    private final ObjectProvider<SessionService> sessionProvider;
    /** 全局总开关：关掉就退回"进线直接排队等人工"的老流程 */
    private final boolean enabled;
    private final boolean receptionEnabled;
    private final int topK;

    /**
     * 同一会话串行处理。
     *
     * <p>客户手快连发三条时，三条消息会各自触发一次机器人；
     * 并发跑三轮会出现"回复顺序乱、意图/情绪被后一轮覆盖"的问题。
     * 单实例使用固定数量的锁条带，避免移除会话锁时与排队中的同会话任务竞态；
     * 多实例部署需要跨实例协调。</p>
     */
    private final Object[] sessionLocks = createSessionLocks();

    private static Object[] createSessionLocks() {
        Object[] locks = new Object[256];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    /**
     * 机器人接待专用线程池。
     *
     * <p>为什么必须异步：这一轮要调 AI（识别 + 检索 + 生成，秒级）。
     * 如果跟着"客户消息落库"的事务提交后同步执行，访客那条消息的响应会被拖到
     * 机器人答完才返回——前端一直是"发送中"，体感上比机器人答得慢还糟。</p>
     *
     * <p>队列满了用 CallerRunsPolicy 而不是丢弃：宁可慢一点，也不能悄悄漏掉一个客户。</p>
     */
    private final ExecutorService executor = new ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(500),
            botThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy());

    private static ThreadFactory botThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "bot-brain-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public BotBrainService(
            BotAiClient aiClient,
            RealtimeNotifyClient notifyClient,
            ObjectProvider<SessionService> sessionProvider,
            @Value("${yunti.bot.enabled:true}") boolean enabled,
            @Value("${yunti.bot.reception-enabled:true}") boolean receptionEnabled,
            @Value("${yunti.bot.top-k:5}") int topK
    ) {
        this.aiClient = aiClient;
        this.notifyClient = notifyClient;
        this.sessionProvider = sessionProvider;
        this.enabled = enabled;
        this.receptionEnabled = receptionEnabled;
        this.topK = topK <= 0 ? 5 : topK;
    }

    /** 机器人总开关（配置关闭时整条机器人链路不参与）。 */
    public boolean enabled() {
        return enabled;
    }

    /** 关服时把在跑的接待任务收个尾，别让线程池吊着进程退出。 */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** 是否由机器人首轮接待。 */
    public boolean receptionEnabled() {
        return enabled && receptionEnabled;
    }

    /** 取租户的机器人档案：客户侧显示的名字 + 开场欢迎语。 */
    public BotAiClient.BotProfile botProfile(String tenantCode) {
        return aiClient.botProfile(tenantCode);
    }

    /**
     * 客户说了一句 → 机器人接一轮（异步执行，不拖慢消息发送）。
     *
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
        Object lock = sessionLocks[Math.floorMod(lockKey.hashCode(), sessionLocks.length)];
        try {
            synchronized (lock) {
                runOneTurn(tenantCode, sessionNo);
            }
        } catch (Exception e) {
            // 必须在这里兜住：这是线程池里的任务，异常跑出去只会打到 stderr，
            // 现象就是"机器人没回话"，日志里却没有一句"为什么"。
            log.error("机器人接待异常 tenant={} sessionNo={}", tenantCode, sessionNo, e);
        }
    }

    /** 一轮接待的主体：先确认该不该机器人管，再跑大脑。 */
    private void runOneTurn(String tenantCode, String sessionNo) {
        SessionService sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            log.warn("跳过机器人接待：SessionService 还没就绪 tenant={} sessionNo={}", tenantCode, sessionNo);
            return;
        }
        Session session;
        try {
            session = sessions.requireSession(tenantCode, sessionNo);
        } catch (BizException e) {
            log.warn("跳过机器人接待：{} tenant={} sessionNo={}", e.getMessage(), tenantCode, sessionNo);
            return;
        }
        String reason = skipReason(session);
        if (reason != null) {
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
            return "会话已由人工接待（agentId=" + session.getAgentId() + "）";
        }
        Integer status = session.getStatus();
        if (Integer.valueOf(SessionService.STATUS_CLOSED).equals(status)) {
            return "会话已结束";
        }
        if (!Integer.valueOf(SessionService.STATUS_QUEUING).equals(status)
                && !Integer.valueOf(SessionService.STATUS_BOT).equals(status)) {
            return "会话状态不是排队中/机器人接待（status=" + status + "）";
        }
        return null;
    }

    /**
     * 这条会话现在该不该由机器人接。
     *
     * <p>已经有人工坐席的会话，机器人一句都不该插——人工接待期间机器人再回一句，
     * 客户会以为是两个人同时在跟他说话。</p>
     */
    private boolean isBotResponsible(Session session) {
        return skipReason(session) == null;
    }

    /** 真正跑一轮大脑并把结果落到会话上。 */
    private void reply(String tenantCode, Session session, SessionService sessions) {
        String sessionNo = session.getSessionNo();
        List<Map<String, String>> messages = buildHistory(tenantCode, sessionNo, sessions);
        if (messages.isEmpty() || !"user".equals(messages.get(messages.size() - 1).get("role"))) {
            // 同一时刻多条客户消息可能排入多个任务；前一任务回复后，后续任务
            // 看到最后一条已是机器人消息，就不再重复回答同一个问题。
            return;
        }
        Map<String, Object> result;
        try {
            result = aiClient.think(tenantCode, sessionNo, messages, topK);
        } catch (Exception e) {
            // 大脑不可用：不能让客户干等，直接转人工
            log.warn("客服大脑不可用，降级转人工 tenant={} sessionNo={} error={}",
                    tenantCode, sessionNo, e.getMessage());
            escalate(sessions, tenantCode, sessionNo, "智能客服暂时不可用，已转人工",
                    "智能客服暂时无法应答，正在为您转接人工客服，请稍候");
            return;
        }

        if (!boolValue(result.get("bot_enabled"), true)) {
            // 租户把机器人关了：不用机器人的口吻回话，直接进人工队列并告诉客户一声
            escalate(sessions, tenantCode, sessionNo,
                    strValue(result.get("transfer_reason"), "机器人服务已关闭"),
                    "正在为您转接人工客服，请稍候");
            return;
        }

        String intent = strValue(result.get("intent"), null);
        String emotion = strValue(result.get("emotion"), null);
        sessions.updateBotState(tenantCode, session.getId(), intent, emotion);

        // 大脑这一轮跑了几秒，期间坐席可能已经接手了：再确认一次，别让人工和机器人同时说话
        if (!stillBotResponsible(tenantCode, sessionNo, sessions)) {
            log.info("机器人生成完毕但会话已被人工接手，放弃发送 tenant={} sessionNo={}",
                    tenantCode, sessionNo);
            return;
        }

        String reply = strValue(result.get("reply"), "");
        boolean needHuman = boolValue(result.get("need_human"), false);
        if (!reply.isBlank()) {
            // 带动作（目前只有"转人工客服"按钮）的消息发成卡片：前端渲染成 文字 + 按钮，
            // 客户点一下才真的转人工。纯文本消息仍走 msgType=1，历史数据不受影响。
            List<Map<String, Object>> actions = actionsOf(result);
            int msgType = actions.isEmpty() ? 1 : 3;
            String content = actions.isEmpty() ? reply : cardJson(reply, actions);
            SessionService.MessageVO saved = sessions.appendMessage(
                    tenantCode, sessionNo, SessionService.SENDER_BOT, null, msgType, content);
            notifyClient.notifyMessage(tenantCode, sessionNo, toMap(saved), true);
        }
        log.info("机器人接待完成 tenant={} sessionNo={} 意图={} 情绪={} 转人工={} 原因={}",
                tenantCode, sessionNo, intent, emotion, needHuman,
                result.get("transfer_reason"));
        if (needHuman) {
            // 交接话术已经由机器人那条回复说了，这里不再补一句一模一样的系统提示
            escalate(sessions, tenantCode, sessionNo,
                    strValue(result.get("transfer_reason"), "智能客服转人工"), null);
        }
    }

    /**
     * 转人工：落库流转、尝试分配坐席，并把该说的话立刻推给客户。
     *
     * <p>关键的一点：**分配不到人时要如实告诉客户**。以前不管有没有坐席在线，
     * 客户看到的都是"正在为您转接人工客服，请稍候"——没人可接时这句话就成了空头支票，
     * 客户只能一直等（"怎么一直在排队"就是这么来的）。</p>
     */
    private void escalate(SessionService sessions, String tenantCode, String sessionNo,
                          String reason, String notice) {
        SessionService.EscalateResult result =
                sessions.escalateToHuman(tenantCode, sessionNo, reason, notice);
        if (result.notice() != null) {
            notifyClient.notifyMessage(tenantCode, sessionNo, toMap(result.notice()), true);
        }
        if (result.assigned()) {
            log.info("转人工已接入坐席 tenant={} sessionNo={} agentId={}",
                    tenantCode, sessionNo, result.agentId());
            return;
        }
        // 没派到人：给客户一句实话，同时让在线坐席刷新列表（有人上线就会自动接走）
        log.warn("转人工后暂无人接待（坐席不在线或都在忙） tenant={} sessionNo={} 原因={}",
                tenantCode, sessionNo, reason);
        String waiting = "当前人工客服暂时无法接入（客服不在线或正在忙），已经为您排入队列，"
                + "客服上线后会第一时间接入；您也可以先留言，我们看到会尽快回复。";
        SessionService.MessageVO waitNotice = sessions.appendMessage(
                tenantCode, sessionNo, SessionService.SENDER_SYSTEM, null, 5, waiting);
        notifyClient.notifyMessage(tenantCode, sessionNo, toMap(waitNotice), true);
    }

    /** 从大脑返回里取出要挂在消息上的动作（为空表示普通文本消息） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> actionsOf(Map<String, Object> result) {
        Object actions = result.get("actions");
        if (!(actions instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> normalized = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> action = new LinkedHashMap<>();
                raw.forEach((key, value) -> action.put(String.valueOf(key), value));
                if (action.get("type") != null && action.get("label") != null) {
                    normalized.add(action);
                }
            }
        }
        return normalized;
    }

    /** 卡片消息正文：{"text": "...", "actions": [...]}（前端按这个结构渲染） */
    private String cardJson(String text, List<Map<String, Object>> actions) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("text", text);
        card.put("actions", actions);
        try {
            return objectMapper.writeValueAsString(card);
        } catch (Exception e) {
            // 序列化失败就退回纯文本：宁可少一个按钮，也不能让消息发不出去
            log.warn("机器人卡片消息序列化失败，降级成纯文本：{}", e.getMessage());
            return text;
        }
    }

    /** 再查一次会话状态：只有仍然没人接待、且没结束时，机器人才发这条回复。 */
    private boolean stillBotResponsible(String tenantCode, String sessionNo, SessionService sessions) {
        try {
            return isBotResponsible(sessions.requireSession(tenantCode, sessionNo));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 把会话消息整理成大脑能吃的对话历史。
     *
     * <p>角色映射：客户(1) → user；坐席(2)/机器人(3) → assistant；系统提示(4) 不进上下文
     * （"正在为您转接人工客服"这种话喂给模型，只会干扰它对客户诉求的判断）。</p>
     */
    private List<Map<String, String>> buildHistory(String tenantCode, String sessionNo,
                                                   SessionService sessions) {
        List<SessionService.MessageVO> rows =
                sessions.historyByTenant(tenantCode, sessionNo, null, MAX_HISTORY, false);
        List<Map<String, String>> messages = new ArrayList<>(rows.size());
        for (SessionService.MessageVO row : rows) {
            Integer sender = row.senderType();
            if (sender == null || sender == SessionService.SENDER_SYSTEM) {
                continue;
            }
            String content = row.content();
            if (content == null || content.isBlank()) {
                continue;
            }
            String role = sender == SessionService.SENDER_CUSTOMER ? "user" : "assistant";
            messages.add(BotAiClient.message(role, content));
        }
        return messages;
    }

    /** 消息对象转发给长连接时用的普通 Map（跨服务只传 JSON，不传 record）。 */
    private Map<String, Object> toMap(SessionService.MessageVO message) {
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

    private boolean boolValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return "true".equalsIgnoreCase(text.trim());
        }
        return fallback;
    }

    private String strValue(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }
}
