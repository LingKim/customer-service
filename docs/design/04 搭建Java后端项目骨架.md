---
title: "04 搭建Java后端项目骨架"
source: "https://articles.zsxq.com/id_wlo7zdcrvc3m.html"
author:
  - "[[苏三]]"
published:
created: 2026-09-06
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

| 项目 | 内容 |
| --- | --- |
| 目标 | 从空目录开始，逐步创建出与「云梯智能客服」后端完全一致的 Maven 多模块工程，并成功编译、启动 |
| 技术栈 | Java 17+、Spring Boot 4.0.3 |
| 前置 | JDK 17 及以上、Maven 3.8+、能访问 Maven 中央仓库（或阿里云镜像） |

> 本教程不依赖任何数据库/中间件，搭出的 7 个模块（1 个公共模块 + 6 个服务）都可独立启动，访问统一返回 JSON。

## 1\. 成品长什么样

最终目录结构如下（共 34 个源文件，本教程会给出全部文件内容）：

```latex
yunti-backend/
├── pom.xml                          # 父工程（聚合模块）
├── .gitignore
├── README.md
├── yunti-common/                     # 公共模块：统一响应/异常/租户上下文/启动日志
│   ├── pom.xml
│   └── src/main/java/cn/net/susan/common/
│       ├── api/{ApiResponse,ResultCode}.java
│       ├── exception/{BizException,GlobalExceptionHandler}.java
│       ├── context/TenantContext.java
│       └── logging/StartupLogger.java
├── yunti-gateway/                    # gateway-service :9090
├── yunti-user-service/               # user-service     :9091
├── yunti-tenant-service/             # tenant-service   :9092
├── yunti-customer-service/           # customer-service :9093
├── yunti-billing-service/            # billing-service  :9094
└── yunti-ops-service/                # ops-service      :9095
```

启动任意服务后，访问 `http://localhost:9091/actuator/health` 返回 `{"status":"UP"}` ，访问 `http://localhost:9091/api/user/ping` 返回：

```json
{"code":0,"message":"成功","data":{"tenantCode":"","service":"user-service"},"requestId":null,"timestamp":1788345556700}
```

---

## 2\. 环境准备

1. 安装 JDK 17+： `java -version`
2. 安装 Maven 3.8+： `mvn -v`
3. 建议配置国内镜像 `~/.m2/settings.xml` （可跳过，只是更快）：

```xml
<settings>
  <mirrors>
    <mirror>
      <id>aliyunmaven</id>
      <mirrorOf>central</mirrorOf>
      <url>https://maven.aliyun.com/repository/public</url>
    </mirror>
  </mirrors>
</settings>
```

---

## 3\. 创建目录

在任意目录（本文以 `~/workspace` 为例）创建工程根目录并进入：

```bash
mkdir -p ~/workspace/yunti-backend && cd ~/workspace/yunti-backend
```

> 后续所有「创建文件」均相对于该目录。

---

## 4\. 逐个创建文件

按照下面的顺序，一个文件一个文件创建。每个文件都给出了完整内容，直接复制即可； **不要漏文件** （34 个）。

### 4.1 文件：pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.3</version>
        <relativePath/>
    </parent>
    <groupId>cn.net.susan</groupId>
    <artifactId>yunti-backend</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>yunti-backend</name>
    <description>云梯智能客服平台 · 后端微服务（Maven 多模块骨架）</description>
    <modules>
        <module>yunti-common</module>
        <module>yunti-gateway</module>
        <module>yunti-user-service</module>
        <module>yunti-tenant-service</module>
        <module>yunti-customer-service</module>
        <module>yunti-billing-service</module>
        <module>yunti-ops-service</module>
    </modules>
    <properties>
        <java.version>21</java.version>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.parameters>true</maven.compiler.parameters>
    </properties>
</project>
```

### 4.2 文件：.gitignore

```latex
target/
*.class
.idea/
*.iml
.vscode/
.DS_Store
logs/
```

### 4.3 文件：README.md

```markdown
# 云梯智能客服平台 · yunti-backend 后端骨架

基于 [docs/system-architecture.md](../docs/system-architecture.md) 的 6+1 微服务划分搭建的 Maven 多模块工程骨架。

## 模块与端口

