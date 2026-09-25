package cn.net.susan.user.enums;

import java.util.Arrays;

/**
 * 企业成员默认角色（与 sys_role 中内置企业角色保持一致）。
 */
public enum MemberRole {

    ADMIN("ADMIN", "企业管理员"),
    SUPERVISOR("SUPERVISOR", "客服主管"),
    SENIOR_AGENT("SENIOR_AGENT", "高级客服"),
    AGENT("AGENT", "客服专员"),
    QUALITY("QUALITY", "质检专员"),
    AI_OPERATOR("AI_OPERATOR", "AI运营");

    private final String code;
    private final String name;

    MemberRole(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    /**
     * 按编码查找；支持客服主管 / 高级客服 / 客服专员 / 质检专员 / AI运营参与邀请。
     */
    public static MemberRole fromCode(String code) {
        return Arrays.stream(values())
                .filter(r -> r.code.equals(code))
                .findFirst()
                .orElse(null);
    }
}
