package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.customer.service.AgentStatusService;
import cn.net.susan.customer.service.RoutingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部接口：仅供 yunti-realtime-service 调用（坐席上下线、触发一次排队调度）。
 */
@RestController
@RequestMapping("/api/customer/internal/agent")
public class AgentInternalController {

    private final AgentStatusService agentStatusService;
    private final RoutingService routingService;
    private final String sharedSecret;

    public AgentInternalController(AgentStatusService agentStatusService, RoutingService routingService,
                                   @Value("${yunti.internal.shared-secret:}") String sharedSecret) {
        this.agentStatusService = agentStatusService;
        this.routingService = routingService;
        this.sharedSecret = sharedSecret;
    }

    /**
     * 坐席长连接上线/下线：路由只把新会话分给"在线且连着"的人，所以这个标记必须实时更新。
     */
    @PostMapping("/connection")
    public ApiResponse<Void> connection(
            @RequestHeader(value = "X-Yunti-Internal-Secret", required = false) String internalSecret,
            @Valid @RequestBody ConnectionBody body) {
        requireInternalSecret(internalSecret);
        agentStatusService.markConnected(body.tenantCode(), body.agentId(), body.connected());
        if (Boolean.TRUE.equals(body.connected())) {
            // 有人上线了，立刻把积压的排队会话分出去
            routingService.assignQueue(body.tenantCode());
        }
        return ApiResponse.ok(null);
    }

    /**
     * 手动触发一次排队调度（排障用）。
     */
    @PostMapping("/routing/run")
    public ApiResponse<Map<String, Object>> runRouting(
            @RequestHeader(value = "X-Yunti-Internal-Secret", required = false) String internalSecret,
            @RequestParam String tenantCode) {
        requireInternalSecret(internalSecret);
        int assigned = routingService.assignQueue(tenantCode);
        return ApiResponse.ok(Map.of("assigned", assigned));
    }

    private void requireInternalSecret(String provided) {
        if (sharedSecret == null || sharedSecret.isBlank() || provided == null || provided.isBlank()
                || !MessageDigest.isEqual(sharedSecret.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(40301, "内部接口未授权");
        }
    }

    /** 上下线请求体 */
    public record ConnectionBody(
            @NotBlank(message = "缺少租户编码")
            @Size(max = 32)
            String tenantCode,

            @NotNull(message = "缺少坐席 ID")
            Long agentId,

            @NotNull(message = "缺少连接状态")
            Boolean connected
    ) {
    }
}
