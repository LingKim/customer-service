package cn.net.susan.ops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ops-service：运营（官网配置/公告/审计/安全中心/客户成功，ops_db）。
 */
@SpringBootApplication(scanBasePackages = "cn.net.susan")
public class OpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(OpsApplication.class, args);
    }
}
