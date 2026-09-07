package cn.net.susan.user.enums;

/**
 * 用户状态（sys_user.status，存 SMALLINT 码值）。
 */
public enum UserStatus {

    /** 正常 */
    NORMAL(1, "正常"),

    /** 停用 */
    DISABLED(2, "停用"),

    /** 锁定 */
    LOCKED(3, "锁定");

    private final int code;
    private final String desc;

    UserStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static UserStatus of(Integer code) {
        if (code == null) {
            return null;
        }
        for (UserStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        return null;
    }
}
