package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.ChannelOnboardingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/customer/channels")
public class ChannelOnboardingController {
    private final ChannelOnboardingService service;
    private final JwtTokenParser jwtTokenParser;

    public ChannelOnboardingController(ChannelOnboardingService service, JwtTokenParser jwtTokenParser) {
        this.service = service;
        this.jwtTokenParser = jwtTokenParser;
    }

    @PostMapping
    public ApiResponse<ChannelOnboardingService.ChannelResult> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateBody body) {
        return ApiResponse.ok(service.create(jwtTokenParser.requireLoginUser(authorization),
                body.channelType(), body.name(), body.desc()));
    }

    @GetMapping
    public ApiResponse<List<ChannelOnboardingService.ChannelResult>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(service.list(user, authorization));
    }

    @GetMapping("/{channelId}/key")
    public ApiResponse<KeyResult> revealKey(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long channelId) {
        return ApiResponse.ok(new KeyResult(service.revealKey(jwtTokenParser.requireLoginUser(authorization), channelId)));
    }

    @PostMapping("/{channelId}/key/rotate")
    public ApiResponse<ChannelOnboardingService.ChannelResult> rotateKey(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long channelId) {
        return ApiResponse.ok(service.rotateKey(jwtTokenParser.requireLoginUser(authorization), channelId));
    }

    @PostMapping("/{channelId}/status")
    public ApiResponse<ChannelOnboardingService.ChannelResult> updateStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long channelId,
            @Valid @RequestBody StatusBody body) {
        return ApiResponse.ok(service.updateStatus(jwtTokenParser.requireLoginUser(authorization),
                channelId, body.enabled()));
    }

    public record CreateBody(@Min(1) @Max(3) int channelType,
                             @NotBlank @Size(max = 64) String name,
                             @Size(max = 255) String desc) {}
    public record StatusBody(boolean enabled) {}
    public record KeyResult(String appKey) {}
}
