package cn.net.susan.tenant.admin.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.api.ResultCode;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.tenant.admin.dto.ReviewPageVO;
import cn.net.susan.tenant.admin.service.EnterpriseReviewAdminService;
import cn.net.susan.tenant.security.JwtTokenParser;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/tenant/admin/reviews")
public class EnterpriseReviewAdminController {
    private final EnterpriseReviewAdminService service;
    private final JwtTokenParser jwtTokenParser;

    public EnterpriseReviewAdminController(EnterpriseReviewAdminService service, JwtTokenParser jwtTokenParser) {
        this.service = service;
        this.jwtTokenParser = jwtTokenParser;
    }

    @GetMapping
    public ApiResponse<IPage<ReviewPageVO>> page(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") @Min(1) int pageNum,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int pageSize,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        requirePlatform(authorization);
        return ApiResponse.ok(service.page(pageNum, pageSize, status, keyword));
    }

    @GetMapping("/{reviewId}")
    public ApiResponse<ReviewPageVO> detail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long reviewId) {
        requirePlatform(authorization);
        return ApiResponse.ok(service.detail(reviewId));
    }

    @PostMapping("/{reviewId}/approve")
    public ApiResponse<ReviewPageVO> approve(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long reviewId) {
        LoginUser user = requirePlatform(authorization);
        return ApiResponse.ok(service.approve(reviewId, user.userId(), authorization));
    }

    @PostMapping("/{reviewId}/reject")
    public ApiResponse<ReviewPageVO> reject(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long reviewId,
            @Valid @RequestBody RejectBody body) {
        LoginUser user = requirePlatform(authorization);
        return ApiResponse.ok(service.reject(reviewId, user.userId(), body.reason()));
    }

    private LoginUser requirePlatform(String authorization) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        if (user.userType() != 1) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        return user;
    }

    public record RejectBody(@NotBlank @Size(max = 512) String reason) {}
}
