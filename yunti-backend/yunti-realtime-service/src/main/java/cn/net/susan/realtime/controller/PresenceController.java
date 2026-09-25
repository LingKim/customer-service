package cn.net.susan.realtime.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.realtime.security.RealtimePrincipal;
import cn.net.susan.realtime.security.RealtimeTokenParser;
import cn.net.susan.realtime.ws.ConnectionRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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

    public PresenceController(ConnectionRegistry registry, RealtimeTokenParser tokenParser) {
        this.registry = registry;
        this.tokenParser = tokenParser;
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
}
