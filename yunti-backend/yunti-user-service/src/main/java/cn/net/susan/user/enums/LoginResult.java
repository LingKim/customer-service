package cn.net.susan.user.enums;

/**
 * 登录结果（login_log.result，存 SMALLINT 码值）。
 */
public enum LoginResult {

    /** 成功 */
    SUCCESS(1, "成功"),

    /** 失败（密码错误等） */
    FAIL(2, "失败"),

    /** 异常（账号状态异常等） */
    EXCEPTION(3, "异常");

    private final int code;
    private final String desc;

    LoginResult(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static LoginResult of(Integer code) {
        if (code == null) {
            return null;
        }
        for (LoginResult result : values()) {
            if (result.code == code) {
                return result;
            }
        }
        return null;
    }
}
