package cn.net.susan.user.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.api.ResultCode;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.user.security.JwtService;
import cn.net.susan.user.entity.SysUser;
import cn.net.susan.user.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 内部接口：仅供 tenant-service 审核通过后回填用户租户编码。
 */
@RestController
@RequestMapping("/api/user/internal")
public class UserInternalController {

    private final SysUserMapper sysUserMapper;
    private final JwtService jwtService;

    public UserInternalController(SysUserMapper sysUserMapper, JwtService jwtService) {
        this.sysUserMapper = sysUserMapper;
        this.jwtService = jwtService;
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
     * 请求体。
     */
    public record BackfillBody(
            @NotBlank(message = "租户编码不能为空")
            String tenantCode
    ) {
    }
}
