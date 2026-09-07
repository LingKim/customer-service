package cn.net.susan.common.context;

/**
 * 租户上下文（ThreadLocal）。网关解析 X-Tenant-Code 后注入，业务侧使用。
 */
public final class TenantContext {

    public static final String HEADER_TENANT_CODE = "X-Tenant-Code";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantCode) {
        TENANT.set(tenantCode);
    }

    public static String get() {
        return TENANT.get();
    }

    public static void clear() {
        TENANT.remove();
    }
}
