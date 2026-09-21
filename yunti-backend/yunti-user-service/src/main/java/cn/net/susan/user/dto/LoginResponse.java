package cn.net.susan.user.dto;

/**
 * 登录结果。
 */
public record LoginResponse(
        String token,
        String userId,
        String userNo,
        String name,
        int userType,
        /** 租户编码：企业账号审核开通前为平台租户 PLATFORM，开通后为正式 T 编码 */
        String tenantCode,
        String avatar
) {
}
