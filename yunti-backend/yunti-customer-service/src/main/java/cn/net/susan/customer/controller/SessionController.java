package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.security.OpenSessionRateLimiter;
import cn.net.susan.customer.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 在线客服会话接口：访客开会话 + 坐席工作台读取会话与聊天记录。
 */
@RestController
@RequestMapping("/api/customer/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final JwtTokenParser jwtTokenParser;
    private final OpenSessionRateLimiter openSessionRateLimiter;

    public SessionController(
            SessionService sessionService,
            JwtTokenParser jwtTokenParser,
            OpenSessionRateLimiter openSessionRateLimiter
    ) {
        this.sessionService = sessionService;
        this.jwtTokenParser = jwtTokenParser;
        this.openSessionRateLimiter = openSessionRateLimiter;
    }

    /**
     * 访客打开会话：前端用渠道密钥调用，无需登录，返回会话令牌 + 访客身份令牌。
     *
     * <p>来源域名（Origin / Referer）用于渠道白名单校验；客户端 IP 用于开会话限流。</p>
     */
    @PostMapping("/open")
    public ApiResponse<SessionService.OpenResult> open(
            @Valid @RequestBody OpenBody body,
            HttpServletRequest request
    ) {
        openSessionRateLimiter.check(body.appKey(), clientIp(request));
        return ApiResponse.ok(sessionService.openSession(new SessionService.OpenCommand(
                body.appKey(),
                body.visitorToken(),
                body.visitorName(),
                requestOrigin(request))));
    }

    /** 来源：优先 Origin，取不到再退回 Referer（老浏览器/同源请求可能没有 Origin）。 */
    private String requestOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            return origin;
        }
        return request.getHeader("Referer");
    }

    /** 客户端 IP：网关转发时优先取 X-Forwarded-For 的第一段。 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 坐席工作台：会话列表。scope 取值：all-全部、queue-待接待、mine-我的会话。
     */
    @GetMapping
    public ApiResponse<List<SessionService.SessionVO>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "all") String scope
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(sessionService.agentSessions(user, scope, keyword));
    }

    /**
     * 工作台顶部计数：我的接待 / 待接待（服务端按固定口径算，不受当前标签页影响）。
     */
    @GetMapping("/summary")
    public ApiResponse<SessionService.WorkloadVO> summary(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(sessionService.workload(user));
    }

    /**
     * 会话详情。
     */
    @GetMapping("/{sessionNo}")
    public ApiResponse<SessionService.SessionVO> detail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(sessionService.sessionDetail(user, sessionNo));
    }

    /**
     * 聊天记录（坐席翻页用，beforeId 传上一页最小消息 ID）。
     */
    @GetMapping("/{sessionNo}/messages")
    public ApiResponse<List<SessionService.MessageVO>> messages(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionNo,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Long afterSeq,
            @RequestParam(required = false) Integer limit
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        if (afterSeq != null) {
            // 断线重连补偿：只要序号大于 afterSeq 的那几条
            return ApiResponse.ok(sessionService.messagesAfter(user, sessionNo, afterSeq, limit));
        }
        return ApiResponse.ok(sessionService.history(user, sessionNo, beforeId, limit));
    }

    /**
     * 会话流转记录（转接 / 分配 / 关闭）。
     */
    @GetMapping("/{sessionNo}/events")
    public ApiResponse<List<SessionService.EventVO>> events(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(sessionService.events(user, sessionNo));
    }

    /**
     * 访客开会话请求体。
     */
    public record OpenBody(
            @NotBlank(message = "缺少渠道密钥")
            @Size(max = 64)
            String appKey,

            /** 访客身份令牌（上次打开时服务端下发的，前端本地保存） */
            @Size(max = 1024)
            String visitorToken,

            @Size(max = 64)
            String visitorName
    ) {
    }
}
