package cn.net.susan.user.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图形验证码内存存储（单机演示用）。
 *
 * <p>生产建议替换为 Redis（key: captcha:{id}，TTL 5 分钟），
 * 本类已按同样的“一次有效、过期作废”语义实现。
 */
@Component
public class CaptchaStore {

    /** 验证码有效期：5 分钟 */
    private static final long EXPIRE_MILLIS = 5 * 60 * 1000L;

    /** 单机最多保留的验证码数量，防止无界增长 */
    private static final int MAX_SIZE = 1000;

    private static final char[] CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /**
     * 生成验证码并保存。
     *
     * @return 验证码 ID
     */
    public String create(String code) {
        evictExpired();
        String id = UUID.randomUUID().toString().replace("-", "");
        if (store.size() >= MAX_SIZE) {
            // 超限时先清掉最早的一条，避免被刷爆内存
            String oldest = store.entrySet().iterator().next().getKey();
            store.remove(oldest);
        }
        store.put(id, new Entry(code, System.currentTimeMillis() + EXPIRE_MILLIS));
        return id;
    }

    /**
     * 校验验证码（忽略大小写，一次有效）。
     */
    public boolean verify(String id, String code) {
        if (id == null || code == null) {
            return false;
        }
        Entry entry = store.remove(id);
        if (entry == null || entry.expireAt() < System.currentTimeMillis()) {
            return false;
        }
        return entry.code().equalsIgnoreCase(code.trim());
    }

    /** 生成随机 4 位验证码（去掉易混淆字符）。 */
    public String randomCode() {
        StringBuilder sb = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            sb.append(CHARS[random.nextInt(CHARS.length)]);
        }
        return sb.toString();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> e.getValue().expireAt() < now);
    }

    private record Entry(String code, long expireAt) {
    }
}
