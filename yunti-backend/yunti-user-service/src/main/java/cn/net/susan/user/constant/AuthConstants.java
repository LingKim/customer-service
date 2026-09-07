package cn.net.susan.user.constant;

/**
 * 认证相关平台常量。
 *
 * <p>用户类型 / 用户状态 / 登录方式 / 登录结果已下沉为 {@code cn.net.susan.user.enums}
 * 下的专业枚举，本类仅保留与租户归属相关的平台常量。</p>
 */
public final class AuthConstants {

    private AuthConstants() {
    }

    /**
     * 平台租户编码（全局常量，与 database-design.md 一致）。
     *
     * <p>两种场景使用平台租户：
     * 1. 平台账号（user_type=1）；
     * 2. 新注册企业账号（user_type=2）审核开通前先挂平台租户，
     *    审核通过后由 tenant-service 生成正式租户编码（T + 日期 + 序号）并回填。
     *    登录失败且账号不存在时，失败流水也记在平台租户下（仅审计，不产生业务数据）。
     */
    public static final String TENANT_CODE_PLATFORM = "PLATFORM";
}
