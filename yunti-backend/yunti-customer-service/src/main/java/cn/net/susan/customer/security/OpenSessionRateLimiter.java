package cn.net.susan.customer.security;

import cn.net.susan.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 访客开会话限流：按「渠道密钥 + 客户端 IP」计数。
 *
 * <p>渠道密钥是公开标识，谁都能复制，所以开会话这个入口必须限流——
 * 否则一个脚本就能把企业库里的客户表和会话表刷满。</p>
 *
 * <p>单机内存实现（固定窗口），够跑通单实例；多实例部署时把计数换成 Redis 的
 * {@code INCR + EXPIRE} 即可，语义不变。</p>
 */
@Component
public class OpenSessionRateLimiter {

    /** 超过这个条数就拒绝，默认每分钟 30 次 */
    private final int maxPerMinute;

    private static final int MAX_KEYS = 5000;

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public OpenSessionRateLimiter(
            @Value("${yunti.visitor.open-rate-limit-per-minute:30}") int maxPerMinute
    ) {
        this.maxPerMinute = maxPerMinute;
    }

    /**
     * 计数 +1；超过阈值抛业务异常。
     */
    public void check(String appKey, String clientIp) {
        if (maxPerMinute <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        String key = (appKey == null ? "-" : appKey.trim()) + '|' + (clientIp == null ? "-" : clientIp);
        evictExpired(now);
        Window window = windows.compute(key, (k, current) -> {
            if (current == null || now - current.startAt >= 60_000L) {
                return new Window(now, 1);
            }
            current.count++;
            return current;
        });
        if (window.count > maxPerMinute) {
            throw new BizException(42900, "会话创建过于频繁，请稍后再试");
        }
    }

    private void evictExpired(long now) {
        if (windows.size() < MAX_KEYS) {
            return;
        }
        windows.entrySet().removeIf(entry -> now - entry.getValue().startAt >= 60_000L);
    }

    /** 固定窗口计数：窗口开始时间 + 窗口内次数 */
    private static final class Window {
        private final long startAt;
        private int count;

        private Window(long startAt, int count) {
            this.startAt = startAt;
            this.count = count;
        }
    }
}
