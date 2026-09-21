package cn.net.susan.customer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * customer-service：客服核心（会话/消息/渠道/知识库/工单/质检/消息中心，customer_db）。
 */
@SpringBootApplication(scanBasePackages = "cn.net.susan")
@MapperScan("cn.net.susan.customer.mapper")
public class CustomerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerApplication.class, args);
    }
}