| 模块 | 服务 | 端口 | 说明 |
| --- | --- | --- | --- |
| yunti-common | — | — | 公共模块：统一响应、异常、租户上下文 |
| yunti-gateway | gateway-service | 9090 | 接入网关（骨架占位，后续演进 WSS/API 网关） |
| yunti-user-service | user-service | 9091 | 用户权限 |
| yunti-tenant-service | tenant-service | 9092 | 租户 |
| yunti-customer-service | customer-service | 9093 | 客服核心 |
| yunti-billing-service | billing-service | 9094 | 计费 |
| yunti-ops-service | ops-service | 9095 | 运营 |

## 快速运行

\`\`\`bash
cd yunti-backend
mvn -q clean package -DskipTests
java -jar yunti-user-service/target/yunti-user-service-1.0.0-SNAPSHOT.jar
```

验证： `curl http://localhost:9091/api/user/ping` 、 `curl http://localhost:9091/actuator/health`

当前骨架 **不连接数据库** ，仅验证微服务可独立启动与调用；数据源、消息队列、注册中心等在后续迭代接入。

### 4.1 文件：pom.xml

```
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>cn.net.susan</groupId>
        <artifactId>yunti-backend</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>yunti-common</artifactId>
    <name>yunti-common</name>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

### 4.5 文件：yunti-common/src/main/java/cn/net/susan/common/api/ResultCode.java

```java
package cn.net.susan.common.api;

/**
 * 统一返回码：业务段 40xxx 参数/业务、50xxx 系统。
 */
public enum ResultCode {

    SUCCESS(0, "成功"),
    BAD_REQUEST(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未认证或登录已过期"),
    FORBIDDEN(40300, "无访问权限"),
    TENANT_MISSING(40110, "缺少租户上下文"),
    NOT_FOUND(40400, "资源不存在"),
    INTERNAL_ERROR(50000, "系统内部错误");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
```

### 4.6 文件：yunti-common/src/main/java/cn/net/susan/common/api/ApiResponse.java

```java
package cn.net.susan.common.api;

import java.time.Instant;

/**
 * 统一响应体。
 */
public record ApiResponse<T>(int code, String message, T data, String requestId, long timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data, null, Instant.now().toEpochMilli());
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ResultCode rc) {
        return new ApiResponse<>(rc.getCode(), rc.getMessage(), null, null, Instant.now().toEpochMilli());
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, null, Instant.now().toEpochMilli());
    }
}
```

### 4.7 文件：yunti-common/src/main/java/cn/net/susan/common/exception/BizException.java

```java
package cn.net.susan.common.exception;

import cn.net.susan.common.api.ResultCode;

/**
 * 业务异常。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ResultCode rc) {
        super(rc.getMessage());
        this.code = rc.getCode();
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
```

### 4.8 文件：yunti-common/src/main/java/cn/net/susan/common/exception/GlobalExceptionHandler.java

```java
package cn.net.susan.common.exception;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.api.ResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleBiz(BizException e) {
        return ApiResponse.fail(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse(ResultCode.BAD_REQUEST.getMessage());
        return ApiResponse.fail(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleOther(Exception e) {
        log.error("unhandled exception", e);
        return ApiResponse.fail(ResultCode.INTERNAL_ERROR);
    }
}
```

### 4.9 文件：yunti-common/src/main/java/cn/net/susan/common/context/TenantContext.java

```java
package cn.net.susan.common.context;

/**
 * 租户上下文（ThreadLocal）。网关解析 X-Tenant-Code 后注入，业务侧使用。
 */
public final class TenantContext {

    public static final String HEADER_TENANT_CODE = "X-Tenant-Code";
    public static final String HEADER_REQUEST_ID = "X-Request-Id";

    private static final ThreadLocal<String> TENANT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(String tenantCode) {
        TENANT.set(tenantCode);
    }

    public static String get() {
        return TENANT.get();
    }

    public static void clear() {
        TENANT.remove();
    }
}
```

### 4.10 文件：yunti-common/src/main/java/cn/net/susan/common/logging/StartupLogger.java

```java
package cn.net.susan.common.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 服务启动成功后的专业日志摘要：服务名、端口、环境、耗时、健康检查地址等。
 * 由各服务通过 @SpringBootApplication(scanBasePackages = "cn.net.susan") 自动装配。
 */
