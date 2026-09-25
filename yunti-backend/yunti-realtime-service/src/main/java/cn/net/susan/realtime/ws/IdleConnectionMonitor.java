package cn.net.susan.realtime.ws;

import cn.net.susan.realtime.config.RealtimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.util.Map;

/**
 * 心跳超时巡检：客户端 30 秒一次 PING，超过阈值没消息就判掉线并断开。
 */
@Component
public class IdleConnectionMonitor {

    private static final Logger log = LoggerFactory.getLogger(IdleConnectionMonitor.class);

    private final ConnectionRegistry registry;
    private final RealtimeProperties properties;

    public IdleConnectionMonitor(ConnectionRegistry registry, RealtimeProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 30000L, initialDelay = 30000L)
    public void sweep() {
        Map<String, ConnectionRegistry.Client> idle = registry.idleClients(properties.getIdleTimeoutSeconds());
        if (idle.isEmpty()) {
            return;
        }
        idle.forEach((socketId, client) -> {
            try {
                client.socket().close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (Exception e) {
                log.debug("关闭空闲连接失败 socketId={} error={}", socketId, e.getMessage());
            }
            registry.unbind(socketId);
            log.info("心跳超时断开连接 userId={} identity={} 当前连接数={}",
                    client.principal().id(), client.principal().identity(), registry.totalConnections());
        });
    }
}
