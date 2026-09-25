package cn.net.susan.customer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * customer-service：客服核心（会话/消息/渠道/知识库/工单/质检/消息中心，customer_db）。
 *
 * <p>开启定时任务：智能路由要定期扫描排队会话（排队超时升级靠它兜底）。</p>
 */
@SpringBootApplication(scanBasePackages = "cn.net.susan")
@MapperScan("cn.net.susan.customer.mapper")
@EnableScheduling
public class CustomerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerApplication.class, args);
    }
}
