package cn.net.susan.user.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.user.entity.SysUser;
import cn.net.susan.user.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    public UserInternalController(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    /**
     * 回填用户主归属租户编码。
     */
    @PutMapping("/users/{userId}/tenant-code")
    public ApiResponse<Void> updateTenantCode(
            @PathVariable long userId,
            @RequestBody BackfillBody body
    ) {
        LambdaUpdateWrapper<SysUser> update = Wrappers.lambdaUpdate(SysUser.class)
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, false)
                .set(SysUser::getTenantCode, body.tenantCode())
                .set(SysUser::getUpdateTime, LocalDateTime.now());
        sysUserMapper.update(null, update);
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
