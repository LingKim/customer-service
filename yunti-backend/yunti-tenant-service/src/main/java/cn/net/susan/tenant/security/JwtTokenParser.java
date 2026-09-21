package cn.net.susan.tenant.security;

import cn.net.susan.common.api.ResultCode;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * JWT 解析器（HS256，与 user-service 共用签名密钥）。
 */
@Component
public class JwtTokenParser {

    private final SecretKey secretKey;

    public JwtTokenParser(@Value("${yunti.auth.jwt-secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public LoginUser parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new LoginUser(
                    Long.parseLong(claims.getSubject()),
                    claims.get("uno", String.class),
                    claims.get("name", String.class),
                    claims.get("type", Integer.class),
                    claims.get("tnt", String.class)
            );
        } catch (Exception e) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
    }

    public LoginUser requireLoginUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        String token = authorization.substring(7).trim();
        if (token.isEmpty()) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return parseToken(token);
    }
}
