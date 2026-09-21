package cn.net.susan.user.dto;

import cn.net.susan.common.auth.LoginUser;

/** 当前登录用户的对外响应；雪花 ID 始终以字符串返回。 */
public record MeResponse(
        String userId,
        String userNo,
        String name,
        int userType,
        String tenantCode
) {
    public static MeResponse from(LoginUser user) {
        return new MeResponse(
                String.valueOf(user.userId()),
                user.userNo(),
                user.name(),
                user.userType(),
                user.tenantCode()
        );
    }
}
