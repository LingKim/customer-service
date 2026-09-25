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
 */
@Service
public class VisitorTokenService {

    /** 令牌类型：访客 */
    public static final int TYPE_VISITOR = 4;

    private static final long DEFAULT_EXPIRE_SECONDS = 12 * 3600L;

    private final SecretKey secretKey;
    private final long expireSeconds;

    public VisitorTokenService(
            @Value("${yunti.auth.jwt-secret}") String secret,
            @Value("${yunti.auth.visitor-expire-seconds:43200}") long expireSeconds
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds > 0 ? expireSeconds : DEFAULT_EXPIRE_SECONDS;
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
     * 解析访客令牌；非法或过期抛未认证。
     */
    public VisitorPrincipal parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
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
}
