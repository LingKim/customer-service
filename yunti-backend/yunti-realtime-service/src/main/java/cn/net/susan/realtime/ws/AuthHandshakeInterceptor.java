package cn.net.susan.realtime.ws;

import cn.net.susan.realtime.security.RealtimePrincipal;
import cn.net.susan.realtime.security.RealtimeTokenParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 握手鉴权：浏览器建连时带不了自定义请求头，令牌通过 {@code ?token=xxx} 传。
 * 校验不通过直接 401 拒绝握手，连不上就不会占用服务端连接资源。
 *
 * <p>日志要限流：令牌过期的标签页会一直重连（每 15 秒一次），
 * 每次都打一条 WARN 的话，一天往日志里灌好几千行同样的内容，
 * 真正要看的错误反而被淹了。所以同一个令牌每 5 分钟最多 WARN 一次，其余降到 DEBUG。</p>
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String PRINCIPAL_KEY = "realtimePrincipal";

    private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);

    /** 同一条令牌多久最多告警一次 */
    private static final long WARN_INTERVAL_MILLIS = 5 * 60 * 1000L;

    /** 令牌指纹 → 上次告警时间（只放指纹，不放令牌本身） */
    private final Map<String, Long> warnedAt = new ConcurrentHashMap<>();

    private final RealtimeTokenParser tokenParser;

    public AuthHandshakeInterceptor(RealtimeTokenParser tokenParser) {
        this.tokenParser = tokenParser;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("token");
        try {
            RealtimePrincipal principal = tokenParser.parse(token);
            attributes.put(PRINCIPAL_KEY, principal);
            return true;
        } catch (Exception e) {
            String fingerprint = fingerprint(token);
            long now = System.currentTimeMillis();
            Long previous = warnedAt.putIfAbsent(fingerprint, now);
            if (previous == null || now - previous >= WARN_INTERVAL_MILLIS) {
                warnedAt.put(fingerprint, now);
                log.warn("长连接鉴权失败 path={} reason={}", request.getURI().getPath(), e.getMessage());
            } else {
                log.debug("长连接鉴权失败 path={} reason={}", request.getURI().getPath(), e.getMessage());
            }
            if (warnedAt.size() > 4096) {
                warnedAt.entrySet().removeIf(entry -> now - entry.getValue() > WARN_INTERVAL_MILLIS);
            }
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    private String fingerprint(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((token == null ? "" : token).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // 无需处理
    }
}
