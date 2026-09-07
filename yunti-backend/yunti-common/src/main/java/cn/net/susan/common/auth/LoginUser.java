package cn.net.susan.common.auth;

/**
 * 登录用户信息（JWT 载荷 + 上下文）。
 *
 * @param userId     用户 ID
 * @param userNo     用户编号（对外）
 * @param name       姓名
 * @param userType   类型码：1-平台账号、2-企业账号
 * @param tenantCode 主归属租户编码（企业未开通前为空串）
 */
public record LoginUser(
        long userId,
        String userNo,
        String name,
        int userType,
        String tenantCode
) {
}
