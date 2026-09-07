package cn.net.susan.common.api;

/**
 * 统一返回码：业务段 40xxx 参数/业务、50xxx 系统。
 */
public enum ResultCode {

    SUCCESS(0, "成功"),
    BAD_REQUEST(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未认证或登录已过期"),
    FORBIDDEN(40300, "无访问权限"),
    TENANT_MISSING(40110, "缺少租户上下文"),
    NOT_FOUND(40400, "资源不存在"),
    INTERNAL_ERROR(50000, "系统内部错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
