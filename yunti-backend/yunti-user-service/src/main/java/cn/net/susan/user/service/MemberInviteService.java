package cn.net.susan.user.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.user.constant.AuthConstants;
import cn.net.susan.user.entity.MemberInvite;
import cn.net.susan.user.entity.SysRole;
import cn.net.susan.user.entity.SysUser;
import cn.net.susan.user.entity.SysUserRole;
import cn.net.susan.user.entity.TenantMember;
import cn.net.susan.user.enums.MemberRole;
import cn.net.susan.user.enums.UserStatus;
import cn.net.susan.user.enums.UserType;
import cn.net.susan.user.mapper.MemberInviteMapper;
import cn.net.susan.user.mapper.SysRoleMapper;
import cn.net.susan.user.mapper.SysUserMapper;
import cn.net.susan.user.mapper.SysUserRoleMapper;
import cn.net.susan.user.mapper.TenantMemberMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 企业客服成员邀请：创建邀请、成员/邀请列表、邀请预览、接受邀请。
 */
@Service
public class MemberInviteService {

    private static final Logger log = LoggerFactory.getLogger(MemberInviteService.class);

    /** 邀请状态码：1-有效、2-已使用、3-已失效 */
    private static final int INVITE_VALID = 1;
    private static final int INVITE_USED = 2;
    private static final int INVITE_EXPIRED = 3;

    private static final String INVITE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter USER_NO_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final SysUserMapper sysUserMapper;
    private final TenantMemberMapper tenantMemberMapper;
    private final MemberInviteMapper memberInviteMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final MemberMailService memberMailService;
    private final SnowflakeIdGenerator idGenerator;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public MemberInviteService(
            SysUserMapper sysUserMapper,
            TenantMemberMapper tenantMemberMapper,
            MemberInviteMapper memberInviteMapper,
            SysRoleMapper sysRoleMapper,
            SysUserRoleMapper sysUserRoleMapper,
            MemberMailService memberMailService,
            SnowflakeIdGenerator idGenerator
    ) {
        this.sysUserMapper = sysUserMapper;
        this.tenantMemberMapper = tenantMemberMapper;
        this.memberInviteMapper = memberInviteMapper;
        this.sysRoleMapper = sysRoleMapper;
        this.sysUserRoleMapper = sysUserRoleMapper;
        this.memberMailService = memberMailService;
        this.idGenerator = idGenerator;
    }

    /**
     * 可邀请角色列表（企业管理员不对外邀请）。
     */
    public List<RoleOption> roleOptions() {
        List<RoleOption> options = new ArrayList<>();
        for (MemberRole role : MemberRole.values()) {
            if (role != MemberRole.ADMIN) {
                options.add(new RoleOption(role.getCode(), role.getName()));
            }
        }
        return options;
    }

