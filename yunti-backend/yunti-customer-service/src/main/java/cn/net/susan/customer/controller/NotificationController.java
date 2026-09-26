package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 消息中心：右上角铃铛点开的那一栏。
 *
 * <p>企业级消息（工单 SLA 预警）+ 发给我的消息（分派给我的工单）一起返回，
 * 每条带"我读没读"；已读是写在 notification_read 里，不影响别人。</p>
 */
@RestController
@RequestMapping("/api/customer/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final JwtTokenParser jwtTokenParser;

    public NotificationController(NotificationService notificationService,
                                  JwtTokenParser jwtTokenParser) {
        this.notificationService = notificationService;
        this.jwtTokenParser = jwtTokenParser;
    }

    @GetMapping
    public ApiResponse<List<NotificationService.NotificationVO>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer notifyType,
            @RequestParam(required = false) Integer limit
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(notificationService.list(user, notifyType, limit));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(Map.of("count", notificationService.unreadCount(user)));
    }

    @PostMapping("/{notifyId}/read")
    public ApiResponse<Void> markRead(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String notifyId
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        notificationService.markRead(user, notifyId);
        return ApiResponse.ok();
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer notifyType
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(Map.of("count", notificationService.markAllRead(user, notifyType)));
    }
}
