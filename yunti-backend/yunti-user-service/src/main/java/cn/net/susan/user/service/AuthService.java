package cn.net.susan.user.service;

import cn.net.susan.common.api.ResultCode;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.user.constant.AuthConstants;
import cn.net.susan.user.dto.LoginRequest;
import cn.net.susan.user.dto.LoginResponse;
import cn.net.susan.user.dto.RegisterRequest;
import cn.net.susan.user.dto.RegisterResponse;
import cn.net.susan.user.entity.LoginLog;
import cn.net.susan.user.entity.SysUser;
import cn.net.susan.user.enums.LoginResult;
import cn.net.susan.user.enums.LoginType;
import cn.net.susan.user.enums.UserStatus;
import cn.net.susan.user.enums.UserType;
import cn.net.susan.user.internal.TenantEnterpriseDraftClient;
import cn.net.susan.user.mapper.LoginLogMapper;
import cn.net.susan.user.mapper.SysUserMapper;
import cn.net.susan.user.security.CaptchaStore;
import cn.net.susan.user.security.JwtService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 企业用户注册 / 登录业务。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final DateTimeFormatter USER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SysUserMapper sysUserMapper;
    private final LoginLogMapper loginLogMapper;
    private final TenantEnterpriseDraftClient tenantDraftClient;
    private final CaptchaStore captchaStore;
    private final JwtService jwtService;
    private final SnowflakeIdGenerator idGenerator;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public AuthService(
            SysUserMapper sysUserMapper,
            LoginLogMapper loginLogMapper,
            TenantEnterpriseDraftClient tenantDraftClient,
            CaptchaStore captchaStore,
            JwtService jwtService,
            SnowflakeIdGenerator idGenerator
    ) {
        this.sysUserMapper = sysUserMapper;
        this.loginLogMapper = loginLogMapper;
        this.tenantDraftClient = tenantDraftClient;
        this.captchaStore = captchaStore;
        this.jwtService = jwtService;
        this.idGenerator = idGenerator;
    }

    /**
     * 企业账号注册：先建账号（企业类型、状态正常；审核开通前挂平台租户 PLATFORM），
     * 再落企业草稿；企业草稿失败时补偿回收账号。
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (!captchaStore.verify(request.captchaId(), request.captchaCode())) {
            throw new BizException(40001, "图形验证码错误或已过期");
        }

        String phone = request.phone().trim();
        String email = request.email().trim().toLowerCase();
        if (sysUserMapper.countByPhoneOrEmail(phone, email) > 0) {
            throw new BizException(40002, "该手机号或邮箱已注册，请直接登录");
        }

        long userId = idGenerator.nextId();
        String userNo = generateUserNo();
        SysUser user = SysUser.builder()
                .id(userId)
                .tenantCode(AuthConstants.TENANT_CODE_PLATFORM)
                .userNo(userNo)
                .name(request.contactName().trim())
                .phone(phone)
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .userType(UserType.ENTERPRISE.getCode())
                .status(UserStatus.NORMAL.getCode())
                .creator("SELF_REGISTER")
                .deleted(false)
                .build();
        sysUserMapper.insert(user);

        try {
            TenantEnterpriseDraftClient.DraftResponse draft = tenantDraftClient.createDraft(
                    new TenantEnterpriseDraftClient.DraftRequest(
                            userId,
                            request.companyName().trim(),
                            trimToNull(request.industry()),
                            trimToNull(request.scale()),
                            user.getName(),
                            phone,
                            email
                    )
            );
            return new RegisterResponse(
                    String.valueOf(userId),
                    userNo,
                    user.getName(),
                    phone,
                    email,
                    String.valueOf(draft.enterpriseId()),
                    draft.enterpriseCode(),
                    draft.status(),
                    AuthConstants.TENANT_CODE_PLATFORM,
                    UserType.ENTERPRISE.getCode()
            );
        } catch (Exception e) {
            // 补偿：回收已创建但企业草稿未落库的账号
            softDeleteById(userId);
            log.error("企业草稿创建失败，已回收注册账号 userId={}, userNo={}", userId, userNo, e);
            throw e;
        }
    }

    /**
     * 账号密码登录（支持手机号 / 邮箱 / 用户编号），记录登录日志并签发 JWT。
     */
    public LoginResponse login(LoginRequest request, String ip, String userAgent) {
        if (!captchaStore.verify(request.captchaId(), request.captchaCode())) {
            throw new BizException(40001, "图形验证码错误或已过期");
        }

        LocalDateTime now = LocalDateTime.now();
        SysUser user = sysUserMapper.findByAccount(request.account().trim());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            insertLoginLog(
                    user == null ? AuthConstants.TENANT_CODE_PLATFORM : user.getTenantCode(),
                    user == null ? 0L : user.getId(),
                    ip,
                    userAgent,
                    LoginResult.FAIL.getCode(),
                    now
            );
            throw new BizException(40002, "账号或密码错误");
        }
        if (user.getStatus() != UserStatus.NORMAL.getCode()) {
            throw new BizException(40301, "账号已被停用或锁定，请联系平台运营");
        }

        updateLastLoginTime(user.getId(), now);
        insertLoginLog(user.getTenantCode(), user.getId(), ip, userAgent,
                LoginResult.SUCCESS.getCode(), now);

        String token = jwtService.createToken(new LoginUser(
                user.getId(),
                user.getUserNo(),
                user.getName(),
                user.getUserType(),
                user.getTenantCode()
        ));
        return new LoginResponse(
                token,
                String.valueOf(user.getId()),
                user.getUserNo(),
                user.getName(),
                user.getUserType(),
                user.getTenantCode(),
                user.getAvatar()
        );
    }

    /**
     * 修改当前登录用户密码（需校验原密码）。
     */
    public void changePassword(LoginUser loginUser, String oldPassword, String newPassword) {
        SysUser user = findById(loginUser.userId())
                .orElseThrow(() -> new BizException(ResultCode.UNAUTHORIZED));
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new BizException(40002, "原密码不正确");
        }
        if (oldPassword.equals(newPassword)) {
            throw new BizException(40002, "新密码不能与原密码相同");
        }
        LambdaUpdateWrapper<SysUser> update = Wrappers.lambdaUpdate(SysUser.class)
                .eq(SysUser::getId, user.getId())
                .eq(SysUser::getDeleted, false)
                .set(SysUser::getPassword, passwordEncoder.encode(newPassword))
                .set(SysUser::getUpdateTime, LocalDateTime.now());
        sysUserMapper.update(null, update);
        log.info("用户修改密码成功 userId={}, userNo={}", user.getId(), user.getUserNo());
    }

    /**
     * 按用户 ID 查询（未删除）。
     */
    public Optional<SysUser> findById(long userId) {
        return Optional.ofNullable(sysUserMapper.selectOne(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getId, userId)
                        .eq(SysUser::getDeleted, false)
                        .last("LIMIT 1")
        ));
    }

    private void insertLoginLog(
            String tenantCode,
            long userId,
            String ip,
            String device,
            int result,
            LocalDateTime loginTime
    ) {
        String safeDevice = device == null || device.isBlank()
                ? null
                : device.substring(0, Math.min(device.length(), 128));
        loginLogMapper.insert(LoginLog.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenantCode)
                .userId(userId)
                .loginType(LoginType.PASSWORD.getCode())
                .ip(ip)
                .device(safeDevice)
                .result(result)
                .loginTime(loginTime)
                .build());
    }

    private void updateLastLoginTime(long userId, LocalDateTime loginTime) {
        LambdaUpdateWrapper<SysUser> update = Wrappers.lambdaUpdate(SysUser.class)
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, false)
                .set(SysUser::getLastLoginTime, loginTime)
                .set(SysUser::getUpdateTime, LocalDateTime.now());
        sysUserMapper.update(null, update);
    }

    private void softDeleteById(long userId) {
        LambdaUpdateWrapper<SysUser> update = Wrappers.lambdaUpdate(SysUser.class)
                .eq(SysUser::getId, userId)
                .set(SysUser::getDeleted, true)
                .set(SysUser::getUpdateTime, LocalDateTime.now());
        sysUserMapper.update(null, update);
    }

    private String generateUserNo() {
        return "U" + USER_NO_FORMAT.format(LocalDateTime.now())
                + ThreadLocalRandom.current().nextInt(100, 1000);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
