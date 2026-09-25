package cn.net.susan.realtime.security;

import cn.net.susan.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * 长连接令牌解析：坐席令牌（登录颁发）与访客令牌（渠道密钥换取）共用一把密钥。
 */
@Component
public class RealtimeTokenParser {

    /** 访客令牌标记（载荷 typ） */
    private static final int TOKEN_TYPE_VISITOR = 4;

    /** 访客身份令牌标记：长期身份令牌不能拿来建会话连接 */
    private static final int TOKEN_TYPE_VISITOR_IDENTITY = 3;

    /** 用户类型：企业账号 */
    private static final int USER_TYPE_ENTERPRISE = 2;

    private final SecretKey secretKey;

    public RealtimeTokenParser(@Value("${yunti.auth.jwt-secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析令牌；非法、过期、平台账号一律拒绝。
     */
    public RealtimePrincipal parse(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(40100, "缺少访问令牌");
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            throw new BizException(40100, "访问令牌无效或已过期");
        }

        Integer typ = claims.get("typ", Integer.class);
        String tenant = claims.get("tnt", String.class);
        String name = claims.get("name", String.class);

        if (typ != null && typ == TOKEN_TYPE_VISITOR_IDENTITY) {
            throw new BizException(40100, "访客身份令牌不能用于建立会话连接，请先打开会话");
        }

        if (typ != null && typ == TOKEN_TYPE_VISITOR) {
            String sessionNo = claims.get("sno", String.class);
            if (tenant == null || tenant.isBlank() || sessionNo == null || sessionNo.isBlank()) {
                throw new BizException(40100, "访客令牌缺少租户或会话信息");
            }
            return new RealtimePrincipal(
                    RealtimePrincipal.Identity.VISITOR,
                    Long.parseLong(claims.getSubject()),
                    name == null ? "访客" : name,
                    tenant,
                    sessionNo
            );
        }

        Integer userType = claims.get("type", Integer.class);
        if (userType == null || userType != USER_TYPE_ENTERPRISE) {
            throw new BizException(40301, "平台账号不参与会话接待");
        }
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用在线客服");
        }
        return new RealtimePrincipal(
                RealtimePrincipal.Identity.AGENT,
                Long.parseLong(claims.getSubject()),
                name == null ? "客服" : name,
                tenant,
                null
        );
    }
}
