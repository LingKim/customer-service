package cn.net.susan.user.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.user.entity.SysUser;
import cn.net.susan.user.enums.UserStatus;
import cn.net.susan.user.enums.UserType;
import cn.net.susan.user.mapper.SysUserMapper;
import cn.net.susan.user.security.JwtService;
import cn.net.susan.user.service.MemberInviteService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 企业客服成员：成员列表 / 邀请管理 / 公共接受邀请。
 */
@RestController
@RequestMapping("/api/user/members")
public class MemberInviteController {

    private final MemberInviteService memberService;
    private final JwtService jwtService;
    private final SysUserMapper sysUserMapper;

    public MemberInviteController(
            MemberInviteService memberService,
            JwtService jwtService,
            SysUserMapper sysUserMapper
    ) {
        this.memberService = memberService;
        this.jwtService = jwtService;
        this.sysUserMapper = sysUserMapper;
    }

    /**
     * 可邀请角色。
     */
    @GetMapping("/roles")
    public ApiResponse<List<MemberInviteService.RoleOption>> roles() {
        return ApiResponse.ok(memberService.roleOptions());
    }

    /**
     * 同事列表（转接会话选人用，任何企业成员都能看）。
     */
    @GetMapping("/colleagues")
    public ApiResponse<List<MemberInviteService.ColleagueVO>> colleagues(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(memberService.colleagues(requireEnterpriseLogin(authorization)));
    }

    /**
     * 当前企业成员访问信息（是否可管理成员 + 当前角色）。
     */
    @GetMapping("/access")
    public ApiResponse<MemberInviteService.MemberAccessVO> access(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = requireEnterpriseLogin(authorization);
        return ApiResponse.ok(memberService.memberAccess(user));
    }

    /**
     * 企业成员列表。
     */
    @GetMapping
    public ApiResponse<List<MemberInviteService.MemberVO>> members(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = requireManager(authorization);
        return ApiResponse.ok(memberService.listMembers(user));
    }

    /**
     * 邀请记录列表。
     */
    @GetMapping("/invites")
    public ApiResponse<List<MemberInviteService.InviteVO>> invites(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = requireManager(authorization);
        return ApiResponse.ok(memberService.listInvites(user));
    }

    /**
     * 创建邀请。
     */
    @PostMapping("/invites")
    public ApiResponse<MemberInviteService.CreateInviteResult> createInvite(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateInviteRequest request
    ) {
        LoginUser user = requireManager(authorization);
        return ApiResponse.ok(memberService.create(
                user,
                request.phone(),
                request.email(),
                request.roleCode(),
                request.expireDays()
        ));
    }

    /**
     * 重新发送邀请邮件。
     */
    @PostMapping("/invites/{inviteCode}/resend")
    public ApiResponse<Boolean> resend(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String inviteCode
    ) {
        LoginUser user = requireManager(authorization);
        return ApiResponse.ok(memberService.resend(user, inviteCode));
    }

    /**
     * 撤销邀请。
     */
    @PostMapping("/invites/{inviteCode}/revoke")
    public ApiResponse<Void> revoke(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String inviteCode
    ) {
        LoginUser user = requireManager(authorization);
        memberService.revoke(user, inviteCode);
        return ApiResponse.ok();
    }

    /**
     * 公共邀请预览（被邀请人打开链接时调用）。
     */
    @GetMapping("/invites/{code}/preview")
    public ApiResponse<MemberInviteService.InvitePreviewVO> preview(@PathVariable String code) {
        return ApiResponse.ok(memberService.preview(code));
    }

    /**
     * 公共接受邀请（创建企业账号并加入团队）。
     */
    @PostMapping("/invites/{code}/accept")
    public ApiResponse<MemberInviteService.AcceptResult> accept(
            @PathVariable String code,
            @Valid @RequestBody AcceptInviteRequest request
    ) {
        return ApiResponse.ok(memberService.accept(
                code,
                request.name(),
                request.password(),
                request.phone(),
                request.email()
        ));
    }

    private LoginUser requireEnterpriseLogin(String authorization) {
        LoginUser loginUser = parseBearer(authorization);
        if (loginUser.userType() != UserType.ENTERPRISE.getCode()) {
            throw new BizException(40301, "仅企业成员可管理客服成员");
        }
        SysUser user = sysUserMapper.selectById(loginUser.userId());
        if (user == null || Boolean.TRUE.equals(user.getDeleted())
                || user.getStatus() == null || user.getStatus() != UserStatus.NORMAL.getCode()) {
            throw new BizException(40301, "账号不存在或已被停用");
        }
        if (!Objects.equals(user.getTenantCode(), loginUser.tenantCode())) {
            throw new BizException(40301, "企业租户已变更，请重新登录");
        }
        return loginUser;
    }

    private LoginUser requireManager(String authorization) {
        LoginUser user = requireEnterpriseLogin(authorization);
        MemberInviteService.MemberAccessVO access = memberService.memberAccess(user);
        if (!access.canManage()) {
            throw new BizException(40302, "仅企业管理员可管理客服成员");
        }
        return user;
    }

    private LoginUser parseBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BizException(40301, "未登录或登录已过期");
        }
        return jwtService.parseToken(authorization.substring(7));
    }

    /**
     * 创建邀请请求体。
     */
    public record CreateInviteRequest(
            @Size(max = 20, message = "手机号最长 20 个字符")
            @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
            String phone,

            @NotBlank(message = "请填写受邀邮箱")
            @Size(max = 128, message = "邮箱最长 128 个字符")
            @Email(message = "邮箱格式不正确")
            String email,

            @NotBlank(message = "请选择角色")
            String roleCode,

            @Min(value = 1, message = "有效期不能少于 1 天")
            @Max(value = 30, message = "有效期不能超过 30 天")
            Integer expireDays
    ) {
    }

    /**
     * 接受邀请请求体。
     */
    public record AcceptInviteRequest(
            @NotBlank(message = "请填写姓名")
            @Size(max = 64, message = "姓名最长 64 个字符")
            String name,

            @NotBlank(message = "请设置登录密码")
            @Pattern(
                    regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                    message = "密码需不少于 8 位，且同时包含字母和数字"
            )
            String password,

            @Size(max = 20, message = "手机号最长 20 个字符")
            @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
            String phone,

            @Size(max = 128, message = "邮箱最长 128 个字符")
            @Email(message = "邮箱格式不正确")
            String email
    ) {
    }
}
