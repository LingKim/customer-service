package cn.net.susan.tenant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * tenant-service：企业、租户、企业审核（tenant_db）。
 */
@SpringBootApplication(scanBasePackages = "cn.net.susan")
public class TenantApplication {

    public static void main(String[] args) {
        SpringApplication.run(TenantApplication.class, args);
    }
}
