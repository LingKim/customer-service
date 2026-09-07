package cn.net.susan.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 接入网关（骨架占位）。
 * TODO: 演进为 API Gateway（路由/限流/鉴权）+ WSS 实时网关（长连接），见 system-architecture.md。
 */
@SpringBootApplication(scanBasePackages = "cn.net.susan")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
