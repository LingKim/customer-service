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

import java.util.Map;

/**
 * 握手鉴权：浏览器建连时带不了自定义请求头，令牌通过 {@code ?token=xxx} 传。
 * 校验不通过直接 401 拒绝握手，连不上就不会占用服务端连接资源。
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String PRINCIPAL_KEY = "realtimePrincipal";

    private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);

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
            log.warn("长连接鉴权失败 path={} reason={}", request.getURI().getPath(), e.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
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
