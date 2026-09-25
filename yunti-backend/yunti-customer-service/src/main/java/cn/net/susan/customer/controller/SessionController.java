package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.SessionService;
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

    public SessionController(SessionService sessionService, JwtTokenParser jwtTokenParser) {
        this.sessionService = sessionService;
        this.jwtTokenParser = jwtTokenParser;
    }

    /**
     * 访客打开会话：前端用渠道密钥调用，无需登录，返回访客令牌。
     */
    @PostMapping("/open")
    public ApiResponse<SessionService.OpenResult> open(@Valid @RequestBody OpenBody body) {
        return ApiResponse.ok(sessionService.openSession(
                new SessionService.OpenCommand(body.appKey(), body.visitorKey(), body.visitorName())));
    }

    /**
     * 坐席工作台：会话列表。
     */
    @GetMapping
    public ApiResponse<List<SessionService.SessionVO>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean mine
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(sessionService.agentSessions(user, status, keyword, mine ? user.userId() : null));
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
            @RequestParam(required = false) Integer limit
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(sessionService.history(user, sessionNo, beforeId, limit));
    }

    /**
     * 访客开会话请求体。
     */
    public record OpenBody(
            @NotBlank(message = "缺少渠道密钥")
            @Size(max = 64)
            String appKey,

            @Size(max = 32)
            String visitorKey,

            @Size(max = 64)
            String visitorName
    ) {
    }
}
