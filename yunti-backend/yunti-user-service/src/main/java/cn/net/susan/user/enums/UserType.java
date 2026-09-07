package cn.net.susan.user.enums;

/**
 * 用户类型（sys_user.user_type，存 SMALLINT 码值）。
 */
public enum UserType {

    /** 平台账号（平台运营 / 平台管理员） */
    PLATFORM(1, "平台账号"),

    /** 企业账号（注册企业及其成员） */
    ENTERPRISE(2, "企业账号");

    private final int code;
    private final String desc;

    UserType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按码值解析，未知码值返回 null（便于调用方兜底处理）。
     */
    public static UserType of(Integer code) {
        if (code == null) {
            return null;
        }
        for (UserType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return null;
    }
}
