package cn.net.susan.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 云梯 API 网关（Spring Cloud Gateway / WebFlux）。
 *
 * <p>统一入口负责：服务路由、跨域、后续鉴权/限流/审计；当前按前缀路由到各业务服务，
 */
@SpringBootApplication(scanBasePackages = "cn.net.susan")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
