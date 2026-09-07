package cn.net.susan.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * billing-service：计费（套餐/订单/支付/发票，billing_db）。
 */
@SpringBootApplication(scanBasePackages = "cn.net.susan")
public class BillingApplication {

    public static void main(String[] args) {
        SpringApplication.run(BillingApplication.class, args);
    }
}