    /**
     * 当前企业用户的成员管理权限与角色信息。
     *
     * <p>老企业账号没有角色数据时按“企业管理员”兼容并自动补全；
     * 有角色数据的用户只有 ADMIN 才能管理成员，普通坐席仅能查看自己的角色。</p>
     */
    @Transactional
    public MemberAccessVO memberAccess(LoginUser user) {
        String tenant = tenantOf(user);
        List<SysUserRole> relations = sysUserRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getTenantCode, tenant)
                        .eq(SysUserRole::getUserId, user.userId()));
        if (!relations.isEmpty()) {
            List<SysRole> roles = sysRoleMapper.selectBatchIds(
                    relations.stream().map(SysUserRole::getRoleId).distinct().toList());
            boolean isAdmin = roles.stream().anyMatch(r -> Boolean.FALSE.equals(r.getDeleted())
                    && Boolean.TRUE.equals(r.getIsEnabled())
                    && MemberRole.ADMIN.getCode().equals(r.getRoleCode()));
            SysRole first = roles.stream()
                    .filter(r -> Boolean.FALSE.equals(r.getDeleted()) && Boolean.TRUE.equals(r.getIsEnabled()))
                    .findFirst()
                    .orElse(null);
            if (isAdmin) {
                SysRole admin = roles.stream()
                        .filter(r -> MemberRole.ADMIN.getCode().equals(r.getRoleCode()))
                        .findFirst()
                        .orElse(first);
                return new MemberAccessVO(true, admin.getRoleCode(), admin.getRoleName());
            }
            return new MemberAccessVO(
                    false,
                    first == null ? null : first.getRoleCode(),
                    first == null ? "客服成员" : first.getRoleName());
        }

        // 老账号兼容：历史注册的企业账号没有角色记录，按企业管理员补全
        SysRole admin = ensureRole(tenant, MemberRole.ADMIN);
        bindRole(user, tenant, admin);
        bindMember(user, tenant);
        return new MemberAccessVO(true, admin.getRoleCode(), admin.getRoleName());
    }

    /**
     * 企业成员列表；返回前补全当前管理员的默认角色/成员关系，保证列表口径完整。
     */
    @Transactional
    public List<MemberVO> listMembers(LoginUser user) {
        String tenant = tenantOf(user);
        ensureAdminMembership(user, tenant);
        List<Map<String, Object>> rows = sysUserMapper.selectEnterpriseMembers(tenant);
        return rows.stream().map(this::toMemberVO).toList();
    }

    /**
     * 邀请记录列表。
     */
    public List<InviteVO> listInvites(LoginUser user) {
        String tenant = tenantOf(user);
        expireOverdue(tenant);
        return memberInviteMapper.selectInviteList(tenant).stream().map(this::toInviteVO).toList();
    }

    /**
     * 创建邀请：落一条 member_invite，返回邀请码（前端拼链接发送给被邀人）。
     */
    @Transactional
    public CreateInviteResult create(
            LoginUser user,
            String phone,
            String email,
            String roleCode,
            Integer expireDays
    ) {
        String tenant = tenantOf(user);
        MemberRole role = MemberRole.fromCode(roleCode);
        if (role == null || role == MemberRole.ADMIN) {
            throw new BizException(40001, "请选择有效的成员角色");
        }
        phone = normalizePhone(phone);
        email = normalizeEmail(email);
        if (email == null) {
            throw new BizException(40001, "请填写被邀请人的邮箱，系统将通过邮件发送邀请");
        }
        if (sysUserMapper.countByPhoneOrEmail(phone == null ? "" : phone, email == null ? "" : email) > 0) {
            throw new BizException(40002, "该手机号或邮箱已注册其他账号，请直接让对方登录或在个人中心切换企业");
        }

        SysRole sysRole = ensureRole(tenant, role);
        LocalDateTime now = LocalDateTime.now();
        int days = expireDays == null ? 7 : Math.min(Math.max(expireDays, 1), 30);
        MemberInvite invite = MemberInvite.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .inviteCode(generateInviteCode(tenant))
                .inviterId(user.userId())
                .roleId(sysRole.getId())
                .inviteePhone(phone)
                .inviteeEmail(email)
                .status(INVITE_VALID)
                .expireTime(now.plusDays(days))
                .creator(String.valueOf(user.userId()))
                .deleted(false)
                .build();
        memberInviteMapper.insert(invite);
        log.info("企业成员邀请已创建 tenant={}, inviteId={}, inviterId={}", tenant, invite.getId(), user.userId());
        boolean emailSent = memberMailService.sendInvitationEmail(
                invite.getInviteeEmail(),
                invite.getInviteCode(),
                user.name(),
                role.getName(),
                invite.getExpireTime()
        );
        return new CreateInviteResult(
                String.valueOf(invite.getId()),
                invite.getInviteCode(),
                role.getCode(),
                role.getName(),
                invite.getExpireTime(),
                phone,
                email,
                emailSent
        );
    }

    /**
     * 重新发送邀请邮件（仅有效邀请）。
     */
    @Transactional
    public boolean resend(LoginUser user, String inviteCode) {
        String tenant = tenantOf(user);
        MemberInvite invite = memberInviteMapper.selectOne(
                Wrappers.<MemberInvite>lambdaQuery()
                        .eq(MemberInvite::getTenantCode, tenant)
                        .eq(MemberInvite::getInviteCode, inviteCode)
                        .eq(MemberInvite::getDeleted, false)
                        .last("LIMIT 1"));
        if (invite == null) {
            throw new BizException(40401, "邀请记录不存在");
        }
        if (invite.getStatus() != INVITE_VALID) {
            throw new BizException(40002, "该邀请已使用或已失效，无法重发");
        }
        if (invite.getExpireTime() == null || invite.getExpireTime().isBefore(LocalDateTime.now())) {
            markExpired(invite);
            throw new BizException(41001, "邀请已过期，请重新发起邀请");
        }
        if (invite.getInviteeEmail() == null || invite.getInviteeEmail().isBlank()) {
            throw new BizException(40001, "该邀请未绑定邮箱，无法重发邮件");
        }
        SysRole role = sysRoleMapper.selectById(invite.getRoleId());
        SysUser inviter = sysUserMapper.selectById(invite.getInviterId());
        return memberMailService.sendInvitationEmail(
                invite.getInviteeEmail(),
                invite.getInviteCode(),
                inviter == null ? "企业管理员" : inviter.getName(),
                role == null ? "客服成员" : role.getRoleName(),
                invite.getExpireTime()
        );
    }

    /**
     * 撤销邀请（仅有效邀请可撤销）。
     */
    @Transactional
    public void revoke(LoginUser user, String inviteCode) {
        String tenant = tenantOf(user);
        MemberInvite invite = memberInviteMapper.selectOne(
                Wrappers.<MemberInvite>lambdaQuery()
                        .eq(MemberInvite::getTenantCode, tenant)
                        .eq(MemberInvite::getInviteCode, inviteCode)
                        .eq(MemberInvite::getDeleted, false)
                        .last("LIMIT 1"));
        if (invite == null) {
            throw new BizException(40401, "邀请记录不存在");
        }
        if (invite.getStatus() != INVITE_VALID) {
            throw new BizException(40002, "该邀请已使用或已失效，无需撤销");
        }
        invite.setStatus(INVITE_EXPIRED);
        invite.setEditor(String.valueOf(user.userId()));
        invite.setUpdateTime(LocalDateTime.now());
        memberInviteMapper.updateById(invite);
    }

    /**
     * 公共邀请预览（未登录可用）。
     */
    public InvitePreviewVO preview(String code) {
        MemberInvite invite = validInvite(code);
        if (invite == null || invite.getExpireTime() == null
                || invite.getExpireTime().isBefore(LocalDateTime.now())) {
            markExpired(invite);
            throw new BizException(41001, "邀请链接已失效，请联系企业管理员重新邀请");
        }
        SysRole role = sysRoleMapper.selectById(invite.getRoleId());
        SysUser inviter = sysUserMapper.selectById(invite.getInviterId());
        return new InvitePreviewVO(
                invite.getInviteCode(),
                role == null ? null : role.getRoleCode(),
                role == null ? "客服成员" : role.getRoleName(),
                inviter == null ? null : inviter.getName(),
                maskPhone(invite.getInviteePhone()),
                maskEmail(invite.getInviteeEmail()),
                invite.getExpireTime()
        );
    }

    /**
     * 接受邀请：创建企业账号 + 成员关系 + 角色绑定，并置邀请为已使用。
     */
    @Transactional
    public AcceptResult accept(String code, String name, String password, String phone, String email) {
        MemberInvite invite = memberInviteMapper.selectValidForUpdate(code);
        if (invite == null) {
            throw new BizException(41001, "邀请链接不存在或已被撤销");
        }
        LocalDateTime now = LocalDateTime.now();
        if (invite.getExpireTime() == null || invite.getExpireTime().isBefore(now)) {
            markExpired(invite);
            throw new BizException(41001, "邀请链接已过期，请联系企业管理员重新邀请");
        }
        phone = normalizePhone(phone);
        email = normalizeEmail(email);
        if (invite.getInviteePhone() != null && !invite.getInviteePhone().equals(phone)) {
            throw new BizException(40002, "手机号与邀请时填写的号码不一致");
        }
        if (invite.getInviteeEmail() != null && !invite.getInviteeEmail().equalsIgnoreCase(email)) {
            throw new BizException(40002, "邮箱与邀请时填写的邮箱不一致");
        }
        if (phone == null && email == null) {
            throw new BizException(40001, "请填写可用于登录的手机号或邮箱");
        }
        if (sysUserMapper.countByPhoneOrEmail(phone == null ? "" : phone, email == null ? "" : email) > 0) {
            throw new BizException(40002, "该手机号或邮箱已注册，请直接登录");
        }

        MemberRole role = MemberRole.fromCode(
                sysRoleMapper.selectById(invite.getRoleId()) == null
                        ? null
                        : sysRoleMapper.selectById(invite.getRoleId()).getRoleCode());
        if (role == null) {
            throw new BizException(40002, "邀请对应的角色已被删除，请联系企业管理员重新邀请");
        }
        SysRole sysRole = ensureRole(invite.getTenantCode(), role);
        String userNo = generateUniqueUserNo();
        long userId = idGenerator.nextId();
        SysUser member = SysUser.builder()
                .id(userId)
                .tenantCode(invite.getTenantCode())
                .userNo(userNo)
                .name(name == null ? "" : name.trim())
                .phone(phone)
                .email(email)
                .password(passwordEncoder.encode(password))
                .userType(UserType.ENTERPRISE.getCode())
                .status(UserStatus.NORMAL.getCode())
                .creator(String.valueOf(invite.getInviterId()))
                .deleted(false)
                .build();
        sysUserMapper.insert(member);

        tenantMemberMapper.insert(TenantMember.builder()
                .id(idGenerator.nextId())
                .tenantCode(invite.getTenantCode())
                .userId(userId)
                .joinTime(now)
                .status(1)
                .creator(String.valueOf(invite.getInviterId()))
                .deleted(false)
                .build());

        sysUserRoleMapper.insert(SysUserRole.builder()
                .id(idGenerator.nextId())
                .tenantCode(invite.getTenantCode())
                .userId(userId)
                .roleId(sysRole.getId())
                .createTime(now)
                .build());

        invite.setStatus(INVITE_USED);
        invite.setUsedBy(userId);
        invite.setUsedTime(now);
        invite.setEditor(String.valueOf(invite.getInviterId()));
        invite.setUpdateTime(now);
        memberInviteMapper.updateById(invite);

        log.info("企业成员已接受邀请 tenant={}, userId={}, userNo={}", invite.getTenantCode(), userId, userNo);
        return new AcceptResult(
                String.valueOf(userId),
                userNo,
                member.getName(),
                phone,
                email,
                sysRole.getRoleCode(),
                sysRole.getRoleName(),
                invite.getTenantCode()
        );
    }

    /**
     * 补全当前管理员的角色与成员关系（老账号没有角色数据时的兼容处理）。
     */
    private void ensureAdminMembership(LoginUser user, String tenant) {
        SysRole admin = ensureRole(tenant, MemberRole.ADMIN);
        bindRole(user, tenant, admin);
        bindMember(user, tenant);
    }

    private void bindRole(LoginUser user, String tenant, SysRole role) {
        long countRole = sysUserRoleMapper.selectCount(
                Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getTenantCode, tenant)
                        .eq(SysUserRole::getUserId, user.userId())
                        .eq(SysUserRole::getRoleId, role.getId()));
        if (countRole == 0) {
            sysUserRoleMapper.insertIgnore(SysUserRole.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .userId(user.userId())
                    .roleId(role.getId())
                    .createTime(LocalDateTime.now())
                    .build());
        }
    }

    private void bindMember(LoginUser user, String tenant) {
        long countMember = tenantMemberMapper.selectCount(
                Wrappers.<TenantMember>lambdaQuery()
                        .eq(TenantMember::getTenantCode, tenant)
                        .eq(TenantMember::getUserId, user.userId())
                        .eq(TenantMember::getStatus, 1)
                        .eq(TenantMember::getDeleted, false));
        if (countMember == 0) {
            tenantMemberMapper.insertIgnore(TenantMember.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .userId(user.userId())
                    .joinTime(LocalDateTime.now())
                    .status(1)
                    .creator(String.valueOf(user.userId()))
                    .deleted(false)
                    .build());
        }
    }

    /**
     * 查询或创建企业角色（幂等，内置角色不重复插入）。
     */
    private SysRole ensureRole(String tenant, MemberRole role) {
        SysRole exists = sysRoleMapper.selectOne(
                Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getTenantCode, tenant)
                        .eq(SysRole::getRoleCode, role.getCode())
                        .eq(SysRole::getDeleted, false)
                        .last("LIMIT 1"));
        if (exists != null) {
            return exists;
        }
        SysRole created = SysRole.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .roleCode(role.getCode())
                .roleName(role.getName())
                .roleType(2)
                .isFixed(true)
                .isEnabled(true)
                .deleted(false)
                .build();
        sysRoleMapper.insertIgnore(created);
        SysRole saved = sysRoleMapper.selectOne(
                Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getTenantCode, tenant)
                        .eq(SysRole::getRoleCode, role.getCode())
                        .eq(SysRole::getDeleted, false)
                        .last("LIMIT 1"));
        if (saved == null) {
            throw new BizException(50001, "企业角色初始化失败，请稍后重试");
        }
        return saved;
    }

    private MemberInvite validInvite(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return memberInviteMapper.selectOne(
                Wrappers.<MemberInvite>lambdaQuery()
                        .eq(MemberInvite::getInviteCode, code.trim())
                        .eq(MemberInvite::getStatus, INVITE_VALID)
                        .eq(MemberInvite::getDeleted, false)
                        .last("LIMIT 1"));
    }

    private void markExpired(MemberInvite invite) {
        if (invite == null || invite.getStatus() != INVITE_VALID) {
            return;
        }
        invite.setStatus(INVITE_EXPIRED);
        invite.setUpdateTime(LocalDateTime.now());
        memberInviteMapper.updateById(invite);
    }

    /**
     * 列表时兜底把已过期但状态仍为“有效”的记录置为失效。
     */
    private void expireOverdue(String tenant) {
        List<MemberInvite> overdue = memberInviteMapper.selectList(
                Wrappers.<MemberInvite>lambdaQuery()
                        .eq(MemberInvite::getTenantCode, tenant)
                        .eq(MemberInvite::getStatus, INVITE_VALID)
                        .eq(MemberInvite::getDeleted, false)
                        .lt(MemberInvite::getExpireTime, LocalDateTime.now()));
        for (MemberInvite invite : overdue) {
            invite.setStatus(INVITE_EXPIRED);
            invite.setUpdateTime(LocalDateTime.now());
            memberInviteMapper.updateById(invite);
        }
    }

    private String generateInviteCode(String tenant) {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder sb = new StringBuilder(12);
            sb.append("INV");
            for (int i = 0; i < 9; i++) {
                sb.append(INVITE_CHARS.charAt(RANDOM.nextInt(INVITE_CHARS.length())));
            }
            String code = sb.toString();
            long count = memberInviteMapper.selectCount(
                    Wrappers.<MemberInvite>lambdaQuery()
                            .eq(MemberInvite::getInviteCode, code));
            if (count == 0) {
                return code;
            }
        }
        throw new BizException(50001, "邀请码生成失败，请稍后重试");
    }

    private String generateUniqueUserNo() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String userNo = "U" + USER_NO_FORMAT.format(LocalDateTime.now())
                    + ThreadLocalRandom.current().nextInt(1000, 10000);
            if (sysUserMapper.findByAccount(userNo) == null) {
                return userNo;
            }
        }
        throw new BizException(50001, "用户编号生成失败，请稍后重试");
    }

    private MemberVO toMemberVO(Map<String, Object> row) {
        return new MemberVO(
                stringValue(row.get("user_id")),
                stringValue(row.get("user_no")),
                stringValue(row.get("member_name")),
                stringValue(row.get("phone")),
                stringValue(row.get("email")),
                stringValue(row.get("avatar")),
                stringValue(row.get("role_code")),
                stringValue(row.get("role_name")),
                integerValue(row.get("user_status")),
                dateValue(row.get("last_login_time")),
                dateValue(row.get("join_time"))
        );
    }

    private InviteVO toInviteVO(Map<String, Object> row) {
        int status = integerValue(row.get("invite_status"));
        return new InviteVO(
                stringValue(row.get("invite_id")),
                stringValue(row.get("invite_code")),
                stringValue(row.get("invitee_phone")),
                stringValue(row.get("invitee_email")),
                stringValue(row.get("role_code")),
                stringValue(row.get("role_name")),
                stringValue(row.get("inviter_name")),
                stringValue(row.get("used_by_name")),
                status,
                statusText(status),
                dateValue(row.get("expire_time")),
                dateValue(row.get("create_time"))
        );
    }

    private String statusText(int status) {
        return switch (status) {
            case 1 -> "待接受";
            case 2 -> "已加入";
            case 3 -> "已失效";
            default -> "未知";
        };
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != UserType.ENTERPRISE.getCode()) {
            throw new BizException(40301, "仅企业成员可管理客服成员");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || AuthConstants.TENANT_CODE_PLATFORM.equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再邀请客服成员");
        }
        return tenant;
    }

    private String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String value = phone.trim();
        return value.isEmpty() ? null : value;
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase();
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone == null ? "" : phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return email == null ? "" : email;
        }
        String[] parts = email.split("@");
        String head = parts[0];
        String maskedHead = head.length() <= 2
                ? head.charAt(0) + "***"
                : head.substring(0, 2) + "***";
        return maskedHead + "@" + parts[1];
    }

    private static int integerValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return 0;
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String dateValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 可邀请角色。
     */
    public record RoleOption(String code, String name) {
    }

    /**
     * 当前成员访问信息。
     */
    public record MemberAccessVO(boolean canManage, String roleCode, String roleName) {
    }

    /**
     * 成员列表项。
     */
    public record MemberVO(
            String userId,
            String userNo,
            String name,
            String phone,
            String email,
            String avatar,
            String roleCode,
            String roleName,
            int status,
            String lastLoginTime,
            String joinTime
    ) {
    }

    /**
     * 邀请记录项。
     */
    public record InviteVO(
            String id,
            String inviteCode,
            String phone,
            String email,
            String roleCode,
            String roleName,
            String inviterName,
            String usedByName,
            int status,
            String statusText,
            String expireTime,
            String createTime
    ) {
    }

    /**
     * 创建邀请结果。
     */
    public record CreateInviteResult(
            String id,
            String inviteCode,
            String roleCode,
            String roleName,
            LocalDateTime expireTime,
            String phone,
            String email,
            boolean emailSent
    ) {
    }

    /**
     * 公共邀请预览。
     */
    public record InvitePreviewVO(
            String inviteCode,
            String roleCode,
            String roleName,
            String inviterName,
            String phoneMasked,
            String emailMasked,
            LocalDateTime expireTime
    ) {
    }

    /**
     * 接受邀请结果。
     */
    public record AcceptResult(
            String userId,
            String userNo,
            String name,
            String phone,
            String email,
            String roleCode,
            String roleName,
            String tenantCode
    ) {
    }
}
