package cn.net.susan.realtime.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 实时网关配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "yunti.realtime")
public class RealtimeProperties {

    /** WebSocket 路径 */
    private String path = "/ws/realtime";

    /** 空闲超时（秒） */
    private int idleTimeoutSeconds = 90;

    /** 单连接每秒最大消息数 */
    private int maxMessagesPerSecond = 10;

    /** 下行缓冲上限（字节） */
    private int sendBufferSizeLimit = 512 * 1024;

    /** 单次发送超时（毫秒） */
    private int sendTimeLimitMs = 10000;
}
