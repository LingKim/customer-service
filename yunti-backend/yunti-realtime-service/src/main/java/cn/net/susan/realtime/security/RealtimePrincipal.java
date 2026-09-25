package cn.net.susan.realtime.security;

/**
 * 长连接上的身份：要么是坐席（企业成员，可进出本企业任意会话），
 * 要么是访客（没有账号，只允许进出自己被分配的那一个会话）。
 */
public record RealtimePrincipal(
        Identity identity,
        long id,
        String name,
        String tenantCode,
        String sessionNo
) {

    public enum Identity {
        /** 坐席（企业成员） */
        AGENT,
        /** 访客（客户） */
        VISITOR
    }

    public boolean isVisitor() {
        return identity == Identity.VISITOR;
    }
}
