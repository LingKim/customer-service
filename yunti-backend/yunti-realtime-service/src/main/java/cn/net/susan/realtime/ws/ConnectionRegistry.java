package cn.net.susan.realtime.ws;

import cn.net.susan.realtime.security.RealtimePrincipal;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接注册表：谁在线、连在哪个会话上。
 *
 * <p>单机内存实现，够跑通单实例；多实例部署时把这份映射换到 Redis
 * （按 sessionNo 记路由，推送时定位到具体实例）即可水平扩容。</p>
 */
@Component
public class ConnectionRegistry {

    /** 一条长连接 */
    public static final class Client {
        private final WebSocketSession socket;
        private final RealtimePrincipal principal;
        private volatile long lastActiveAt = System.currentTimeMillis();
        private volatile long windowStart = System.currentTimeMillis();
        private volatile int windowCount = 0;

        Client(WebSocketSession socket, RealtimePrincipal principal) {
            this.socket = socket;
            this.principal = principal;
        }

        public WebSocketSession socket() {
            return socket;
        }

        public RealtimePrincipal principal() {
            return principal;
        }

        public long lastActiveAt() {
            return lastActiveAt;
        }

        public void touch() {
            this.lastActiveAt = System.currentTimeMillis();
        }

        /**
         * 单连接限流：每秒最多 maxPerSecond 条上行消息。
         */
        public boolean allow(int maxPerSecond) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= 1000) {
                windowStart = now;
                windowCount = 0;
            }
            windowCount++;
            return windowCount <= maxPerSecond;
        }
    }

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionSubscribers = new ConcurrentHashMap<>();

    public void bind(WebSocketSession socket, RealtimePrincipal principal) {
        clients.put(socket.getId(), new Client(socket, principal));
    }

    public Client get(String socketId) {
        return clients.get(socketId);
    }

    public Client unbind(String socketId) {
        Client client = clients.remove(socketId);
        for (Set<String> ids : sessionSubscribers.values()) {
            ids.remove(socketId);
        }
        return client;
    }

    /** 订阅某个会话（坐席可同时订阅多个，访客只订阅自己那一个）。 */
    public void subscribe(String socketId, String sessionNo) {
        sessionSubscribers.computeIfAbsent(sessionNo, key -> ConcurrentHashMap.newKeySet()).add(socketId);
    }

    public Set<String> subscribersOf(String sessionNo) {
        return sessionSubscribers.getOrDefault(sessionNo, Set.of());
    }

    /** 该会话当前在线的连接数（含访客与坐席）。 */
    public int onlineCount(String sessionNo) {
        return subscribersOf(sessionNo).size();
    }

    /**
     * 找出空闲超时的连接，交由定时任务断开。
     */
    public Map<String, Client> idleClients(int timeoutSeconds) {
        long deadline = Instant.now().toEpochMilli() - timeoutSeconds * 1000L;
        Map<String, Client> result = new ConcurrentHashMap<>();
        clients.forEach((id, client) -> {
            if (client.lastActiveAt() < deadline) {
                result.put(id, client);
            }
        });
        return result;
    }

    public int totalConnections() {
        return clients.size();
    }
}
