package cn.net.susan.realtime.ws;

import cn.net.susan.common.exception.BizException;
import cn.net.susan.realtime.client.SessionApiClient;
import cn.net.susan.realtime.config.RealtimeProperties;
import cn.net.susan.realtime.security.RealtimePrincipal;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长连接主处理：建连 → 订阅会话 → 收发消息 → 断开清理。
 *
 * <p>消息可靠性约定：客户端每条消息带 {@code clientMsgNo}，服务端落库后回 {@code ACK}
 * （带服务端消息号），前端据 ACK 把"发送中"改成"已发送"；没收到 ACK 的重连后重发，
 * 服务端按 {@code msg_no} 唯一约束去重。</p>
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    /** 一次最多回多少条历史消息 */
    private static final int JOIN_HISTORY_LIMIT = 50;
    private static final int PAGE_HISTORY_LIMIT = 30;
    private static final int MAX_CONTENT_LENGTH = 4000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConnectionRegistry registry;
    private final SessionApiClient sessionApi;
    private final RealtimeProperties properties;

    public RealtimeWebSocketHandler(
            ConnectionRegistry registry,
            SessionApiClient sessionApi,
            RealtimeProperties properties
    ) {
        this.registry = registry;
        this.sessionApi = sessionApi;
        this.properties = properties;
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
        send(socket, RealtimeMessage.withData("CONNECTED", principal.sessionNo(), info));

        // 访客连上就自动进入自己的会话，前端不用再发 JOIN
        if (principal.isVisitor()) {
            handleJoin(socket, principal.sessionNo(), false);
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
                        "PONG", null, null, null, null, null, null, System.currentTimeMillis(), null, null));
                case "JOIN" -> handleJoin(client.socket(), inbound.sessionNo(), true);
                case "SEND" -> handleSend(client, inbound);
                case "HISTORY" -> handleHistory(client, inbound);
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
        if (client.principal().isVisitor() && client.principal().sessionNo() != null) {
            broadcastPresence(client.principal().sessionNo());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession rawSession, Throwable exception) {
        log.warn("长连接传输异常 socketId={} error={}", rawSession.getId(), exception.getMessage());
        closeQuietly(rawSession, CloseStatus.SERVER_ERROR);
    }

    /**
     * 进入会话：坐席认领 + 回历史消息 + 广播在线状态。
     */
    private void handleJoin(WebSocketSession socket, String requestedSessionNo, boolean explicit) {
        ConnectionRegistry.Client client = registry.get(socket.getId());
        if (client == null) {
            return;
        }
        RealtimePrincipal principal = client.principal();
        String sessionNo = resolveSessionNo(principal, requestedSessionNo);

        SessionApiClient.SessionInfo session = sessionApi.requireSession(principal.tenantCode(), sessionNo);
        if (session.closed()) {
            send(socket, RealtimeMessage.error(40301, "会话已结束"));
            return;
        }
        registry.subscribe(socket.getId(), sessionNo);

        // 坐席主动 JOIN 视为接入（认领）；已被别人接待时只提示，仍可旁观
        if (explicit && principal.identity() == RealtimePrincipal.Identity.AGENT) {
            try {
                session = sessionApi.assignAgent(principal.tenantCode(), sessionNo, principal.id());
                broadcast(sessionNo, RealtimeMessage.withData("SESSION", sessionNo, session), null);
            } catch (BizException e) {
                send(socket, RealtimeMessage.error(e.getCode(), e.getMessage()));
            }
        }

        List<SessionApiClient.MessageView> messages =
                sessionApi.history(principal.tenantCode(), sessionNo, null, JOIN_HISTORY_LIMIT);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session", session);
        payload.put("messages", messages);
        payload.put("online", registry.onlineCount(sessionNo));
        send(socket, RealtimeMessage.withData("JOINED", sessionNo, payload));
        broadcastPresence(sessionNo);
    }

    /**
     * 发消息：落库 → 回 ACK → 广播给会话内其他连接。
     */
    private void handleSend(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        RealtimePrincipal principal = client.principal();
        String sessionNo = resolveSessionNo(principal, inbound.sessionNo());
        String content = inbound.content() == null ? "" : inbound.content().trim();
        if (content.isEmpty()) {
            throw new BizException(40001, "消息内容不能为空");
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
                content
        );

        send(client.socket(), new RealtimeMessage("ACK", sessionNo, inbound.clientMsgNo(), null, null, null,
                saved, System.currentTimeMillis(), null, null));
        broadcast(sessionNo,
                new RealtimeMessage("MESSAGE", sessionNo, null, null, null, null,
                        saved, System.currentTimeMillis(), null, null),
                client.socket().getId());
    }

    /**
     * 向上翻聊天记录。
     */
    private void handleHistory(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        RealtimePrincipal principal = client.principal();
        String sessionNo = resolveSessionNo(principal, inbound.sessionNo());
        List<SessionApiClient.MessageView> messages = sessionApi.history(
                principal.tenantCode(), sessionNo, inbound.beforeId(), PAGE_HISTORY_LIMIT);
        send(client.socket(), RealtimeMessage.withData("HISTORY", sessionNo, messages));
    }

    /**
     * 坐席结束会话。
     */
    private void handleClose(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        RealtimePrincipal principal = client.principal();
        if (principal.isVisitor()) {
            throw new BizException(40301, "访客不能结束会话");
        }
        String sessionNo = resolveSessionNo(principal, inbound.sessionNo());
        SessionApiClient.SessionInfo session = sessionApi.close(principal.tenantCode(), sessionNo);
        broadcast(sessionNo, RealtimeMessage.withData("SESSION", sessionNo, session), null);
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
        broadcast(sessionNo, RealtimeMessage.withData("PRESENCE", sessionNo,
                Map.of("online", registry.onlineCount(sessionNo))), null);
    }

    private void broadcast(String sessionNo, RealtimeMessage message, String excludeSocketId) {
        for (String socketId : registry.subscribersOf(sessionNo)) {
            if (socketId.equals(excludeSocketId)) {
                continue;
            }
            ConnectionRegistry.Client client = registry.get(socketId);
            if (client != null) {
                send(client.socket(), message);
            }
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
