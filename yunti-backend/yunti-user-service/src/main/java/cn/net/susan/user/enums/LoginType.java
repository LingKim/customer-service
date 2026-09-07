package cn.net.susan.user.enums;

/**
 * 登录方式（login_log.login_type，存 SMALLINT 码值）。
 */
public enum LoginType {

    /** 密码登录 */
    PASSWORD(1, "密码登录"),

    /** 企业微信 SSO */
    WECHAT_WORK_SSO(2, "企业微信 SSO"),

    /** 飞书 SSO */
    FEISHU_SSO(3, "飞书 SSO");

    private final int code;
    private final String desc;

    LoginType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static LoginType of(Integer code) {
        if (code == null) {
            return null;
        }
        for (LoginType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}
