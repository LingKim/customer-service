package cn.net.susan.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 实时网关：直接承载 WebSocket 长连接（客服与客户"秒回"的通道）。
 *
 * <p>与 HTTP 网关分工：HTTP 走 yunti-gateway（无状态、可随便扩容），
 * 长连接走本服务（有状态、按连接数扩容）。会话与消息的落库统一调 customer-service，
 * 本服务不直连客户库。</p>
 */
@SpringBootApplication(scanBasePackages = "cn.net.susan")
@EnableScheduling
public class RealtimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeApplication.class, args);
    }
}
