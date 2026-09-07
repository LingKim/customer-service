package cn.net.susan.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * user-service：认证、用户、角色权限、个人中心（user_db）。
 */
@SpringBootApplication(scanBasePackages = "cn.net.susan")
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
