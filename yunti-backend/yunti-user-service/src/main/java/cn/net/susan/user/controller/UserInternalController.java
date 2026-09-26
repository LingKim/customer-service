package cn.net.susan.user.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.api.ResultCode;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.user.security.JwtService;
import cn.net.susan.user.entity.SysUser;
import cn.net.susan.user.mapper.SysUserMapper;
import cn.net.susan.user.service.MemberInviteService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 内部接口：仅供 tenant-service 审核通过后回填用户租户编码。
 */
@RestController
@RequestMapping("/api/user/internal")
public class UserInternalController {

    private final SysUserMapper sysUserMapper;
    private final JwtService jwtService;
    private final MemberInviteService memberInviteService;
    private final String internalSharedSecret;

    public UserInternalController(SysUserMapper sysUserMapper, JwtService jwtService,
                                  MemberInviteService memberInviteService,
                                  @Value("${yunti.internal.shared-secret:}") String internalSharedSecret) {
        this.sysUserMapper = sysUserMapper;
        this.jwtService = jwtService;
        this.memberInviteService = memberInviteService;
        this.internalSharedSecret = internalSharedSecret;
    }

    /**
     * 回填用户主归属租户编码。
     */
    @PutMapping("/users/{userId}/tenant-code")
    public ApiResponse<Void> updateTenantCode(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long userId,
            @Valid @RequestBody BackfillBody body
    ) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        LoginUser operator = jwtService.parseToken(authorization.substring(7).trim());
        SysUser admin = sysUserMapper.selectById(operator.userId());
        if (operator.userType() != 1 || admin == null || Boolean.TRUE.equals(admin.getDeleted())
                || admin.getUserType() != 1 || admin.getStatus() != 1) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        SysUser target = sysUserMapper.selectById(userId);
        if (target == null || Boolean.TRUE.equals(target.getDeleted())) {
            throw new BizException(40401, "用户不存在");
        }
        if (!"PLATFORM".equals(target.getTenantCode()) && !body.tenantCode().equals(target.getTenantCode())) {
            throw new BizException(40001, "用户已归属其他租户");
        }
        LambdaUpdateWrapper<SysUser> update = Wrappers.lambdaUpdate(SysUser.class)
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, false)
                .in(SysUser::getTenantCode, "PLATFORM", body.tenantCode())
                .set(SysUser::getTenantCode, body.tenantCode())
                .set(SysUser::getUpdateTime, LocalDateTime.now());
        if (sysUserMapper.update(null, update) != 1) {
            throw new BizException(40001, "用户租户归属已发生变化");
        }
        return ApiResponse.ok();
    }

    /**
     * 查询用户在某租户下的角色编码（内部接口：customer-service 判工单权限用）。
     *
     * <p>走内部接口而不是转发调用方的令牌：服务间调用不该依赖租户侧的登录态，
     * 也避免把用户令牌在多个服务之间传来传去。</p>
     */
    @GetMapping("/users/{userId}/roles")
    public ApiResponse<List<String>> userRoles(
            @RequestHeader(value = "X-Yunti-Internal-Secret", required = false) String internalSecret,
            @PathVariable long userId,
            @RequestParam String tenantCode
    ) {
        if (internalSharedSecret == null || internalSharedSecret.isBlank() || internalSecret == null
                || !MessageDigest.isEqual(internalSharedSecret.getBytes(StandardCharsets.UTF_8),
                        internalSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        return ApiResponse.ok(memberInviteService.roleCodesOf(tenantCode, userId));
    }

    /**
     * 请求体。
     */
    public record BackfillBody(
            @NotBlank(message = "租户编码不能为空")
            String tenantCode
    ) {
    }

    @GetMapping("/users/names")
    public ApiResponse<Map<String, String>> userNames(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String ids) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        LoginUser requester = jwtService.parseToken(authorization.substring(7).trim());
        if (requester.tenantCode() == null || "PLATFORM".equals(requester.tenantCode())) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        List<Long> userIds;
        try {
            userIds = Arrays.stream(ids.split(","))
                    .map(String::trim).filter(s -> !s.isBlank()).map(Long::parseLong).distinct().toList();
        } catch (NumberFormatException e) {
            throw new BizException(40001, "用户 ID 格式不正确");
        }
        if (userIds.size() > 50) {
            throw new BizException(40001, "一次最多查询 50 位用户");
        }
        if (userIds.isEmpty()) {
            return ApiResponse.ok(Map.of());
        }
        Map<String, String> names = sysUserMapper.selectBatchIds(userIds).stream()
                .filter(u -> !Boolean.TRUE.equals(u.getDeleted()))
                .filter(u -> requester.tenantCode().equals(u.getTenantCode()))
                .collect(Collectors.toMap(u -> String.valueOf(u.getId()),
                        u -> u.getName() == null ? "" : u.getName(), (a, b) -> a));
        return ApiResponse.ok(names);
    }
}
