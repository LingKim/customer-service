package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.customer.entity.Session;
import cn.net.susan.customer.service.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * 内部接口：仅供 yunti-realtime-service 调用（长连接里的会话与消息落库）。
 *
 * <p>实时网关不直接连客户库，避免"两个服务同时写一个库"，
 * 这条边界在生产可以平滑替换成 RPC 或消息队列。</p>
 */
@RestController
@RequestMapping("/api/customer/internal/sessions")
public class SessionInternalController {

    private final SessionService sessionService;
    private final String sharedSecret;

    public SessionInternalController(
            SessionService sessionService,
            @Value("${yunti.internal.shared-secret:}") String sharedSecret
    ) {
        this.sessionService = sessionService;
        this.sharedSecret = sharedSecret;
    }

    /**
     * 取会话（实时网关鉴权后确认会话存在、状态可用）。
     */
    @GetMapping("/{sessionNo}")
    public ApiResponse<SessionInfo> detail(
            @RequestHeader(value = "X-Yunti-Internal-Secret", required = false) String internalSecret,
            @PathVariable String sessionNo,
            @RequestParam String tenantCode
    ) {
        requireInternalSecret(internalSecret);
        return ApiResponse.ok(toInfo(sessionService.requireSession(tenantCode, sessionNo)));
    }

    /**
     * 拉历史消息（JOIN 时回给前端）。
     */
    @GetMapping("/{sessionNo}/messages")
    public ApiResponse<List<SessionService.MessageVO>> messages(
            @RequestHeader(value = "X-Yunti-Internal-Secret", required = false) String internalSecret,
            @PathVariable String sessionNo,
            @RequestParam String tenantCode,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer limit
    ) {
        requireInternalSecret(internalSecret);
        return ApiResponse.ok(sessionService.historyByTenant(tenantCode, sessionNo, beforeId, limit));
    }

    /**
     * 消息落库。
     */
    @PostMapping("/{sessionNo}/messages")
    public ApiResponse<SessionService.MessageVO> append(
            @RequestHeader(value = "X-Yunti-Internal-Secret", required = false) String internalSecret,
            @PathVariable String sessionNo,
            @Valid @RequestBody AppendBody body
    ) {
        requireInternalSecret(internalSecret);
        return ApiResponse.ok(sessionService.appendMessage(
                body.tenantCode(),
                sessionNo,
                body.senderType() == null ? SessionService.SENDER_CUSTOMER : body.senderType(),
                body.senderId(),
                body.msgType() == null ? 1 : body.msgType(),
                body.content()
        ));
    }

    /**
     * 坐席接入会话。
     */
    @PostMapping("/{sessionNo}/assign")
    public ApiResponse<SessionInfo> assign(
            @RequestHeader(value = "X-Yunti-Internal-Secret", required = false) String internalSecret,
            @PathVariable String sessionNo,
            @Valid @RequestBody AssignBody body
    ) {
        requireInternalSecret(internalSecret);
        return ApiResponse.ok(toInfo(sessionService.assignAgent(body.tenantCode(), sessionNo, body.agentId())));
    }

    /**
     * 结束会话。
     */
    @PostMapping("/{sessionNo}/close")
    public ApiResponse<SessionInfo> close(
            @RequestHeader(value = "X-Yunti-Internal-Secret", required = false) String internalSecret,
            @PathVariable String sessionNo,
            @Valid @RequestBody CloseBody body
    ) {
        requireInternalSecret(internalSecret);
        return ApiResponse.ok(toInfo(sessionService.closeSession(body.tenantCode(), sessionNo)));
    }

    private void requireInternalSecret(String provided) {
        if (sharedSecret == null || sharedSecret.isBlank() || provided == null || provided.isBlank()
                || !MessageDigest.isEqual(sharedSecret.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(40301, "内部接口未授权");
        }
    }

    private SessionInfo toInfo(Session session) {
        return new SessionInfo(
                session.getSessionNo(),
                session.getTenantCode(),
                session.getId(),
                session.getStatus(),
                session.getAgentId(),
                session.getCustomerId(),
                session.getStartTime(),
                session.getEndTime()
        );
    }

    /** 会话信息 */
    public record SessionInfo(
            String sessionNo,
            String tenantCode,
            Long sessionId,
            Integer status,
            Long agentId,
            Long customerId,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
    }

    /** 消息落库请求体 */
    public record AppendBody(
            @NotBlank(message = "缺少租户编码")
            @Size(max = 32)
            String tenantCode,

            Integer senderType,
            Long senderId,
            Integer msgType,

            @Size(max = 4000, message = "单条消息最长 4000 字")
            String content
    ) {
    }

    /** 接入会话请求体 */
    public record AssignBody(
            @NotBlank(message = "缺少租户编码")
            @Size(max = 32)
            String tenantCode,

            @NotNull(message = "缺少坐席 ID")
            Long agentId
    ) {
    }

    /** 结束会话请求体 */
    public record CloseBody(
            @NotBlank(message = "缺少租户编码")
            @Size(max = 32)
            String tenantCode
    ) {
    }
}
