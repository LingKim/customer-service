package cn.net.susan.realtime.ws;

import cn.net.susan.realtime.config.RealtimeProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册长连接入口：ws://host:9096/ws/realtime?token=xxx
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler handler;
    private final AuthHandshakeInterceptor authInterceptor;
    private final RealtimeProperties properties;

    public WebSocketConfig(
            RealtimeWebSocketHandler handler,
            AuthHandshakeInterceptor authInterceptor,
            RealtimeProperties properties
    ) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, properties.getPath())
                .addInterceptors(authInterceptor)
                // 访客来自企业自己的网站/小程序，域名不可枚举，这里放开跨域；生产建议按渠道域名白名单收紧
                .setAllowedOriginPatterns("*");
    }
}