@Component
public class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    @Value("${spring.application.name:yunti-service}")
    private String appName;

    @Value("${server.port:0}")
    private int port;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        String[] profiles = env.getActiveProfiles();
        String profile = profiles.length == 0 ? "default" : String.join(",", profiles);
        long startupMs = event.getApplicationContext().getStartupDate();
        Duration elapsed = Duration.ofMillis(System.currentTimeMillis() - startupMs);

        String line = "  ┌──────────────────────────────────────────────────────────────┐";
        String sep = "  ├──────────────────────────────────────────────────────────────┤";
        String end = "  └──────────────────────────────────────────────────────────────┘";
        String bar = "    Yunti 智能客服平台 · 服务启动成功";
        String name = pad("服务名称", appName);
        String portS = pad("服务端口", String.valueOf(port));
        String envS = pad("运行环境", profile);
        String timeS = pad("启动耗时", elapsed.toMillis() / 1000.0 + " 秒");
        String jdkS = pad("JDK 版本", System.getProperty("java.version", "unknown"));
        String health = pad("健康检查", "http://localhost:" + port + "/actuator/health");
        String api = pad("业务入口", "http://localhost:" + port + "/api/" + appName.replace("yunti-", "").replace("-service", ""));

        log.info("\n" + bar + "\n" + line + "\n" + sep + "\n"
                + row(name) + row(portS) + row(envS) + row(timeS) + row(jdkS)
                + sep + "\n" + row(health) + row(api) + end);
    }

    private static String pad(String label, String value) {
        return label + ":" + " ".repeat(Math.max(1, 10 - displayWidth(label))) + value;
    }

    private static String row(String content) {
        int width = 60;
        int len = displayWidth(content);
        String pad = len >= width ? "" : " ".repeat(width - len);
        return "  │ " + content + pad + " │\n";
    }

    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 127 ? 2 : 1;
        }
        return w;
    }
}
```

### 4.11 文件：yunti-gateway/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>cn.net.susan</groupId>
        <artifactId>yunti-backend</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>yunti-gateway</artifactId>
    <name>yunti-gateway</name>
    <dependencies>
        <dependency>
            <groupId>cn.net.susan</groupId>
            <artifactId>yunti-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 4.12 文件：yunti-gateway/src/main/java/cn/net/susan/gateway/GatewayApplication.java

```java
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
```

### 4.13 文件：yunti-gateway/src/main/java/cn/net/susan/gateway/controller/GatewayController.java

```java
package cn.net.susan.gateway.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 网关占位接口。
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.ok(Map.of(
                "service", "gateway-service",
                "tenantCode", TenantContext.get() == null ? "" : TenantContext.get()
        ));
    }
}
```

### 4.14 文件：yunti-gateway/src/main/resources/application.yml

```yaml
server:
  port: 9090

spring:
  application:
    name: yunti-gateway

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

### 4.15 文件：yunti-user-service/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>cn.net.susan</groupId>
        <artifactId>yunti-backend</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>yunti-user-service</artifactId>
    <name>yunti-user-service</name>
    <dependencies>
        <dependency>
            <groupId>cn.net.susan</groupId>
            <artifactId>yunti-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 4.16 文件：yunti-user-service/src/main/java/cn/net/susan/user/UserApplication.java

```java
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
```

### 4.17 文件：yunti-user-service/src/main/java/cn/net/susan/user/controller/UserController.java

```java
package cn.net.susan.user.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * user-service 骨架接口。
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.ok(Map.of(
                "service", "user-service",
                "tenantCode", TenantContext.get() == null ? "" : TenantContext.get()
        ));
    }
}
```

### 4.18 文件：yunti-user-service/src/main/resources/application.yml

```yaml
server:
  port: 9091

spring:
  application:
    name: yunti-user-service

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

### 4.19 文件：yunti-tenant-service/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>cn.net.susan</groupId>
        <artifactId>yunti-backend</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>yunti-tenant-service</artifactId>
    <name>yunti-tenant-service</name>
    <dependencies>
        <dependency>
            <groupId>cn.net.susan</groupId>
            <artifactId>yunti-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 4.20 文件：yunti-tenant-service/src/main/java/cn/net/susan/tenant/TenantApplication.java

```java
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
```

### 4.21 文件：yunti-tenant-service/src/main/java/cn/net/susan/tenant/controller/TenantController.java

```java
package cn.net.susan.tenant.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * tenant-service 骨架接口。
 */
