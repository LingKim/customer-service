package cn.net.susan.realtime.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.realtime.security.RealtimePrincipal;
import cn.net.susan.realtime.security.RealtimeTokenParser;
import cn.net.susan.realtime.ws.ConnectionRegistry;
import cn.net.susan.realtime.ws.RealtimeWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 在线状态查询：坐席工作台定期（或切回前台时）用 HTTP 对一次账。
 *
 * <p>为什么不用长连接？因为"状态过期"的典型原因恰恰是长连接断了、或被后台标签页节流，
 * 这时候再让客户端通过长连接要数据是自相矛盾的。HTTP 走网关，链路独立，才能自愈。</p>
 */
@RestController
@RequestMapping("/api/realtime")
public class PresenceController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final ConnectionRegistry registry;
    private final RealtimeTokenParser tokenParser;
    private final RealtimeWebSocketHandler handler;
    private final String sharedSecret;

    public PresenceController(
            ConnectionRegistry registry,
            RealtimeTokenParser tokenParser,
            RealtimeWebSocketHandler handler,
            @Value("${yunti.internal.shared-secret:}") String sharedSecret
    ) {
        this.registry = registry;
        this.tokenParser = tokenParser;
        this.handler = handler;
        this.sharedSecret = sharedSecret;
    }

    /**
     * 当前租户在线的访客会话号列表（只认坐席令牌）。
     */
    @GetMapping("/presence")
    public ApiResponse<PresenceView> presence(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        RealtimePrincipal principal = tokenParser.parse(resolveBearer(authorization));
        if (principal.isVisitor()) {
            // 访客令牌只能进自己那一条会话，不能拿来看整个租户的在线情况
            throw new BizException(40301, "访客无权查看坐席在线状态");
        }
        return ApiResponse.ok(new PresenceView(
                List.copyOf(registry.onlineVisitorSessions(principal.tenantCode()))));
    }

    private String resolveBearer(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }

    /**
     * 在线快照。
     */
    public record PresenceView(List<String> onlineSessions) {
    }

    /**
     * 内部接口：customer-service 智能路由分配成功后调用，通知坐席"你被派单了"。
     */
    @PostMapping("/internal/assigned")
    public ApiResponse<Map<String, Object>> assigned(
            @RequestHeader(value = "X-Yunti-Internal-Secret", required = false) String internalSecret,
            @RequestBody AssignedBody body) {
        requireInternalSecret(internalSecret);
        int delivered = handler.notifyAssigned(
                body.tenantCode(), body.agentId(), body.sessionNo(), body.reason());
        return ApiResponse.ok(Map.of("delivered", delivered));
    }

    private void requireInternalSecret(String internalSecret) {
        if (sharedSecret == null || sharedSecret.isBlank() || internalSecret == null || internalSecret.isBlank()
                || !MessageDigest.isEqual(sharedSecret.getBytes(StandardCharsets.UTF_8),
                        internalSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(40301, "内部接口未授权");
        }
    }

    /** 自动接入通知请求体 */
    public record AssignedBody(String tenantCode, long agentId, String sessionNo, String reason) {
    }

    /**
     * 内部接口：会话被自动分配（智能路由 / 机器人转人工）后，把状态同步给会话里的所有人。
     *
     * <p>坐席手点「接入会话」走长连接、天然会广播；自动分配发生在 customer-service，
     * 不通知一次的话，客户会一直停在"正在为您转接人工客服，请稍候"。</p>
     */
    @PostMapping("/internal/claimed")
    public ApiResponse<Map<String, Object>> claimed(@RequestBody ClaimedBody body) {
        int delivered = handler.notifyClaimed(body.tenantCode(), body.sessionNo(), body.agentId());
        return ApiResponse.ok(Map.of("delivered", delivered));
    }

    /** 会话已被接入的同步请求体 */
    public record ClaimedBody(String tenantCode, String sessionNo, Long agentId) {
    }

    /**
     * 内部接口：customer-service 实时质检命中后调用，把预警推给这条会话的坐席。
     */
    @PostMapping("/internal/qa-alert")
    public ApiResponse<Map<String, Object>> qaAlert(
            @RequestHeader(value = "X-Yunti-Internal-Secret", required = false) String internalSecret,
            @RequestBody QaAlertBody body) {
        requireInternalSecret(internalSecret);
        int delivered = handler.notifyQaAlert(
                body.tenantCode(), body.sessionNo(), body.agentId(), body.payload());
        return ApiResponse.ok(Map.of("delivered", delivered));
    }

    /** 实时质检预警请求体 */
    public record QaAlertBody(String tenantCode, String sessionNo, Long agentId, Map<String, Object> payload) {
    }

    /**
     * 内部接口：customer-service 落了一条"机器人回复 / 系统提示"后调用，让长连接广播出去。
     *
     * <p>机器人回复不是从前端长连接发起的，长连接手里没有这条消息；
     * 不推一次，访客就只能靠刷新页面才看到机器人回了什么。</p>
     */
    @PostMapping("/internal/message")
    public ApiResponse<Map<String, Object>> message(@RequestBody NotifyMessageBody body) {
        int delivered = handler.notifyMessage(
                body.tenantCode(), body.sessionNo(), body.message(),
                body.refreshAgents() == null || body.refreshAgents());
        return ApiResponse.ok(Map.of("delivered", delivered));
    }

    /** 广播消息请求体 */
    public record NotifyMessageBody(
            String tenantCode,
            String sessionNo,
            Map<String, Object> message,
            Boolean refreshAgents
    ) {
    }

    /**
     * 内部接口：customer-service 通知"机器人/坐席正在输入"，由长连接推给双方。
     *
     * <p>客户发完消息到机器人答上来有几秒，中间没有任何反馈，客户会怀疑消息没发出去。</p>
     */
    @PostMapping("/internal/typing")
    public ApiResponse<Map<String, Object>> typing(@RequestBody TypingBody body) {
        int delivered = handler.notifyTyping(
                body.tenantCode(), body.sessionNo(), body.who(), body.typing() == null || body.typing());
        return ApiResponse.ok(Map.of("delivered", delivered));
    }

    /** 输入状态请求体 */
    public record TypingBody(String tenantCode, String sessionNo, String who, Boolean typing) {
    }

    /**
     * 内部接口：探活 + 版本自证。
     *
     * <p>customer-service 靠它判断"对面这个实时网关是不是新版"——
     * 旧版没有 /internal/message，机器人回复推不出去，现象却是"机器人不回话"，
     * 查起来很绕。这里把版本能力直接写在返回里。</p>
     */
    @GetMapping("/internal/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("status", "UP");
        // 这一项是"版本指纹"：旧版没有 /internal/message，机器人回复推不出去
        body.put("internalMessage", true);
        body.put("connections", registry.totalConnections());
        return ApiResponse.ok(body);
    }
}
