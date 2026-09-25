package cn.net.susan.customer.security;

import cn.net.susan.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 访客令牌：访客端（官网挂件 / 微信 / 小程序）没有账号密码，
 * 用渠道密钥换取一枚短期令牌，再去连实时网关。
 *
 * <p>令牌用与坐席同一把密钥签发，载荷里带 {@code typ=4}（访客）和 {@code sno}（会话号），
 * 实时网关据此判断"这是访客，并且只允许进出这一个会话"。</p>
 *
 * <p>另外还签发一枚 <b>访客身份令牌</b>（{@code typ=3}，默认 30 天）：
 * 它解决"同一个客户下次再来还是他"的问题。身份令牌由服务端签发并校验签名，
 * 前端只负责保存和回传，绝不能拿明文客户编号当身份——那样编号一旦被猜到或泄露，
 * 别人就能顶替该客户继续会话。</p>
 */
@Service
public class VisitorTokenService {

    /** 令牌类型：访客 */
    public static final int TYPE_VISITOR = 4;

    /** 令牌类型：访客身份（长期） */
    public static final int TYPE_VISITOR_IDENTITY = 3;

    private static final long DEFAULT_EXPIRE_SECONDS = 12 * 3600L;
    private static final long DEFAULT_IDENTITY_EXPIRE_SECONDS = 30 * 24 * 3600L;

    private final SecretKey secretKey;
    private final long expireSeconds;
    private final long identityExpireSeconds;

    public VisitorTokenService(
            @Value("${yunti.auth.jwt-secret}") String secret,
            @Value("${yunti.auth.visitor-expire-seconds:43200}") long expireSeconds,
            @Value("${yunti.auth.visitor-identity-expire-seconds:2592000}") long identityExpireSeconds
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds > 0 ? expireSeconds : DEFAULT_EXPIRE_SECONDS;
        this.identityExpireSeconds = identityExpireSeconds > 0
                ? identityExpireSeconds
                : DEFAULT_IDENTITY_EXPIRE_SECONDS;
    }

    public String createToken(long customerId, String customerName, String tenantCode, String sessionNo) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(customerId))
                .claim("name", customerName)
                .claim("tnt", tenantCode)
                .claim("typ", TYPE_VISITOR)
                .claim("sno", sessionNo)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireSeconds * 1000L))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 签发访客身份令牌（长期）：同一个客户下次打开挂件时据此复用客户档案。
     */
    public String createIdentityToken(long customerId, String customerNo, String customerName, String tenantCode) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(customerId))
                .claim("cno", customerNo)
                .claim("name", customerName)
                .claim("tnt", tenantCode)
                .claim("typ", TYPE_VISITOR_IDENTITY)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + identityExpireSeconds * 1000L))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析访客身份令牌：非法、过期、类型不对一律返回 {@code null}，
     * 由调用方当成"新访客"处理，避免因为一枚过期令牌把客户挡在门外。
     */
    public VisitorIdentity parseIdentityToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Integer type = claims.get("typ", Integer.class);
            if (type == null || type != TYPE_VISITOR_IDENTITY) {
                return null;
            }
            String tenant = claims.get("tnt", String.class);
            String customerNo = claims.get("cno", String.class);
            if (tenant == null || tenant.isBlank() || customerNo == null || customerNo.isBlank()) {
                return null;
            }
            return new VisitorIdentity(
                    Long.parseLong(claims.getSubject()),
                    customerNo,
                    tenant,
                    claims.get("name", String.class));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析访客令牌；非法或过期抛未认证。
     */
    public VisitorPrincipal parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!Integer.valueOf(TYPE_VISITOR).equals(claims.get("typ", Integer.class))) {
                throw new BizException(40100, "访客令牌类型无效");
            }
            return new VisitorPrincipal(
                    Long.parseLong(claims.getSubject()),
                    claims.get("name", String.class),
                    claims.get("tnt", String.class),
                    claims.get("sno", String.class)
            );
        } catch (Exception e) {
            throw new BizException(40100, "访客令牌无效或已过期");
        }
    }

    public LocalDateTime expireAt() {
        return LocalDateTime.ofInstant(new Date(System.currentTimeMillis() + expireSeconds * 1000L).toInstant(),
                ZoneId.systemDefault());
    }

    /**
     * 访客身份。
     */
    public record VisitorPrincipal(long customerId, String customerName, String tenantCode, String sessionNo) {
    }

    /**
     * 访客身份（长期令牌解析结果）。
     */
    public record VisitorIdentity(long customerId, String customerNo, String tenantCode, String customerName) {
    }
}
