package cn.net.susan.common.id;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 线程安全的 64 位雪花 ID 生成器。
 *
 * <p>服务实例通过 {@code yunti.id.worker-id} 分配不同工作节点号，以避免多服务并发写入时产生重复 ID。</p>
 */
@Component
public class SnowflakeIdGenerator {

    private static final long EPOCH = 1_704_067_200_000L; // 2024-01-01T00:00:00Z
    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private final long workerId;
    private long sequence;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(@Value("${yunti.id.worker-id:1}") long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("worker-id must be between 0 and " + MAX_WORKER_ID);
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException(
                    "Clock moved backwards by " + (lastTimestamp - timestamp) + "ms; refusing to generate duplicate IDs"
            );
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = waitForNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0;
        }

        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << (WORKER_ID_BITS + SEQUENCE_BITS))
                | (workerId << SEQUENCE_BITS)
                | sequence;
    }

    private long waitForNextMillis(long previousTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= previousTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
