package cn.net.susan.realtime.ws;

import cn.net.susan.common.exception.BizException;
import cn.net.susan.realtime.client.SessionApiClient;
import cn.net.susan.realtime.config.RealtimeProperties;
import cn.net.susan.realtime.security.RealtimePrincipal;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长连接主处理：建连 → 进入会话 → 收发消息 → 协同（认领/转接/备注）→ 断开清理。
 *
 * <p>消息可靠性约定：客户端每条消息带 {@code clientMsgNo}，服务端落库后回 {@code ACK}
 * （带服务端消息号），前端据 ACK 把"发送中"改成"已发送"；没收到 ACK 的重连后重发。</p>
 *
 * <p>会话协同约定：一个会话可以有多个坐席同时在（主接 + 协助）；
 * 内部备注（NOTE）只广播给坐席连接，访客拉历史时会被过滤掉。</p>
 *
 * <p>列表同步约定：会话内消息只推给"正在看这个会话"的连接，坐席工作台左侧列表靠
 * {@code QUEUE} 事件驱动——访客进出排队、访客发消息时，租户内所有在线坐席都会收到
 * 一条轻量提醒，前端据此重新拉取列表，避免坐席不开会话就完全感知不到新访客。</p>
 *
 * <p>访客离线约定：客户关掉页面就断开长连接，坐席端要立刻看到"客户已离线"，
 * 并且在离线超过 {@code yunti.realtime.visitor-offline-close-minutes} 分钟后自动结束会话，
 * 避免大量"客户早走了、会话还挂在待接待里"的僵尸会话。</p>
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    /** 一次最多回多少条历史消息 */
    private static final int JOIN_HISTORY_LIMIT = 50;
    private static final int PAGE_HISTORY_LIMIT = 30;
    private static final int MAX_CONTENT_LENGTH = 4000;

    /** 与 customer-service 约定的可见范围 */
    private static final int VISIBLE_ALL = 1;
    private static final int VISIBLE_AGENT_ONLY = 2;

    /** 系统操作人 ID：自动结束会话时写进流转记录 */
    private static final long SYSTEM_OPERATOR_ID = 0L;

    /** 离线扫描间隔：30 秒一次，够准也不费资源 */
    private static final long OFFLINE_SWEEP_MILLIS = 30_000L;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConnectionRegistry registry;
    private final SessionApiClient sessionApi;
    private final RealtimeProperties properties;

    /** 访客离线超过多少分钟自动结束会话（0 表示不自动结束） */
    private final long visitorOfflineCloseMinutes;

    /** sessionNo → 访客离线时间；访客重连就移除，扫描到超时再结束会话 */
    private final Map<String, OfflineVisitor> offlineVisitors = new ConcurrentHashMap<>();

    /** 访客离线标记（租户 + 断开时刻） */
    private record OfflineVisitor(String tenantCode, long offlineAt) {
    }

    public RealtimeWebSocketHandler(
            ConnectionRegistry registry,
            SessionApiClient sessionApi,
            RealtimeProperties properties,
            @Value("${yunti.realtime.visitor-offline-close-minutes:10}") long visitorOfflineCloseMinutes
    ) {
        this.registry = registry;
        this.sessionApi = sessionApi;
        this.properties = properties;
        this.visitorOfflineCloseMinutes = visitorOfflineCloseMinutes;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) {
        Object attribute = rawSession.getAttributes().get(AuthHandshakeInterceptor.PRINCIPAL_KEY);
        if (!(attribute instanceof RealtimePrincipal principal)) {
            closeQuietly(rawSession, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        // 包装成并发安全的 session：广播和心跳可能同时往一条连接写
        WebSocketSession socket = new ConcurrentWebSocketSessionDecorator(
                rawSession, properties.getSendTimeLimitMs(), properties.getSendBufferSizeLimit());
        registry.bind(socket, principal);
        log.info("长连接建立 userId={} identity={} tenant={} 当前连接数={}",
                principal.id(), principal.identity(), principal.tenantCode(), registry.totalConnections());

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", principal.id());
        info.put("name", principal.name());
        info.put("identity", principal.identity().name());
        info.put("tenantCode", principal.tenantCode());
        info.put("sessionNo", principal.sessionNo());
        info.put("online", registry.totalConnections());
        if (!principal.isVisitor()) {
            // 坐席刚上线/重连：先给一份"哪些会话的客户此刻在线"的快照，列表状态点就不会猜错
            info.put("onlineSessions", registry.onlineVisitorSessions(principal.tenantCode()));
            info.put("visitorOfflineCloseMinutes", visitorOfflineCloseMinutes);
        }
        send(socket, RealtimeMessage.withData("CONNECTED", principal.sessionNo(), info));

        if (principal.isVisitor()) {
            // 访客连上就自动进入自己的会话，前端不用再发 JOIN
            SessionApiClient.SessionInfo session;
            try {
                session = handleJoin(socket, principal.sessionNo());
            } catch (BizException e) {
                // 会话不存在（被清理、或换了环境）等异常情况：明确回一条错误再正常关闭。
                // 不这样做的话，异常会冒到 Spring 的 WebSocket 装饰器里，
                // 每重连一次就打一份完整堆栈，日志瞬间被刷爆。
                log.warn("访客建连失败 userId={} sessionNo={} code={} message={}",
                        principal.id(), principal.sessionNo(), e.getCode(), e.getMessage());
                send(socket, RealtimeMessage.error(e.getCode(), e.getMessage()));
                closeQuietly(socket, CloseStatus.NORMAL);
                return;
            }
            if (session != null && session.closed()) {
                // 会话已结束：handleJoin 已经回过"会话已结束"，这条连接留着也没用，直接断开
                closeQuietly(socket, CloseStatus.NORMAL);
                return;
            }
            if (session != null) {
                // 客户回来了：撤销离线标记，并让所有坐席把状态点切回"在线"
                offlineVisitors.remove(session.sessionNo());
                notifySessionChanged(principal.tenantCode(), session.sessionNo(), "VISITOR_ONLINE", true);
            }
        } else {
            // 坐席上线：让所有在线坐席看到彼此的状态与接待量
            broadcastAgents(principal.tenantCode());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession rawSession, TextMessage textMessage) {
        ConnectionRegistry.Client client = registry.get(rawSession.getId());
        if (client == null) {
            return;
        }
        client.touch();
        if (!client.allow(properties.getMaxMessagesPerSecond())) {
            send(client.socket(), RealtimeMessage.error(42900, "发送过于频繁，请稍后再试"));
            return;
        }

        RealtimeMessage inbound;
        try {
            inbound = objectMapper.readValue(textMessage.getPayload(), RealtimeMessage.class);
        } catch (Exception e) {
            send(client.socket(), RealtimeMessage.error(40000, "消息格式不正确"));
            return;
        }

        String type = inbound.type() == null ? "" : inbound.type().trim().toUpperCase();
        try {
            switch (type) {
                case "PING" -> send(client.socket(), new RealtimeMessage(
                        "PONG", null, null, null, null, null, null, null, null,
                        System.currentTimeMillis(), null, null));
                case "JOIN" -> handleJoin(client.socket(), inbound.sessionNo());
                case "SEND" -> handleSend(client, inbound);
                case "NOTE" -> handleNote(client, inbound);
                case "HISTORY" -> handleHistory(client, inbound);
                case "CLAIM" -> handleClaim(client, inbound);
                case "RELEASE" -> handleRelease(client, inbound);
                case "TRANSFER" -> handleTransfer(client, inbound);
                case "CLOSE_SESSION" -> handleClose(client, inbound);
                default -> send(client.socket(), RealtimeMessage.error(40000, "不支持的消息类型：" + type));
            }
        } catch (BizException e) {
            send(client.socket(), RealtimeMessage.error(e.getCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("处理长连接消息失败 type={} socketId={}", type, rawSession.getId(), e);
            send(client.socket(), RealtimeMessage.error(50000, "服务处理失败，请稍后重试"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession rawSession, CloseStatus status) {
        ConnectionRegistry.Client client = registry.unbind(rawSession.getId());
        if (client == null) {
            return;
        }
        log.info("长连接断开 userId={} identity={} code={} 当前连接数={}",
                client.principal().id(), client.principal().identity(), status.getCode(), registry.totalConnections());
        if (client.principal().isVisitor()) {
            String sessionNo = client.principal().sessionNo();
            if (sessionNo != null) {
                offlineVisitors.put(sessionNo, new OfflineVisitor(
                        client.principal().tenantCode(), System.currentTimeMillis()));
                broadcastPresence(sessionNo);
                // 客户走了：坐席端立刻标记离线，不用等刷新
                notifySessionChanged(client.principal().tenantCode(), sessionNo, "VISITOR_OFFLINE", false);
            }
        } else {
            broadcastAgents(client.principal().tenantCode());
        }
    }

    /**
     * 访客离线超时自动结束会话。
     *
     * <p>客户关掉页面就不再有连接，会话如果一直挂着，坐席的待接待列表会越来越脏。
     * 这里每 30 秒扫一次离线标记：超过阈值且期间没有重连，就自动结束会话
     * （流转记录的操作人是系统，结束原因写清楚），并广播给坐席。</p>
     *
     * <p>单实例内存实现，够跑通单机；多实例部署要先把离线标记挪到 Redis，
     * 否则"访客连在 A 实例、扫描在 B 实例"会漏判。</p>
     */
    @Scheduled(fixedDelay = OFFLINE_SWEEP_MILLIS)
    public void closeOfflineSessions() {
        if (visitorOfflineCloseMinutes <= 0 || offlineVisitors.isEmpty()) {
            return;
        }
        long deadline = System.currentTimeMillis() - visitorOfflineCloseMinutes * 60_000L;
        for (Map.Entry<String, OfflineVisitor> entry : offlineVisitors.entrySet()) {
            String sessionNo = entry.getKey();
            OfflineVisitor mark = entry.getValue();
            if (mark.offlineAt() > deadline) {
                continue;
            }
            // 先摘掉标记再处理：重连（remove）与超时结束（remove）只会有一方成功
            if (!offlineVisitors.remove(sessionNo, mark)) {
                continue;
            }
            // 兜底再确认一次：扫描前一刻客户又回来了就不结束
            if (registry.onlineVisitorSessions(mark.tenantCode()).contains(sessionNo)) {
                continue;
            }
            try {
                SessionApiClient.SessionInfo session = sessionApi.requireSession(mark.tenantCode(), sessionNo);
                if (session.closed()) {
                    continue;
                }
                SessionApiClient.SessionInfo closed = sessionApi.close(mark.tenantCode(), sessionNo, SYSTEM_OPERATOR_ID,
                        "客户已离线超过 " + visitorOfflineCloseMinutes + " 分钟，系统自动结束会话");
                broadcastSession(mark.tenantCode(), sessionNo, closed);
                notifySessionChanged(mark.tenantCode(), sessionNo, "SESSION_CLOSED_BY_TIMEOUT", null);
                broadcastAgents(mark.tenantCode());
                log.info("访客离线超时，会话已自动结束 tenant={} sessionNo={} 离线时长={} 分钟",
                        mark.tenantCode(), sessionNo, visitorOfflineCloseMinutes);
            } catch (Exception e) {
                log.warn("访客离线超时自动结束会话失败 tenant={} sessionNo={} error={}",
                        mark.tenantCode(), sessionNo, e.getMessage());
            }
        }
    }

    @Override
    public void handleTransportError(WebSocketSession rawSession, Throwable exception) {
        log.warn("长连接传输异常 socketId={} error={}", rawSession.getId(), exception.getMessage());
        closeQuietly(rawSession, CloseStatus.SERVER_ERROR);
    }

    /**
     * 进入会话：订阅 + 回历史 + 广播在线情况。
     *
     * <p>注意：JOIN 只是"看着这个会话"（坐席可以多人同时看，用于协同），
     * 真正把会话变成"我的"要显式发 CLAIM。</p>
     */
    private SessionApiClient.SessionInfo handleJoin(WebSocketSession socket, String requestedSessionNo) {
        ConnectionRegistry.Client client = registry.get(socket.getId());
        if (client == null) {
            return null;
        }
        RealtimePrincipal principal = client.principal();
        String sessionNo = resolveSessionNo(principal, requestedSessionNo);

        SessionApiClient.SessionInfo session = sessionApi.requireSession(principal.tenantCode(), sessionNo);
        if (session.closed()) {
            if (principal.isVisitor()) {
                // 客户侧：会话结束了这条连接就没用了，回错误让前端提示"可重新发起"
                send(socket, RealtimeMessage.error(40301, "会话已结束"));
                return session;
            }
            // 坐席侧：已结束的会话改成只读查看（"全部会话"里点开就是这种），
            // 直接回 JOINED 带上历史，前端能正常渲染，也不会再弹"会话已结束"的提示；
            // 想发消息会被 customer-service 的落库校验挡下来（会话已结束，无法继续发送消息）
            registry.subscribe(socket.getId(), sessionNo);
            sendJoined(socket, principal, session);
            return session;
        }
        registry.subscribe(socket.getId(), sessionNo);
        sendJoined(socket, principal, session);
        broadcastPresence(sessionNo);
        return session;
    }

    /**
     * 回 JOINED：会话对象 + 历史消息 + 在线情况（"还有没有更早的消息"也一起给前端）。
     */
    private void sendJoined(WebSocketSession socket, RealtimePrincipal principal, SessionApiClient.SessionInfo session) {
        String sessionNo = session.sessionNo();
        List<SessionApiClient.MessageView> messages = sessionApi.history(
                principal.tenantCode(), sessionNo, null, JOIN_HISTORY_LIMIT, !principal.isVisitor());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session", session);
        payload.put("messages", messages);
        payload.put("online", registry.onlineCount(sessionNo));
        payload.put("agents", registry.agentIdsInSession(sessionNo));
        // 告诉前端"还有没有更早的消息"，工作台的「更早消息」按钮据此置灰
        payload.put("hasMore", messages.size() >= JOIN_HISTORY_LIMIT);
        // 会话当前最大序号：客户端拿本地最大序号一比，就知道断线期间有没有漏消息
        payload.put("lastSeq", session.lastSeq() == null ? 0L : session.lastSeq());
        send(socket, RealtimeMessage.withData("JOINED", sessionNo, payload));
    }

    /**
     * 发消息：落库 → 回 ACK → 广播给会话内其他连接。
     */
    private void handleSend(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        saveAndBroadcast(client, inbound, VISIBLE_ALL);
    }

    /**
     * 内部备注：只有坐席看得见，访客既收不到推送、拉历史也看不到。
     */
    private void handleNote(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        if (client.principal().isVisitor()) {
            throw new BizException(40301, "访客不能发送内部备注");
        }
        saveAndBroadcast(client, inbound, VISIBLE_AGENT_ONLY);
    }

    private void saveAndBroadcast(ConnectionRegistry.Client client, RealtimeMessage inbound, int visibleTo) {
        RealtimePrincipal principal = client.principal();
        String sessionNo = resolveSessionNo(principal, inbound.sessionNo());
        String content = inbound.content() == null ? "" : inbound.content().trim();
        if (content.isEmpty()) {
            throw new BizException(40001, visibleTo == VISIBLE_AGENT_ONLY ? "备注内容不能为空" : "消息内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BizException(40001, "单条消息最长 " + MAX_CONTENT_LENGTH + " 字");
        }

        int senderType = principal.isVisitor() ? 1 : 2;
        SessionApiClient.MessageView saved = sessionApi.appendMessage(
                principal.tenantCode(),
                sessionNo,
                senderType,
                principal.id(),
                inbound.msgType() == null ? 1 : inbound.msgType(),
                content,
                visibleTo,
                inbound.clientMsgNo()
        );

        send(client.socket(), new RealtimeMessage("ACK", sessionNo, inbound.clientMsgNo(), null, null, null,
                null, null, saved, System.currentTimeMillis(), null, null));
        broadcast(sessionNo,
                new RealtimeMessage("MESSAGE", sessionNo, null, null, null, null, null, null,
                        saved, System.currentTimeMillis(), null, null),
                client.socket().getId(),
                visibleTo == VISIBLE_AGENT_ONLY);

        // 访客说话：提醒租户内所有坐席刷新列表（最后一条消息、排队会话都会变）
        if (principal.isVisitor()) {
            notifySessionChanged(principal.tenantCode(), sessionNo, "VISITOR_MESSAGE", null);
        }
    }

    /**
     * 向上翻聊天记录（访客看不到内部备注）。
     */
    private void handleHistory(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        RealtimePrincipal principal = client.principal();
        String sessionNo = resolveSessionNo(principal, inbound.sessionNo());
        List<SessionApiClient.MessageView> messages = sessionApi.history(
                principal.tenantCode(), sessionNo, inbound.beforeId(), PAGE_HISTORY_LIMIT, !principal.isVisitor());
        send(client.socket(), RealtimeMessage.withData("HISTORY", sessionNo, messages));
    }

    /**
     * 认领：把待接待会话变成"我的"（并发抢单由 customer-service 兜住）。
     */
    private void handleClaim(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        RealtimePrincipal principal = requireAgent(client);
        String sessionNo = resolveSessionNo(principal, inbound.sessionNo());
        SessionApiClient.SessionInfo session = sessionApi.claim(principal.tenantCode(), sessionNo, principal.id());
        registry.subscribe(client.socket().getId(), sessionNo);
        broadcastSession(principal.tenantCode(), sessionNo, session);
        // "人工客服已接入"这类系统提示要立刻出现在双方的对话窗口里，不能等刷新
        broadcastLatestMessage(principal.tenantCode(), sessionNo);
        broadcastAgents(principal.tenantCode());
        log.info("坐席认领会话 tenant={} sessionNo={} agentId={}", principal.tenantCode(), sessionNo, principal.id());
    }

    /**
     * 退回队列。
     */
    private void handleRelease(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        RealtimePrincipal principal = requireAgent(client);
        String sessionNo = resolveSessionNo(principal, inbound.sessionNo());
        SessionApiClient.SessionInfo session = sessionApi.release(principal.tenantCode(), sessionNo, principal.id());
        broadcastSession(principal.tenantCode(), sessionNo, session);
        broadcastAgents(principal.tenantCode());
    }

    /**
     * 转接给其他坐席。
     */
    private void handleTransfer(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        RealtimePrincipal principal = requireAgent(client);
        String sessionNo = resolveSessionNo(principal, inbound.sessionNo());
        if (inbound.toAgentId() == null) {
            throw new BizException(40001, "请选择要转接的客服");
        }
        SessionApiClient.SessionInfo session = sessionApi.transfer(
                principal.tenantCode(), sessionNo, principal.id(), inbound.toAgentId(), inbound.remark());
        broadcastSession(principal.tenantCode(), sessionNo, session);
        broadcastLatestMessage(principal.tenantCode(), sessionNo);
        broadcastAgents(principal.tenantCode());
        log.info("会话转接 tenant={} sessionNo={} from={} to={}",
                principal.tenantCode(), sessionNo, principal.id(), inbound.toAgentId());
    }

    /**
     * 结束会话（可带结束小结）。
     */
    private void handleClose(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        RealtimePrincipal principal = requireAgent(client);
        String sessionNo = resolveSessionNo(principal, inbound.sessionNo());
        SessionApiClient.SessionInfo session = sessionApi.close(
                principal.tenantCode(), sessionNo, principal.id(), inbound.remark());
        broadcastSession(principal.tenantCode(), sessionNo, session);
        broadcastLatestMessage(principal.tenantCode(), sessionNo);
        broadcastAgents(principal.tenantCode());
    }

    /**
     * 把会话里最新的一条系统提示推给会话内所有人。
     *
     * <p>认领 / 转接 / 结束这类动作由 customer-service 落库时补一条系统消息
     * （"人工客服已接入，很高兴为您服务"等），实时网关负责把它广播出去——
     * 客户和坐席两边的对话窗口都要立刻看到，不用刷新页面。</p>
     *
     * <p>只取客户可见的消息（agentView=false），避免把坐席内部备注推到访客侧。</p>
     */
    private void broadcastLatestMessage(String tenantCode, String sessionNo) {
        try {
            List<SessionApiClient.MessageView> latest =
                    sessionApi.history(tenantCode, sessionNo, null, 1, false);
            if (latest.isEmpty()) {
                return;
            }
            SessionApiClient.MessageView message = latest.get(latest.size() - 1);
            broadcast(sessionNo,
                    new RealtimeMessage("MESSAGE", sessionNo, null, null, null, null, null, null,
                            message, System.currentTimeMillis(), null, null),
                    null,
                    false);
        } catch (Exception e) {
            log.warn("推送会话系统提示失败 tenant={} sessionNo={} error={}", tenantCode, sessionNo, e.getMessage());
        }
    }

    private RealtimePrincipal requireAgent(ConnectionRegistry.Client client) {
        RealtimePrincipal principal = client.principal();
        if (principal.isVisitor()) {
            throw new BizException(40301, "访客不能执行该操作");
        }
        return principal;
    }

    private String resolveSessionNo(RealtimePrincipal principal, String requested) {
        if (principal.isVisitor()) {
            if (requested != null && !requested.isBlank() && !requested.equals(principal.sessionNo())) {
                throw new BizException(40301, "无权访问该会话");
            }
            return principal.sessionNo();
        }
        if (requested == null || requested.isBlank()) {
            throw new BizException(40001, "缺少会话号");
        }
        return requested;
    }

    private void broadcastPresence(String sessionNo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("online", registry.onlineCount(sessionNo));
        payload.put("agents", registry.agentIdsInSession(sessionNo));
        broadcast(sessionNo, RealtimeMessage.withData("PRESENCE", sessionNo, payload), null, false);
    }

    /**
     * 会话状态变化（认领 / 退回 / 转接 / 结束）：会话内的连接拿最新会话对象，
     * 租户内其它在线坐席也收一份，保证左侧列表和不属于自己但已流转的会话同步。
     */
    private void broadcastSession(String tenantCode, String sessionNo, SessionApiClient.SessionInfo session) {
        Set<String> delivered = new LinkedHashSet<>();
        RealtimeMessage message = RealtimeMessage.withData("SESSION", sessionNo, session);
        for (String socketId : registry.subscribersOf(sessionNo)) {
            ConnectionRegistry.Client client = registry.get(socketId);
            if (client != null && delivered.add(socketId)) {
                send(client.socket(), message);
            }
        }
        for (ConnectionRegistry.Client client : registry.agentClients(tenantCode)) {
            if (delivered.add(client.socket().getId())) {
                send(client.socket(), message);
            }
        }
    }

    /**
     * 列表变更提醒：访客上/下线、发消息时通知租户内所有在线坐席。
     *
     * <p>只推"变了"这个信号（带会话号与原因），列表内容仍由坐席按自己的筛选条件拉取，
     * 避免为每个坐席拼一份可能上千条的列表；{@code visitorOnline} 不为空时，
     * 前端不用额外再查一次就能把状态点切过来。</p>
     */
    private void notifySessionChanged(String tenantCode, String sessionNo, String reason, Boolean visitorOnline) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionNo", sessionNo);
        payload.put("reason", reason);
        if (visitorOnline != null) {
            payload.put("visitorOnline", visitorOnline);
        }
        RealtimeMessage message = RealtimeMessage.withData("QUEUE", sessionNo, payload);
        for (ConnectionRegistry.Client client : registry.agentClients(tenantCode)) {
            send(client.socket(), message);
        }
    }

    /**
     * 广播坐席在线状态与接待量：只在坐席之间同步，不给访客看。
     */
    private void broadcastAgents(String tenantCode) {
        Set<Long> online = registry.onlineAgentIds(tenantCode);
        Map<Long, Integer> workload = new LinkedHashMap<>();
        try {
            for (SessionApiClient.AgentLoadView load : sessionApi.workload(tenantCode)) {
                workload.put(load.agentId(), load.sessionCount() == null ? 0 : load.sessionCount());
            }
        } catch (Exception e) {
            log.warn("拉取坐席接待量失败 tenant={} error={}", tenantCode, e.getMessage());
        }
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Long agentId : online) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("agentId", agentId);
            item.put("online", true);
            item.put("sessionCount", workload.getOrDefault(agentId, 0));
            agents.add(item);
        }
        RealtimeMessage message = RealtimeMessage.withData("AGENTS", null, Map.of("agents", agents));
        for (ConnectionRegistry.Client client : registry.agentClients(tenantCode)) {
            send(client.socket(), message);
        }
    }

    private void broadcast(String sessionNo, RealtimeMessage message, String excludeSocketId, boolean agentOnly) {
        for (String socketId : registry.subscribersOf(sessionNo)) {
            if (socketId.equals(excludeSocketId)) {
                continue;
            }
            ConnectionRegistry.Client client = registry.get(socketId);
            if (client == null) {
                continue;
            }
            if (agentOnly && client.principal().isVisitor()) {
                continue;
            }
            send(client.socket(), message);
        }
    }

    private void send(WebSocketSession socket, RealtimeMessage message) {
        if (socket == null || !socket.isOpen()) {
            return;
        }
        try {
            socket.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (Exception e) {
            log.warn("下行消息发送失败 socketId={} error={}", socket.getId(), e.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession socket, CloseStatus status) {
        try {
            socket.close(status);
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
    }
}
