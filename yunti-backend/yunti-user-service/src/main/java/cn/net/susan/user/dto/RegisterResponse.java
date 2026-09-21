package cn.net.susan.user.dto;

/**
 * 企业注册结果。
 *
 * @param userId          新用户 ID
 * @param userNo          用户编号（登录账号之一）
 * @param contactName     管理员姓名
 * @param phone           手机号
 * @param email           邮箱
 * @param enterpriseId    企业草稿 ID
 * @param enterpriseCode  企业编码
 * @param enterpriseStatus 企业状态码：1-待审核
 * @param tenantCode       企业注册时预分配的租户编码（审核通过后正式创建租户行）
 * @param userType         用户类型码：2-企业账号
 */
public record RegisterResponse(
        String userId,
        String userNo,
        String contactName,
        String phone,
        String email,
        String enterpriseId,
        String enterpriseCode,
        int enterpriseStatus,
        String tenantCode,
        int userType
) {
}