@RestController
@RequestMapping("/api/tenant")
public class TenantController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.ok(Map.of(
                "service", "tenant-service",
                "tenantCode", TenantContext.get() == null ? "" : TenantContext.get()
        ));
    }
}
```

### 4.22 文件：yunti-tenant-service/src/main/resources/application.yml

```yaml
server:
  port: 9092

spring:
  application:
    name: yunti-tenant-service

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

### 4.23 文件：yunti-customer-service/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>cn.net.susan</groupId>
        <artifactId>yunti-backend</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>yunti-customer-service</artifactId>
    <name>yunti-customer-service</name>
    <dependencies>
        <dependency>
            <groupId>cn.net.susan</groupId>
            <artifactId>yunti-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 4.24 文件：yunti-customer-service/src/main/java/cn/net/susan/customer/CustomerApplication.java

```java
package cn.net.susan.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * customer-service：客服核心（会话/消息/渠道/知识库/工单/质检/消息中心，customer_db）。
 */
@SpringBootApplication(scanBasePackages = "cn.net.susan")
public class CustomerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerApplication.class, args);
    }
}
```

### 4.25 文件：yunti-customer-service/src/main/java/cn/net/susan/customer/controller/CustomerController.java

```java
package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * customer-service 骨架接口。
 */
@RestController
@RequestMapping("/api/customer")
public class CustomerController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.ok(Map.of(
                "service", "customer-service",
                "tenantCode", TenantContext.get() == null ? "" : TenantContext.get()
        ));
    }
}
```

### 4.26 文件：yunti-customer-service/src/main/resources/application.yml

```yaml
server:
  port: 9093

spring:
  application:
    name: yunti-customer-service

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

### 4.27 文件：yunti-billing-service/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>cn.net.susan</groupId>
        <artifactId>yunti-backend</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>yunti-billing-service</artifactId>
    <name>yunti-billing-service</name>
    <dependencies>
        <dependency>
            <groupId>cn.net.susan</groupId>
            <artifactId>yunti-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 4.28 文件：yunti-billing-service/src/main/java/cn/net/susan/billing/BillingApplication.java

```java
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
```

### 4.29 文件：yunti-billing-service/src/main/java/cn/net/susan/billing/controller/BillingController.java

```java
package cn.net.susan.billing.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * billing-service 骨架接口。
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.ok(Map.of(
                "service", "billing-service",
                "tenantCode", TenantContext.get() == null ? "" : TenantContext.get()
        ));
    }
}
```

### 4.30 文件：yunti-billing-service/src/main/resources/application.yml

```yaml
server:
  port: 9094

spring:
  application:
    name: yunti-billing-service

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

### 4.31 文件：yunti-ops-service/pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>cn.net.susan</groupId>
        <artifactId>yunti-backend</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <relativePath>../pom.xml</relativePath>
    </parent>
    <artifactId>yunti-ops-service</artifactId>
    <name>yunti-ops-service</name>
    <dependencies>
        <dependency>
            <groupId>cn.net.susan</groupId>
            <artifactId>yunti-common</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 4.32 文件：yunti-ops-service/src/main/java/cn/net/susan/ops/OpsApplication.java

```java
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
```

### 4.33 文件：yunti-ops-service/src/main/java/cn/net/susan/ops/controller/OpsController.java

```java
package cn.net.susan.ops.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * ops-service 骨架接口。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.ok(Map.of(
                "service", "ops-service",
                "tenantCode", TenantContext.get() == null ? "" : TenantContext.get()
        ));
    }
}
```

### 4.34 文件：yunti-ops-service/src/main/resources/application.yml

```yaml
server:
  port: 9095

spring:
  application:
    name: yunti-ops-service

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

---

## 5\. 构建

回到工程根目录执行：

```bash
cd ~/workspace/yunti-backend
mvn clean package -DskipTests
```

看到 `BUILD SUCCESS` 且 7 个模块全部 SUCCESS 即构建成功。

每个服务模块的 `target/` 下会生成可执行 jar：

```latex
yunti-gateway/target/yunti-gateway-1.0.0-SNAPSHOT.jar
...
```

## 6\. 运行与验证

启动任意服务（端口见下表）：

```bash
java -jar yunti-user-service/target/yunti-user-service-1.0.0-SNAPSHOT.jar
```

启动成功之后会有下面的日志打印：

![图片.png](https://article-images.zsxq.com/FnJiGw5aVnkFHr05q0pC5Y7mJLVT)

| 模块 | 端口 | ping 地址 |
| --- | --- | --- |
| yunti-gateway | 9090 | [http://localhost:9090/api/gateway/ping](http://localhost:9090/api/gateway/ping) |
| yunti-user-service | 9091 | [http://localhost:9091/api/user/ping](http://localhost:9091/api/user/ping) |
| yunti-tenant-service | 9092 | [http://localhost:9092/api/tenant/ping](http://localhost:9092/api/tenant/ping) |
| yunti-customer-service | 9093 | [http://localhost:9093/api/customer/ping](http://localhost:9093/api/customer/ping) |
| yunti-billing-service | 9094 | [http://localhost:9094/api/billing/ping](http://localhost:9094/api/billing/ping) |
| yunti-ops-service | 9095 | [http://localhost:9095/api/ops/ping](http://localhost:9095/api/ops/ping) |

验证命令：

```bash
curl http://localhost:9091/actuator/health
curl http://localhost:9091/api/user/ping
```

启动成功的控制台会打印专业启动日志（服务名、端口、环境、耗时、健康检查地址）。

## 7\. 常见问题

1. **端口被占用** ：报 `Port 9091 was already in use` ，换端口启动： `java -jar xxx.jar --server.port=19091` ；
2. **依赖下载慢/失败** ：检查网络或配置阿里云镜像（见第 2 节）；
3. **JDK 版本不匹配** ：Spring Boot 4.0.3 要求 JDK 17+，请用 `java -version` 确认；
4. **找不到主类/打包无 boot jar** ：确认 pom 已引入 `spring-boot-maven-plugin` （见各服务 pom）。

## 8\. 文件清单核对

创建完成后，可用以下命令核对文件是否齐全：

```bash
find . -path ./target -prune -o -path ./.git -prune -o -type f -print | sort
```

```latex
pom.xml
.gitignore
README.md
yunti-common/pom.xml
yunti-common/src/main/java/cn/net/susan/common/api/ResultCode.java
yunti-common/src/main/java/cn/net/susan/common/api/ApiResponse.java
yunti-common/src/main/java/cn/net/susan/common/exception/BizException.java
yunti-common/src/main/java/cn/net/susan/common/exception/GlobalExceptionHandler.java
yunti-common/src/main/java/cn/net/susan/common/context/TenantContext.java
yunti-common/src/main/java/cn/net/susan/common/logging/StartupLogger.java
yunti-gateway/pom.xml
yunti-gateway/src/main/java/cn/net/susan/gateway/GatewayApplication.java
yunti-gateway/src/main/java/cn/net/susan/gateway/controller/GatewayController.java
yunti-gateway/src/main/resources/application.yml
yunti-user-service/pom.xml
yunti-user-service/src/main/java/cn/net/susan/user/UserApplication.java
yunti-user-service/src/main/java/cn/net/susan/user/controller/UserController.java
yunti-user-service/src/main/resources/application.yml
yunti-tenant-service/pom.xml
yunti-tenant-service/src/main/java/cn/net/susan/tenant/TenantApplication.java
yunti-tenant-service/src/main/java/cn/net/susan/tenant/controller/TenantController.java
yunti-tenant-service/src/main/resources/application.yml
yunti-customer-service/pom.xml
yunti-customer-service/src/main/java/cn/net/susan/customer/CustomerApplication.java
yunti-customer-service/src/main/java/cn/net/susan/customer/controller/CustomerController.java
yunti-customer-service/src/main/resources/application.yml
yunti-billing-service/pom.xml
yunti-billing-service/src/main/java/cn/net/susan/billing/BillingApplication.java
yunti-billing-service/src/main/java/cn/net/susan/billing/controller/BillingController.java
yunti-billing-service/src/main/resources/application.yml
yunti-ops-service/pom.xml
yunti-ops-service/src/main/java/cn/net/susan/ops/OpsApplication.java
yunti-ops-service/src/main/java/cn/net/susan/ops/controller/OpsController.java
yunti-ops-service/src/main/resources/application.yml
```

![](data:image/png;base64,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)

扫码加入星球

查看更多优质内容

https://wx.zsxq.com/mweb/views/joingroup/join\_group.html?group\_id=28851182188851