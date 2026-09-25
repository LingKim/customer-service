---
title: "15 打造实时通信底座：WebSocket 长连接网关与会话消息链路（一）"
source: "https://articles.zsxq.com/id_6fvw8hvwocc3.html"
author:
  - "[[苏三]]"
published:
created: 2026-09-13
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

## 一、项目概述

### 1.1 功能范围

前 14 篇把注册开通、渠道接入、机器人配置、成员邀请、质检中心都做完了，但有一个致命缺口：**客户和客服没法真的聊天**。

`session` / `session_message` 两张表建好了却一条数据都没有，上一次给质检中心接的"真实会话对话"兜底只能拿示例数据凑。

这一篇把这条链路补上：

1.  新增独立的长连接服务 **yunti-realtime-service**（端口 9096），直接承载 WebSocket 服务端；

2.  访客用渠道密钥换访客令牌，坐席用登录令牌，两种身份都能连长连接；

3.  会话与消息统一落到 customer-service，实时网关不直连客户库；

4.  消息可靠：客户端带 `clientMsgNo`，服务端**先落库、再 ACK、再广播**；坐席上线时把历史补齐（离线消息补发）；

5.  心跳 30 秒一次，90 秒没动静判掉线；前端断线指数退避重连，未确认的消息重连后自动重发；

6.  坐席工作台：会话列表（筛选/搜索/只看我的）+ 聊天窗口 + 结束会话；

7.  访客端：`/visitor?key=渠道密钥` 一开就能聊，可直接嵌到企业官网。

### 1.2 技术选型

|                      |                                                       |
|----------------------|-------------------------------------------------------|
| 技术                 | 说明                                                  |
| Spring WebSocket     | 长连接服务端（`TextWebSocketHandler`）                |
| Spring Cloud Gateway | HTTP 网关（9090），只管无状态的 API                   |
| PostgreSQL 17        | customer\_db 的 session / session\_message / customer |
| JWT（jjwt）          | 坐席令牌与访客令牌共用一把密钥                        |
| Vue 3 + TS           | 坐席工作台、访客端                                    |

### 1.3 双网关与端口

|                            |          |                                      |
|----------------------------|----------|--------------------------------------|
| 模块                       | 端口     | 职责                                 |
| yunti-gateway              | 9090     | HTTP 网关：无状态、可随便扩容        |
| yunti-user-service         | 9091     | 用户与成员                           |
| yunti-tenant-service       | 9092     | 租户与企业                           |
| yunti-customer-service     | 9093     | 客服核心：会话、消息、质检、渠道     |
| yunti-billing-service      | 9094     | 计费                                 |
| yunti-ops-service          | 9095     | 运营                                 |
| **yunti-realtime-service** | **9096** | **实时网关：长连接、心跳、消息路由** |
| yunti-ai                   | 9100     | Python AI 中心                       |

### 1.4 长连接消息协议

上下行都是 JSON，字段含义：

|      |                |                                    |
|------|----------------|------------------------------------|
| 方向 | type           | 说明                               |
| 上行 | PING           | 心跳，服务端回 PONG                |
| 上行 | JOIN           | 进入会话（坐席用；访客连上自动进） |
| 上行 | SEND           | 发消息，带 clientMsgNo             |
| 上行 | HISTORY        | 向上翻聊天记录，带 beforeId        |
| 上行 | CLOSE\_SESSION | 坐席结束会话                       |
| 下行 | CONNECTED      | 建连成功，带身份与在线数           |
| 下行 | JOINED         | 进入成功，带会话信息 + 历史消息    |
| 下行 | ACK            | 消息已落库，带服务端消息号         |
| 下行 | MESSAGE        | 会话内其他人的新消息               |
| 下行 | HISTORY        | 历史消息分页结果                   |
| 下行 | SESSION        | 会话状态变化（接入 / 结束）        |
| 下行 | PRESENCE       | 会话在线人数变化                   |
| 下行 | ERROR          | 业务错误，带 code 与 message       |

### 1.5 与上一篇的关系

|                                                 |                            |
|-------------------------------------------------|----------------------------|
| 上一篇（14 真实的 AI 质检）已有                 | 本次处理                   |
| customer-service 的会话表读取（质检靠它取对话） | 保留，本次补上**写入**链路 |
| session / session\_message / customer 三张表    | 直接使用，无需改表         |
| 渠道接入的 channel\_key                         | 访客用它换取访客令牌       |
| 成员邀请 / 角色体系                             | 坐席身份与租户校验沿用     |

## 二、环境准备

``` code-block-container
java -version    # 21
mvn -v           # 3.8+
psql --version   # PostgreSQL 17
```

数据库沿用已有的 customer\_db，**本次不需要新增任何表**（session / session\_message / customer 在架构篇就已经建好）。

## 三、后端工程结构

新增一个模块，并在父 pom 里注册。

### 文件：yunti-backend/pom.xml

``` code-block-container
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
        <module>yunti-realtime-service</module>
        <module>yunti-billing-service</module>
        <module>yunti-ops-service</module>
    </modules>
    <properties>
        <java.version>21</java.version>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <maven.compiler.parameters>true</maven.compiler.parameters>
        <spring-cloud.version>2025.1.2</spring-cloud.version>
    </properties>
    <dependencyManagement>
        <dependencies>
            <!-- Spring Cloud BOM：网关等组件版本统一管理 -->
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

## 四、customer-service：会话与消息链路

### 4.1 会话与客户实体

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/entity/Session.java

``` code-block-container
package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * session 会话表实体：一次"客户来找我们聊天"的完整过程。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("session")
public class Session {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 会话编号（对外，UUID 风格短号） */
    private String sessionNo;

    private Long channelId;

    private Long customerId;

    /** 状态码：1-排队中、2-机器人接待、3-人工接待、4-已结束 */
    private Integer status;

    private Long skillGroupId;

    /** 当前坐席用户 ID */
    private Long agentId;

    private String intent;

    private String emotion;

    /** 来源：网站、微信、小程序等 */
    private String source;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer csatScore;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/entity/Customer.java

``` code-block-container
package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * customer 客户表实体（访客进门后先建档，再开会话）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("customer")
public class Customer {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String customerNo;

    private String name;

    private String phone;

    /** 会员等级码：1-普通、2-银卡、3-金卡、4-铂金、5-企业 */
    private Integer level;

    /** 来源渠道 */
    private String channel;

    private Integer ordersCount;

    private BigDecimal totalValue;

    private Integer points;

    private BigDecimal csat;

    private Integer sentiment;

    private LocalDateTime lastActive;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
```

消息实体在第 14 篇已经建过，这次补上状态与时间字段（消息状态、创建/更新时间），并把分页查询加上。

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/entity/SessionMessage.java

``` code-block-container
package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * session_message 会话消息表实体：AI 质检的真实对话来源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("session_message")
public class SessionMessage {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long sessionId;

    /** 消息编号（客户端幂等键） */
    private String msgNo;

    /** 类型码：1-文本、2-图片、3-卡片、4-事件、5-系统 */
    private Integer msgType;

    /** 发送方码：1-客户、2-坐席、3-机器人、4-系统 */
    private Integer senderType;

    private Long senderId;

    /** 消息内容（文本或 JSON 结构） */
    private String content;

    /** 消息状态码：1-已发送、2-已送达、3-已读、4-失败 */
    private Integer status;

    private LocalDateTime sendTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
```

### 4.2 Mapper 与 SQL

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/SessionMapper.java

``` code-block-container
package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.Session;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * session 会话 Mapper。
 */
@Mapper
public interface SessionMapper extends BaseMapper<Session> {

    /**
     * 坐席工作台的会话列表：带客户名与最后一条消息（SQL 见 SessionMapper.xml）。
     */
    List<Map<String, Object>> selectAgentSessions(
            @Param("tenantCode") String tenantCode,
            @Param("status") Integer status,
            @Param("keyword") String keyword,
            @Param("limit") int limit
    );

    /**
     * 访客复用会话：同一渠道下未结束的最近一条会话。
     */
    Session selectOpenSession(
            @Param("tenantCode") String tenantCode,
            @Param("channelId") Long channelId,
            @Param("customerId") Long customerId
    );
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/resources/mapper/SessionMapper.xml

``` code-block-container
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="cn.net.susan.customer.mapper.SessionMapper">

    <!-- 坐席工作台会话列表：左连客户档案，再用 LATERAL 取每个会话的最后一条消息 -->
    <select id="selectAgentSessions" resultType="java.util.Map">
        SELECT s.id            AS session_id,
               s.session_no    AS session_no,
               s.tenant_code   AS tenant_code,
               s.channel_id    AS channel_id,
               s.customer_id   AS customer_id,
               s.status        AS status,
               s.agent_id      AS agent_id,
               s.intent        AS intent,
               s.emotion       AS emotion,
               s.source        AS source,
               s.start_time    AS start_time,
               s.end_time      AS end_time,
               c.name          AS customer_name,
               c.level         AS customer_level,
               lm.content      AS last_content,
               lm.sender_type  AS last_sender_type,
               lm.send_time    AS last_time,
               COALESCE(mc.msg_count, 0) AS msg_count
          FROM session s
          LEFT JOIN customer c
                 ON c.id = s.customer_id
          LEFT JOIN LATERAL (
                SELECT m.content, m.sender_type, m.send_time
                  FROM session_message m
                 WHERE m.tenant_code = s.tenant_code
                   AND m.session_id = s.id
                   AND m.is_deleted = FALSE
                 ORDER BY m.send_time DESC, m.id DESC
                 LIMIT 1
               ) lm ON TRUE
          LEFT JOIN LATERAL (
                SELECT COUNT(1) AS msg_count
                  FROM session_message m2
                 WHERE m2.tenant_code = s.tenant_code
                   AND m2.session_id = s.id
                   AND m2.is_deleted = FALSE
               ) mc ON TRUE
         WHERE s.tenant_code = #{tenantCode}
           AND s.is_deleted = FALSE
           <if test="status != null">
           AND s.status = #{status}
           </if>
           <if test="keyword != null">
           AND (
                s.session_no ILIKE '%' || #{keyword}::text || '%'
                OR c.name ILIKE '%' || #{keyword}::text || '%'
               )
           </if>
         ORDER BY COALESCE(lm.send_time, s.start_time) DESC
         LIMIT #{limit}
    </select>
    <!-- 同一渠道、同一客户未结束的会话，访客刷新页面时复用，不重复开会话 -->
    <select id="selectOpenSession" resultType="cn.net.susan.customer.entity.Session">
        SELECT id, tenant_code, session_no, channel_id, customer_id, status, skill_group_id,
               agent_id, intent, emotion, source, start_time, end_time, csat_score,
               create_time, update_time, creator, editor, is_deleted
          FROM session
         WHERE tenant_code = #{tenantCode}
           AND channel_id = #{channelId}
           AND customer_id = #{customerId}
           AND status IN (1, 2, 3)
           AND is_deleted = FALSE
         ORDER BY start_time DESC
         LIMIT 1
    </select>
</mapper>
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/CustomerMapper.java

``` code-block-container
package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.Customer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * customer 客户 Mapper。
 */
@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/SessionMessageMapper.java

``` code-block-container
package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.SessionMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * session_message 会话消息 Mapper。
 */
@Mapper
public interface SessionMessageMapper extends BaseMapper<SessionMessage> {

    /**
     * 按会话读取对话记录（时间正序，SQL 见 SessionMessageMapper.xml）。
     */
    List<SessionMessage> selectDialog(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId,
            @Param("limit") int limit
    );

    /**
     * 会话消息分页（倒序取一页，供聊天记录回溯）。
     */
    List<SessionMessage> selectBySession(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit
    );
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/resources/mapper/SessionMessageMapper.xml

``` code-block-container
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="cn.net.susan.customer.mapper.SessionMessageMapper">

    <!-- 按会话取对话记录：只取文本/卡片类消息，按时间正序，供 AI 质检还原真实会话 -->
    <select id="selectDialog" resultType="cn.net.susan.customer.entity.SessionMessage">
        SELECT id, tenant_code, session_id, msg_no, msg_type, sender_type, sender_id, content, send_time
          FROM session_message
         WHERE tenant_code = #{tenantCode}
           AND session_id = #{sessionId}
           AND is_deleted = FALSE
         ORDER BY send_time, id
         LIMIT #{limit}
    </select>
    <!-- 聊天记录：按时间倒序取一页（beforeId 用于向上翻页），Service 里再反转为正序 -->
    <select id="selectBySession" resultType="cn.net.susan.customer.entity.SessionMessage">
        SELECT id, tenant_code, session_id, msg_no, msg_type, sender_type, sender_id, content, send_time
          FROM session_message
         WHERE tenant_code = #{tenantCode}
           AND session_id = #{sessionId}
           AND is_deleted = FALSE
           <if test="beforeId != null">
           AND id &lt; #{beforeId}
           </if>
         ORDER BY id DESC
         LIMIT #{limit}
    </select>
</mapper>
```

### 4.3 访客令牌

访客没有账号密码，用渠道密钥换一枚短期令牌；令牌里带 `typ=4`（访客）和 `sno`（会话号），实时网关据此判断"这是访客，而且只允许进出这一个会话"。

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/security/VisitorTokenService.java

``` code-block-container
package cn.net.susan.customer.security;

import cn.net.susan.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

/**
 * 访客令牌：访客端（官网挂件 / 微信 / 小程序）没有账号密码，
 * 用渠道密钥换取一枚短期令牌，再去连实时网关。
 *
 * <p>令牌用与坐席同一把密钥签发，载荷里带 {@code typ=4}（访客）和 {@code sno}（会话号），
 * 实时网关据此判断"这是访客，并且只允许进出这一个会话"。</p>
 */
@Service
public class VisitorTokenService {

    /** 令牌类型：访客 */
    public static final int TYPE_VISITOR = 4;

    private static final long DEFAULT_EXPIRE_SECONDS = 12 * 3600L;

    private final SecretKey secretKey;
    private final long expireSeconds;

    public VisitorTokenService(
            @Value("${yunti.auth.jwt-secret}") String secret,
            @Value("${yunti.auth.visitor-expire-seconds:43200}") long expireSeconds
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds > 0 ? expireSeconds : DEFAULT_EXPIRE_SECONDS;
    }

    public String createToken(long customerId, String customerName, String tenantCode, String sessionNo) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(customerId))
                .claim("name", customerName)
                .claim("tnt", tenantCode)
                .claim("typ", TYPE_VISITOR)
                .claim("sno", sessionNo)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireSeconds * 1000L))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析访客令牌；非法或过期抛未认证。
     */
    public VisitorPrincipal parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return new VisitorPrincipal(
                    Long.parseLong(claims.getSubject()),
                    claims.get("name", String.class),
                    claims.get("tnt", String.class),
                    claims.get("sno", String.class)
            );
        } catch (Exception e) {
            throw new BizException(40100, "访客令牌无效或已过期");
        }
    }

    public LocalDateTime expireAt() {
        return LocalDateTime.ofInstant(new Date(System.currentTimeMillis() + expireSeconds * 1000L).toInstant(),
                ZoneId.systemDefault());
    }

    /**
     * 访客身份。
     */
    public record VisitorPrincipal(long customerId, String customerName, String tenantCode, String sessionNo) {
    }
}
```

### 4.4 会话服务（核心）

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/service/SessionService.java

``` code-block-container
package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.Channel;
import cn.net.susan.customer.entity.ChannelKey;
import cn.net.susan.customer.entity.Customer;
import cn.net.susan.customer.entity.Session;
import cn.net.susan.customer.entity.SessionMessage;
import cn.net.susan.customer.mapper.ChannelKeyMapper;
import cn.net.susan.customer.mapper.ChannelMapper;
import cn.net.susan.customer.mapper.CustomerMapper;
import cn.net.susan.customer.mapper.SessionMapper;
import cn.net.susan.customer.mapper.SessionMessageMapper;
import cn.net.susan.customer.security.VisitorTokenService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 会话与消息：实时通信底座的业务落库方。
 *
 * <p>职责划分：长连接、心跳、广播由 yunti-realtime-service 负责；
 * 会话与消息的"唯一写入口"在 customer-service，避免多服务同时写客户库。</p>
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 会话状态码 */
    public static final int STATUS_QUEUING = 1;
    public static final int STATUS_BOT = 2;
    public static final int STATUS_AGENT = 3;
    public static final int STATUS_CLOSED = 4;

    /** 消息发送方码：1-客户、2-坐席、3-机器人、4-系统 */
    public static final int SENDER_CUSTOMER = 1;
    public static final int SENDER_AGENT = 2;
    public static final int SENDER_BOT = 3;
    public static final int SENDER_SYSTEM = 4;

    private static final int DEFAULT_HISTORY_LIMIT = 30;
    private static final int MAX_HISTORY_LIMIT = 100;
    private static final int SESSION_LIST_LIMIT = 100;

    private final SessionMapper sessionMapper;
    private final SessionMessageMapper sessionMessageMapper;
    private final CustomerMapper customerMapper;
    private final ChannelMapper channelMapper;
    private final ChannelKeyMapper channelKeyMapper;
    private final VisitorTokenService visitorTokenService;
    private final SnowflakeIdGenerator idGenerator;

    public SessionService(
            SessionMapper sessionMapper,
            SessionMessageMapper sessionMessageMapper,
            CustomerMapper customerMapper,
            ChannelMapper channelMapper,
            ChannelKeyMapper channelKeyMapper,
            VisitorTokenService visitorTokenService,
            SnowflakeIdGenerator idGenerator
    ) {
        this.sessionMapper = sessionMapper;
        this.sessionMessageMapper = sessionMessageMapper;
        this.customerMapper = customerMapper;
        this.channelMapper = channelMapper;
        this.channelKeyMapper = channelKeyMapper;
        this.visitorTokenService = visitorTokenService;
        this.idGenerator = idGenerator;
    }

    /**
     * 访客打开会话：渠道密钥校验 → 客户建档/复用 → 开会话/复用未结束会话 → 发访客令牌。
     */
    @Transactional
    public OpenResult openSession(OpenCommand command) {
        if (command.appKey() == null || command.appKey().isBlank()) {
            throw new BizException(40001, "缺少渠道密钥");
        }
        ChannelKey channelKey = channelKeyMapper.selectOne(Wrappers.<ChannelKey>lambdaQuery()
                .eq(ChannelKey::getAppKey, command.appKey().trim())
                .eq(ChannelKey::getStatus, 1)
                .eq(ChannelKey::getDeleted, false)
                .last("LIMIT 1"));
        if (channelKey == null) {
            throw new BizException(40401, "渠道密钥无效或已停用");
        }
        if (channelKey.getExpireTime() != null && channelKey.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BizException(40301, "渠道密钥已过期，请联系企业管理员重新生成");
        }
        Channel channel = channelMapper.selectById(channelKey.getChannelId());
        if (channel == null || Boolean.TRUE.equals(channel.getDeleted())) {
            throw new BizException(40401, "渠道不存在");
        }
        if (!Integer.valueOf(1).equals(channel.getStatus())) {
            throw new BizException(40301, "渠道已停用，暂时无法接待");
        }

        String tenant = channelKey.getTenantCode();
        Customer customer = resolveVisitor(tenant, command.visitorKey(), command.visitorName(), channel.getName());

        Session session = sessionMapper.selectOpenSession(tenant, channel.getId(), customer.getId());
        boolean created = false;
        if (session == null) {
            session = createSession(tenant, channel, customer);
            created = true;
            appendSystemMessage(tenant, session, "访客进入会话，等待客服接入");
            log.info("访客会话创建 tenant={} sessionNo={} channel={} customerNo={}",
                    tenant, session.getSessionNo(), channel.getName(), customer.getCustomerNo());
        }

        String token = visitorTokenService.createToken(
                customer.getId(), customer.getName(), tenant, session.getSessionNo());
        return new OpenResult(
                session.getSessionNo(),
                customer.getCustomerNo(),
                customer.getName(),
                token,
                visitorTokenService.expireAt(),
                session.getStatus(),
                created
        );
    }

    /**
     * 坐席工作台：我的会话列表（可按状态与关键词过滤）。
     */
    public List<SessionVO> agentSessions(LoginUser user, Integer status, String keyword, Long agentId) {
        String tenant = tenantOf(user);
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        List<Map<String, Object>> rows = sessionMapper.selectAgentSessions(tenant, status, normalizedKeyword, SESSION_LIST_LIMIT);
        List<SessionVO> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            SessionVO vo = toSessionVO(row);
            // agentOnly=true 时只保留"分配给我"的会话，用于工作台的"我的会话"筛选
            if (agentId != null && !agentId.equals(vo.agentId())) {
                continue;
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 坐席查看历史消息（按会话号，校验租户）。
     */
    public List<MessageVO> history(LoginUser user, String sessionNo, Long beforeId, Integer limit) {
        String tenant = tenantOf(user);
        Session session = requireSession(tenant, sessionNo);
        return historyOf(tenant, session, beforeId, limit);
    }

    /**
     * 内部接口：按租户 + 会话号拉历史消息（实时网关在 JOIN 时回给前端）。
     */
    public List<MessageVO> historyByTenant(String tenantCode, String sessionNo, Long beforeId, Integer limit) {
        Session session = requireSession(tenantCode, sessionNo);
        return historyOf(tenantCode, session, beforeId, limit);
    }

    /**
     * 拉取会话详情（坐席工作台右侧头部信息）。
     */
    public SessionVO sessionDetail(LoginUser user, String sessionNo) {
        String tenant = tenantOf(user);
        Session session = requireSession(tenant, sessionNo);
        Customer customer = session.getCustomerId() == null ? null : customerMapper.selectById(session.getCustomerId());
        return new SessionVO(
                session.getSessionNo(),
                session.getStatus(),
                session.getAgentId(),
                session.getChannelId(),
                session.getCustomerId(),
                customer == null ? null : customer.getName(),
                customer == null ? null : customer.getLevel(),
                session.getSource(),
                session.getIntent(),
                session.getEmotion(),
                session.getStartTime(),
                session.getEndTime(),
                null,
                null,
                null,
                0
        );
    }

    /**
     * 内部接口：按租户 + 会话号取会话（实时网关用）。
     */
    public Session requireSession(String tenantCode, String sessionNo) {
        if (tenantCode == null || tenantCode.isBlank() || sessionNo == null || sessionNo.isBlank()) {
            throw new BizException(40001, "缺少租户或会话号");
        }
        Session session = sessionMapper.selectOne(Wrappers.<Session>lambdaQuery()
                .eq(Session::getTenantCode, tenantCode)
                .eq(Session::getSessionNo, sessionNo)
                .eq(Session::getDeleted, false)
                .last("LIMIT 1"));
        if (session == null) {
            throw new BizException(40401, "会话不存在");
        }
        return session;
    }

    /**
     * 内部接口：落一条消息。
     */
    @Transactional
    public MessageVO appendMessage(
            String tenantCode,
            String sessionNo,
            int senderType,
            Long senderId,
            int msgType,
            String content
    ) {
        Session session = requireSession(tenantCode, sessionNo);
        if (Integer.valueOf(STATUS_CLOSED).equals(session.getStatus())) {
            throw new BizException(40301, "会话已结束，无法继续发送消息");
        }
        SessionMessage message = buildMessage(tenantCode, session.getId(), senderType, senderId, msgType, content);
        sessionMessageMapper.insert(message);

        LocalDateTime now = LocalDateTime.now();
        Session update = new Session();
        update.setId(session.getId());
        update.setUpdateTime(now);
        // 坐席开口即视为接入，会话从"排队中"进入"人工接待"
        if (senderType == SENDER_AGENT && session.getAgentId() == null) {
            update.setAgentId(senderId);
            update.setStatus(STATUS_AGENT);
        }
        sessionMapper.updateById(update);
        touchCustomer(tenantCode, session.getCustomerId(), now);
        return toMessageVO(message);
    }

    /**
     * 内部接口：坐席接入会话（认领）。
     */
    @Transactional
    public Session assignAgent(String tenantCode, String sessionNo, Long agentId) {
        Session session = requireSession(tenantCode, sessionNo);
        if (Integer.valueOf(STATUS_CLOSED).equals(session.getStatus())) {
            throw new BizException(40301, "会话已结束");
        }
        if (session.getAgentId() != null && !session.getAgentId().equals(agentId)) {
            throw new BizException(40301, "该会话已由其他客服接待");
        }
        Session update = new Session();
        update.setId(session.getId());
        update.setAgentId(agentId);
        update.setStatus(STATUS_AGENT);
        update.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(update);
        session.setAgentId(agentId);
        session.setStatus(STATUS_AGENT);
        log.info("坐席接入会话 tenant={} sessionNo={} agentId={}", tenantCode, sessionNo, agentId);
        return session;
    }

    /**
     * 内部接口：结束会话。
     */
    @Transactional
    public Session closeSession(String tenantCode, String sessionNo) {
        Session session = requireSession(tenantCode, sessionNo);
        LocalDateTime now = LocalDateTime.now();
        Session update = new Session();
        update.setId(session.getId());
        update.setStatus(STATUS_CLOSED);
        update.setEndTime(now);
        update.setUpdateTime(now);
        sessionMapper.updateById(update);
        session.setStatus(STATUS_CLOSED);
        session.setEndTime(now);
        log.info("会话已结束 tenant={} sessionNo={}", tenantCode, sessionNo);
        return session;
    }

    private List<MessageVO> historyOf(String tenant, Session session, Long beforeId, Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
        List<SessionMessage> rows = sessionMessageMapper.selectBySession(tenant, session.getId(), beforeId, size);
        // SQL 是倒序取的，展示要正序
        Collections.reverse(rows);
        return rows.stream().map(this::toMessageVO).toList();
    }

    private Session createSession(String tenant, Channel channel, Customer customer) {
        LocalDateTime now = LocalDateTime.now();
        for (int attempt = 0; attempt < 5; attempt++) {
            Session session = Session.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .sessionNo(generateSessionNo())
                    .channelId(channel.getId())
                    .customerId(customer.getId())
                    .status(STATUS_QUEUING)
                    .source(channel.getName())
                    .startTime(now)
                    .createTime(now)
                    .updateTime(now)
                    .creator("VISITOR")
                    .deleted(false)
                    .build();
            try {
                sessionMapper.insert(session);
                return session;
            } catch (DuplicateKeyException e) {
                log.warn("会话号冲突，重试第 {} 次", attempt + 1);
            }
        }
        throw new BizException(50001, "会话创建失败，请稍后重试");
    }

    private void appendSystemMessage(String tenant, Session session, String content) {
        sessionMessageMapper.insert(buildMessage(tenant, session.getId(), SENDER_SYSTEM, null, 5, content));
    }

    private SessionMessage buildMessage(
            String tenant,
            Long sessionId,
            int senderType,
            Long senderId,
            int msgType,
            String content
    ) {
        LocalDateTime now = LocalDateTime.now();
        return SessionMessage.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .sessionId(sessionId)
                .msgNo(generateMsgNo())
                .msgType(msgType)
                .senderType(senderType)
                .senderId(senderId)
                .content(content == null ? "" : content)
                .status(1)
                .sendTime(now)
                .createTime(now)
                .updateTime(now)
                .deleted(false)
                .build();
    }

    private void touchCustomer(String tenant, Long customerId, LocalDateTime time) {
        if (customerId == null) {
            return;
        }
        Customer update = new Customer();
        update.setId(customerId);
        update.setLastActive(time);
        update.setUpdateTime(time);
        customerMapper.updateById(update);
    }

    /**
     * 访客建档：visitorKey 由访客端本地保存，用来复用同一个客户档案。
     */
    private Customer resolveVisitor(String tenant, String visitorKey, String visitorName, String channelName) {
        String customerNo = visitorKey == null || visitorKey.isBlank() ? generateCustomerNo() : visitorKey.trim();
        Customer existing = customerMapper.selectOne(Wrappers.<Customer>lambdaQuery()
                .eq(Customer::getTenantCode, tenant)
                .eq(Customer::getCustomerNo, customerNo)
                .eq(Customer::getDeleted, false)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        Customer customer = Customer.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .customerNo(customerNo)
                .name(visitorName == null || visitorName.isBlank() ? "访客" + customerNo.substring(Math.max(0, customerNo.length() - 4)) : visitorName.trim())
                .level(1)
                .channel(channelName)
                .ordersCount(0)
                .totalValue(java.math.BigDecimal.ZERO)
                .points(0)
                .lastActive(now)
                .createTime(now)
                .updateTime(now)
                .creator("VISITOR")
                .deleted(false)
                .build();
        customerMapper.insert(customer);
        return customer;
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2) {
            throw new BizException(40301, "仅企业成员可使用在线客服");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用在线客服");
        }
        return tenant;
    }

    private String generateSessionNo() {
        return "S" + LocalDateTime.now().format(NO_TIME) + String.format("%03d", RANDOM.nextInt(1000));
    }

    private String generateMsgNo() {
        return "M" + LocalDateTime.now().format(NO_TIME) + String.format("%04d", RANDOM.nextInt(10000));
    }

    private String generateCustomerNo() {
        return "V" + LocalDateTime.now().format(NO_TIME) + String.format("%03d", RANDOM.nextInt(1000));
    }

    private SessionVO toSessionVO(Map<String, Object> row) {
        return new SessionVO(
                stringValue(row.get("session_no")),
                intValue(row.get("status")),
                longValue(row.get("agent_id")),
                longValue(row.get("channel_id")),
                longValue(row.get("customer_id")),
                stringValue(row.get("customer_name")),
                intValue(row.get("customer_level")),
                stringValue(row.get("source")),
                stringValue(row.get("intent")),
                stringValue(row.get("emotion")),
                dateValue(row.get("start_time")),
                dateValue(row.get("end_time")),
                stringValue(row.get("last_content")),
                intValue(row.get("last_sender_type")),
                dateValue(row.get("last_time")),
                intValue(row.get("msg_count"))
        );
    }

    private MessageVO toMessageVO(SessionMessage message) {
        return new MessageVO(
                String.valueOf(message.getId()),
                message.getMsgNo(),
                message.getSessionId(),
                message.getSenderType(),
                message.getSenderId(),
                message.getMsgType(),
                message.getContent(),
                message.getSendTime()
        );
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private LocalDateTime dateValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    /** 访客开会话入参 */
    public record OpenCommand(String appKey, String visitorKey, String visitorName) {
    }

    /** 访客开会话结果 */
    public record OpenResult(
            String sessionNo,
            String customerNo,
            String customerName,
            String visitorToken,
            LocalDateTime tokenExpireAt,
            int sessionStatus,
            boolean created
    ) {
    }

    /** 会话列表 / 详情 */
    public record SessionVO(
            String sessionNo,
            Integer status,
            Long agentId,
            Long channelId,
            Long customerId,
            String customerName,
            Integer customerLevel,
            String source,
            String intent,
            String emotion,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String lastContent,
            Integer lastSenderType,
            LocalDateTime lastTime,
            Integer msgCount
    ) {
    }

    /** 消息 */
    public record MessageVO(
            String msgId,
            String msgNo,
            Long sessionId,
            Integer senderType,
            Long senderId,
            Integer msgType,
            String content,
            LocalDateTime sendTime
    ) {
    }
}
```

### 4.5 对外接口与内部接口

对外接口给前端用（访客开会话、坐席读列表与聊天记录）；内部接口只给实时网关调用，不对外开放。

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/controller/SessionController.java

``` code-block-container
package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 在线客服会话接口：访客开会话 + 坐席工作台读取会话与聊天记录。
 */
@RestController
@RequestMapping("/api/customer/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final JwtTokenParser jwtTokenParser;

    public SessionController(SessionService sessionService, JwtTokenParser jwtTokenParser) {
        this.sessionService = sessionService;
        this.jwtTokenParser = jwtTokenParser;
    }

    /**
     * 访客打开会话：前端用渠道密钥调用，无需登录，返回访客令牌。
     */
    @PostMapping("/open")
    public ApiResponse<SessionService.OpenResult> open(@Valid @RequestBody OpenBody body) {
        return ApiResponse.ok(sessionService.openSession(
                new SessionService.OpenCommand(body.appKey(), body.visitorKey(), body.visitorName())));
    }

    /**
     * 坐席工作台：会话列表。
     */
    @GetMapping
    public ApiResponse<List<SessionService.SessionVO>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean mine
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(sessionService.agentSessions(user, status, keyword, mine ? user.userId() : null));
    }

    /**
     * 会话详情。
     */
    @GetMapping("/{sessionNo}")
    public ApiResponse<SessionService.SessionVO> detail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(sessionService.sessionDetail(user, sessionNo));
    }

    /**
     * 聊天记录（坐席翻页用，beforeId 传上一页最小消息 ID）。
     */
    @GetMapping("/{sessionNo}/messages")
    public ApiResponse<List<SessionService.MessageVO>> messages(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionNo,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer limit
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(sessionService.history(user, sessionNo, beforeId, limit));
    }

    /**
     * 访客开会话请求体。
     */
    public record OpenBody(
            @NotBlank(message = "缺少渠道密钥")
            @Size(max = 64)
            String appKey,

            @Size(max = 32)
            String visitorKey,

            @Size(max = 64)
            String visitorName
    ) {
    }
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/controller/SessionInternalController.java

``` code-block-container
package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.customer.entity.Session;
import cn.net.susan.customer.service.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 内部接口：仅供 yunti-realtime-service 调用（长连接里的会话与消息落库）。
 *
 * <p>实时网关不直接连客户库，避免"两个服务同时写一个库"，
 * 这条边界在生产可以平滑替换成 RPC 或消息队列。</p>
 */
@RestController
@RequestMapping("/api/customer/internal/sessions")
public class SessionInternalController {

    private final SessionService sessionService;

    public SessionInternalController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    /**
     * 取会话（实时网关鉴权后确认会话存在、状态可用）。
     */
    @GetMapping("/{sessionNo}")
    public ApiResponse<SessionInfo> detail(
            @PathVariable String sessionNo,
            @RequestParam String tenantCode
    ) {
        return ApiResponse.ok(toInfo(sessionService.requireSession(tenantCode, sessionNo)));
    }

    /**
     * 拉历史消息（JOIN 时回给前端）。
     */
    @GetMapping("/{sessionNo}/messages")
    public ApiResponse<List<SessionService.MessageVO>> messages(
            @PathVariable String sessionNo,
            @RequestParam String tenantCode,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer limit
    ) {
        return ApiResponse.ok(sessionService.historyByTenant(tenantCode, sessionNo, beforeId, limit));
    }

    /**
     * 消息落库。
     */
    @PostMapping("/{sessionNo}/messages")
    public ApiResponse<SessionService.MessageVO> append(
            @PathVariable String sessionNo,
            @Valid @RequestBody AppendBody body
    ) {
        return ApiResponse.ok(sessionService.appendMessage(
                body.tenantCode(),
                sessionNo,
                body.senderType() == null ? SessionService.SENDER_CUSTOMER : body.senderType(),
                body.senderId(),
                body.msgType() == null ? 1 : body.msgType(),
                body.content()
        ));
    }

    /**
     * 坐席接入会话。
     */
    @PostMapping("/{sessionNo}/assign")
    public ApiResponse<SessionInfo> assign(
            @PathVariable String sessionNo,
            @Valid @RequestBody AssignBody body
    ) {
        return ApiResponse.ok(toInfo(sessionService.assignAgent(body.tenantCode(), sessionNo, body.agentId())));
    }

    /**
     * 结束会话。
     */
    @PostMapping("/{sessionNo}/close")
    public ApiResponse<SessionInfo> close(
            @PathVariable String sessionNo,
            @Valid @RequestBody CloseBody body
    ) {
        return ApiResponse.ok(toInfo(sessionService.closeSession(body.tenantCode(), sessionNo)));
    }

    private SessionInfo toInfo(Session session) {
        return new SessionInfo(
                session.getSessionNo(),
                session.getTenantCode(),
                session.getId(),
                session.getStatus(),
                session.getAgentId(),
                session.getCustomerId(),
                session.getStartTime(),
                session.getEndTime()
        );
    }

    /** 会话信息 */
    public record SessionInfo(
            String sessionNo,
            String tenantCode,
            Long sessionId,
            Integer status,
            Long agentId,
            Long customerId,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
    }

    /** 消息落库请求体 */
    public record AppendBody(
            @NotBlank(message = "缺少租户编码")
            @Size(max = 32)
            String tenantCode,

            Integer senderType,
            Long senderId,
            Integer msgType,

            @Size(max = 4000, message = "单条消息最长 4000 字")
            String content
    ) {
    }

    /** 接入会话请求体 */
    public record AssignBody(
            @NotBlank(message = "缺少租户编码")
            @Size(max = 32)
            String tenantCode,

            @NotNull(message = "缺少坐席 ID")
            Long agentId
    ) {
    }

    /** 结束会话请求体 */
    public record CloseBody(
            @NotBlank(message = "缺少租户编码")
            @Size(max = 32)
            String tenantCode
    ) {
    }
}
```

## 五、yunti-realtime-service：长连接网关

### 5.1 工程、入口与配置

### 文件：yunti-backend/yunti-realtime-service/pom.xml

``` code-block-container
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
    <artifactId>yunti-realtime-service</artifactId>
    <name>yunti-realtime-service</name>
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
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.6</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

### 文件：yunti-backend/yunti-realtime-service/src/main/resources/application.yml

``` code-block-container
server:
  port: 9096

spring:
  application:
    name: yunti-realtime-service

yunti:
  auth:
    # JWT 签名密钥：必须与 user-service / customer-service 一致，才能认同一批令牌
    jwt-secret: ${YUNTI_JWT_SECRET:yunti-customer-service-jwt-secret-please-change-in-prod-0123456789}
  customer:
    # customer-service 内部接口地址（会话与消息落库）
    internal-base-url: ${YUNTI_CUSTOMER_INTERNAL_URL:http://127.0.0.1:9093}
  realtime:
    # 长连接地址，前端连 ws://host:9096/ws/realtime?token=xxx
    path: ${YUNTI_WS_PATH:/ws/realtime}
    # 超过这个时间没收到任何消息（含心跳）就判定掉线并断开
    idle-timeout-seconds: ${YUNTI_WS_IDLE_TIMEOUT:90}
    # 单个连接每秒最多发多少条消息（简单限流，防止刷接口）
    max-messages-per-second: ${YUNTI_WS_MAX_MPS:10}
    # 单次下行缓冲上限（字节），超出说明客户端消费不过来
    send-buffer-size-limit: ${YUNTI_WS_SEND_BUFFER:524288}
    send-time-limit-ms: ${YUNTI_WS_SEND_TIMEOUT:10000}

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

### 文件：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/RealtimeApplication.java

``` code-block-container
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
```

### 文件：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/config/RealtimeProperties.java

``` code-block-container
package cn.net.susan.realtime.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 实时网关配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "yunti.realtime")
public class RealtimeProperties {

    /** WebSocket 路径 */
    private String path = "/ws/realtime";

    /** 空闲超时（秒） */
    private int idleTimeoutSeconds = 90;

    /** 单连接每秒最大消息数 */
    private int maxMessagesPerSecond = 10;

    /** 下行缓冲上限（字节） */
    private int sendBufferSizeLimit = 512 * 1024;

    /** 单次发送超时（毫秒） */
    private int sendTimeLimitMs = 10000;
}
```

### 5.2 身份与握手鉴权

浏览器建连时带不了自定义请求头，令牌只能走 `?token=xxx`。鉴权在**握手阶段**完成，不通过直接 401，连不上就不占用服务端连接资源。

### 文件：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/security/RealtimePrincipal.java

``` code-block-container
package cn.net.susan.realtime.security;

/**
 * 长连接上的身份：要么是坐席（企业成员，可进出本企业任意会话），
 * 要么是访客（没有账号，只允许进出自己被分配的那一个会话）。
 */
public record RealtimePrincipal(
        Identity identity,
        long id,
        String name,
        String tenantCode,
        String sessionNo
) {

    public enum Identity {
        /** 坐席（企业成员） */
        AGENT,
        /** 访客（客户） */
        VISITOR
    }

    public boolean isVisitor() {
        return identity == Identity.VISITOR;
    }
}
```

### 文件：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/security/RealtimeTokenParser.java

``` code-block-container
package cn.net.susan.realtime.security;

import cn.net.susan.common.exception.BizException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * 长连接令牌解析：坐席令牌（登录颁发）与访客令牌（渠道密钥换取）共用一把密钥。
 */
@Component
public class RealtimeTokenParser {

    /** 访客令牌标记（载荷 typ） */
    private static final int TOKEN_TYPE_VISITOR = 4;

    /** 用户类型：企业账号 */
    private static final int USER_TYPE_ENTERPRISE = 2;

    private final SecretKey secretKey;

    public RealtimeTokenParser(@Value("${yunti.auth.jwt-secret}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解析令牌；非法、过期、平台账号一律拒绝。
     */
    public RealtimePrincipal parse(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException(40100, "缺少访问令牌");
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            throw new BizException(40100, "访问令牌无效或已过期");
        }

        Integer typ = claims.get("typ", Integer.class);
        String tenant = claims.get("tnt", String.class);
        String name = claims.get("name", String.class);

        if (typ != null && typ == TOKEN_TYPE_VISITOR) {
            String sessionNo = claims.get("sno", String.class);
            if (tenant == null || tenant.isBlank() || sessionNo == null || sessionNo.isBlank()) {
                throw new BizException(40100, "访客令牌缺少租户或会话信息");
            }
            return new RealtimePrincipal(
                    RealtimePrincipal.Identity.VISITOR,
                    Long.parseLong(claims.getSubject()),
                    name == null ? "访客" : name,
                    tenant,
                    sessionNo
            );
        }

        Integer userType = claims.get("type", Integer.class);
        if (userType == null || userType != USER_TYPE_ENTERPRISE) {
            throw new BizException(40301, "平台账号不参与会话接待");
        }
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用在线客服");
        }
        return new RealtimePrincipal(
                RealtimePrincipal.Identity.AGENT,
                Long.parseLong(claims.getSubject()),
                name == null ? "客服" : name,
                tenant,
                null
        );
    }
}
```

### 文件：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/ws/AuthHandshakeInterceptor.java

``` code-block-container
package cn.net.susan.realtime.ws;

import cn.net.susan.realtime.security.RealtimePrincipal;
import cn.net.susan.realtime.security.RealtimeTokenParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 握手鉴权：浏览器建连时带不了自定义请求头，令牌通过 {@code ?token=xxx} 传。
 * 校验不通过直接 401 拒绝握手，连不上就不会占用服务端连接资源。
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String PRINCIPAL_KEY = "realtimePrincipal";

    private static final Logger log = LoggerFactory.getLogger(AuthHandshakeInterceptor.class);

    private final RealtimeTokenParser tokenParser;

    public AuthHandshakeInterceptor(RealtimeTokenParser tokenParser) {
        this.tokenParser = tokenParser;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes
    ) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build()
                .getQueryParams().getFirst("token");
        try {
            RealtimePrincipal principal = tokenParser.parse(token);
            attributes.put(PRINCIPAL_KEY, principal);
            return true;
        } catch (Exception e) {
            log.warn("长连接鉴权失败 uri={} reason={}", request.getURI(), e.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception
    ) {
        // 无需处理
    }
}
```

### 5.3 连接注册表

谁在线、连在哪个会话上，全靠这张表。单机内存版够跑通；多实例部署把这份映射换成 Redis 路由表即可水平扩容，代码里已经留好位置。

### 文件：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/ws/ConnectionRegistry.java

``` code-block-container
package cn.net.susan.realtime.ws;

import cn.net.susan.realtime.security.RealtimePrincipal;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接注册表：谁在线、连在哪个会话上。
 *
 * <p>单机内存实现，够跑通单实例；多实例部署时把这份映射换到 Redis
 * （按 sessionNo 记路由，推送时定位到具体实例）即可水平扩容。</p>
 */
@Component
public class ConnectionRegistry {

    /** 一条长连接 */
    public static final class Client {
        private final WebSocketSession socket;
        private final RealtimePrincipal principal;
        private volatile long lastActiveAt = System.currentTimeMillis();
        private volatile long windowStart = System.currentTimeMillis();
        private volatile int windowCount = 0;

        Client(WebSocketSession socket, RealtimePrincipal principal) {
            this.socket = socket;
            this.principal = principal;
        }

        public WebSocketSession socket() {
            return socket;
        }

        public RealtimePrincipal principal() {
            return principal;
        }

        public long lastActiveAt() {
            return lastActiveAt;
        }

        public void touch() {
            this.lastActiveAt = System.currentTimeMillis();
        }

        /**
         * 单连接限流：每秒最多 maxPerSecond 条上行消息。
         */
        public boolean allow(int maxPerSecond) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= 1000) {
                windowStart = now;
                windowCount = 0;
            }
            windowCount++;
            return windowCount <= maxPerSecond;
        }
    }

    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sessionSubscribers = new ConcurrentHashMap<>();

    public void bind(WebSocketSession socket, RealtimePrincipal principal) {
        clients.put(socket.getId(), new Client(socket, principal));
    }

    public Client get(String socketId) {
        return clients.get(socketId);
    }

    public Client unbind(String socketId) {
        Client client = clients.remove(socketId);
        for (Set<String> ids : sessionSubscribers.values()) {
            ids.remove(socketId);
        }
        return client;
    }

    /** 订阅某个会话（坐席可同时订阅多个，访客只订阅自己那一个）。 */
    public void subscribe(String socketId, String sessionNo) {
        sessionSubscribers.computeIfAbsent(sessionNo, key -> ConcurrentHashMap.newKeySet()).add(socketId);
    }

    public Set<String> subscribersOf(String sessionNo) {
        return sessionSubscribers.getOrDefault(sessionNo, Set.of());
    }

    /** 该会话当前在线的连接数（含访客与坐席）。 */
    public int onlineCount(String sessionNo) {
        return subscribersOf(sessionNo).size();
    }

    /**
     * 找出空闲超时的连接，交由定时任务断开。
     */
    public Map<String, Client> idleClients(int timeoutSeconds) {
        long deadline = Instant.now().toEpochMilli() - timeoutSeconds * 1000L;
        Map<String, Client> result = new ConcurrentHashMap<>();
        clients.forEach((id, client) -> {
            if (client.lastActiveAt() < deadline) {
                result.put(id, client);
            }
        });
        return result;
    }

    public int totalConnections() {
        return clients.size();
    }
}
```

### 5.4 消息协议与主处理

### 文件：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/ws/RealtimeMessage.java

``` code-block-container
package cn.net.susan.realtime.ws;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 长连接消息信封（上下行共用）。
 *
 * <p>上行：PING / JOIN / SEND / HISTORY / CLOSE / CLOSE_SESSION<br/>
 * 下行：CONNECTED / PONG / JOINED / ACK / MESSAGE / HISTORY / SESSION / PRESENCE / ERROR</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RealtimeMessage(
        String type,
        String sessionNo,
        String clientMsgNo,
        Integer msgType,
        String content,
        Long beforeId,
        Object data,
        Long serverTime,
        Integer code,
        String message
) {

    public static RealtimeMessage of(String type) {
        return new RealtimeMessage(type, null, null, null, null, null, null, System.currentTimeMillis(), null, null);
    }

    public static RealtimeMessage error(int code, String message) {
        return new RealtimeMessage("ERROR", null, null, null, null, null, null,
                System.currentTimeMillis(), code, message);
    }

    public static RealtimeMessage withData(String type, String sessionNo, Object data) {
        return new RealtimeMessage(type, sessionNo, null, null, null, null, data,
                System.currentTimeMillis(), null, null);
    }
}
```

### 文件：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/ws/RealtimeWebSocketHandler.java

``` code-block-container
package cn.net.susan.realtime.ws;

import cn.net.susan.common.exception.BizException;
import cn.net.susan.realtime.client.SessionApiClient;
import cn.net.susan.realtime.config.RealtimeProperties;
import cn.net.susan.realtime.security.RealtimePrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长连接主处理：建连 → 订阅会话 → 收发消息 → 断开清理。
 *
 * <p>消息可靠性约定：客户端每条消息带 {@code clientMsgNo}，服务端落库后回 {@code ACK}
 * （带服务端消息号），前端据 ACK 把"发送中"改成"已发送"；没收到 ACK 的重连后重发，
 * 服务端按 {@code msg_no} 唯一约束去重。</p>
 */
@Component
public class RealtimeWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    /** 一次最多回多少条历史消息 */
    private static final int JOIN_HISTORY_LIMIT = 50;
    private static final int PAGE_HISTORY_LIMIT = 30;
    private static final int MAX_CONTENT_LENGTH = 4000;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConnectionRegistry registry;
    private final SessionApiClient sessionApi;
    private final RealtimeProperties properties;

    public RealtimeWebSocketHandler(
            ConnectionRegistry registry,
            SessionApiClient sessionApi,
            RealtimeProperties properties
    ) {
        this.registry = registry;
        this.sessionApi = sessionApi;
        this.properties = properties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) {
        Object attribute = rawSession.getAttributes().get(AuthHandshakeInterceptor.PRINCIPAL_KEY);
        if (!(attribute instanceof RealtimePrincipal principal)) {
            closeQuietly(rawSession, CloseStatus.NOT_ACCEPTABLE);
            return;
        }
        // 包装成并发安全的 session：广播和心跳可能同时往一条连接写
        WebSocketSession socket = new ConcurrentWebSocketSessionDecorator(
                rawSession, properties.getSendTimeLimitMs(), properties.getSendBufferSizeLimit());
        registry.bind(socket, principal);
        log.info("长连接建立 userId={} identity={} tenant={} 当前连接数={}",
                principal.id(), principal.identity(), principal.tenantCode(), registry.totalConnections());

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", principal.id());
        info.put("name", principal.name());
        info.put("identity", principal.identity().name());
        info.put("tenantCode", principal.tenantCode());
        info.put("sessionNo", principal.sessionNo());
        info.put("online", registry.totalConnections());
        send(socket, RealtimeMessage.withData("CONNECTED", principal.sessionNo(), info));

        // 访客连上就自动进入自己的会话，前端不用再发 JOIN
        if (principal.isVisitor()) {
            handleJoin(socket, principal.sessionNo(), false);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession rawSession, TextMessage textMessage) {
        ConnectionRegistry.Client client = registry.get(rawSession.getId());
        if (client == null) {
            return;
        }
        client.touch();
        if (!client.allow(properties.getMaxMessagesPerSecond())) {
            send(client.socket(), RealtimeMessage.error(42900, "发送过于频繁，请稍后再试"));
            return;
        }

        RealtimeMessage inbound;
        try {
            inbound = objectMapper.readValue(textMessage.getPayload(), RealtimeMessage.class);
        } catch (Exception e) {
            send(client.socket(), RealtimeMessage.error(40000, "消息格式不正确"));
            return;
        }

        String type = inbound.type() == null ? "" : inbound.type().trim().toUpperCase();
        try {
            switch (type) {
                case "PING" -> send(client.socket(), new RealtimeMessage(
                        "PONG", null, null, null, null, null, null, System.currentTimeMillis(), null, null));
                case "JOIN" -> handleJoin(client.socket(), inbound.sessionNo(), true);
                case "SEND" -> handleSend(client, inbound);
                case "HISTORY" -> handleHistory(client, inbound);
                case "CLOSE_SESSION" -> handleClose(client, inbound);
                default -> send(client.socket(), RealtimeMessage.error(40000, "不支持的消息类型：" + type));
            }
        } catch (BizException e) {
            send(client.socket(), RealtimeMessage.error(e.getCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("处理长连接消息失败 type={} socketId={}", type, rawSession.getId(), e);
            send(client.socket(), RealtimeMessage.error(50000, "服务处理失败，请稍后重试"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession rawSession, CloseStatus status) {
        ConnectionRegistry.Client client = registry.unbind(rawSession.getId());
        if (client == null) {
            return;
        }
        log.info("长连接断开 userId={} identity={} code={} 当前连接数={}",
                client.principal().id(), client.principal().identity(), status.getCode(), registry.totalConnections());
        if (client.principal().isVisitor() && client.principal().sessionNo() != null) {
            broadcastPresence(client.principal().sessionNo());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession rawSession, Throwable exception) {
        log.warn("长连接传输异常 socketId={} error={}", rawSession.getId(), exception.getMessage());
        closeQuietly(rawSession, CloseStatus.SERVER_ERROR);
    }

    /**
     * 进入会话：坐席认领 + 回历史消息 + 广播在线状态。
     */
    private void handleJoin(WebSocketSession socket, String requestedSessionNo, boolean explicit) {
        ConnectionRegistry.Client client = registry.get(socket.getId());
        if (client == null) {
            return;
        }
        RealtimePrincipal principal = client.principal();
        String sessionNo = resolveSessionNo(principal, requestedSessionNo);

        SessionApiClient.SessionInfo session = sessionApi.requireSession(principal.tenantCode(), sessionNo);
        if (session.closed()) {
            send(socket, RealtimeMessage.error(40301, "会话已结束"));
            return;
        }
        registry.subscribe(socket.getId(), sessionNo);

        // 坐席主动 JOIN 视为接入（认领）；已被别人接待时只提示，仍可旁观
        if (explicit && principal.identity() == RealtimePrincipal.Identity.AGENT) {
            try {
                session = sessionApi.assignAgent(principal.tenantCode(), sessionNo, principal.id());
                broadcast(sessionNo, RealtimeMessage.withData("SESSION", sessionNo, session), null);
            } catch (BizException e) {
                send(socket, RealtimeMessage.error(e.getCode(), e.getMessage()));
            }
        }

        List<SessionApiClient.MessageView> messages =
                sessionApi.history(principal.tenantCode(), sessionNo, null, JOIN_HISTORY_LIMIT);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session", session);
        payload.put("messages", messages);
        payload.put("online", registry.onlineCount(sessionNo));
        send(socket, RealtimeMessage.withData("JOINED", sessionNo, payload));
        broadcastPresence(sessionNo);
    }

    /**
     * 发消息：落库 → 回 ACK → 广播给会话内其他连接。
     */
    private void handleSend(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        RealtimePrincipal principal = client.principal();
        String sessionNo = resolveSessionNo(principal, inbound.sessionNo());
        String content = inbound.content() == null ? "" : inbound.content().trim();
        if (content.isEmpty()) {
            throw new BizException(40001, "消息内容不能为空");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new BizException(40001, "单条消息最长 " + MAX_CONTENT_LENGTH + " 字");
        }

        int senderType = principal.isVisitor() ? 1 : 2;
        SessionApiClient.MessageView saved = sessionApi.appendMessage(
                principal.tenantCode(),
                sessionNo,
                senderType,
                principal.id(),
                inbound.msgType() == null ? 1 : inbound.msgType(),
                content
        );

        send(client.socket(), new RealtimeMessage("ACK", sessionNo, inbound.clientMsgNo(), null, null, null,
                saved, System.currentTimeMillis(), null, null));
        broadcast(sessionNo,
                new RealtimeMessage("MESSAGE", sessionNo, null, null, null, null,
                        saved, System.currentTimeMillis(), null, null),
                client.socket().getId());
    }

    /**
     * 向上翻聊天记录。
     */
    private void handleHistory(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        RealtimePrincipal principal = client.principal();
        String sessionNo = resolveSessionNo(principal, inbound.sessionNo());
        List<SessionApiClient.MessageView> messages = sessionApi.history(
                principal.tenantCode(), sessionNo, inbound.beforeId(), PAGE_HISTORY_LIMIT);
        send(client.socket(), RealtimeMessage.withData("HISTORY", sessionNo, messages));
    }

    /**
     * 坐席结束会话。
     */
    private void handleClose(ConnectionRegistry.Client client, RealtimeMessage inbound) {
        RealtimePrincipal principal = client.principal();
        if (principal.isVisitor()) {
            throw new BizException(40301, "访客不能结束会话");
        }
        String sessionNo = resolveSessionNo(principal, inbound.sessionNo());
        SessionApiClient.SessionInfo session = sessionApi.close(principal.tenantCode(), sessionNo);
        broadcast(sessionNo, RealtimeMessage.withData("SESSION", sessionNo, session), null);
    }

    private String resolveSessionNo(RealtimePrincipal principal, String requested) {
        if (principal.isVisitor()) {
            if (requested != null && !requested.isBlank() && !requested.equals(principal.sessionNo())) {
                throw new BizException(40301, "无权访问该会话");
            }
            return principal.sessionNo();
        }
        if (requested == null || requested.isBlank()) {
            throw new BizException(40001, "缺少会话号");
        }
        return requested;
    }

    private void broadcastPresence(String sessionNo) {
        broadcast(sessionNo, RealtimeMessage.withData("PRESENCE", sessionNo,
                Map.of("online", registry.onlineCount(sessionNo))), null);
    }

    private void broadcast(String sessionNo, RealtimeMessage message, String excludeSocketId) {
        for (String socketId : registry.subscribersOf(sessionNo)) {
            if (socketId.equals(excludeSocketId)) {
                continue;
            }
            ConnectionRegistry.Client client = registry.get(socketId);
            if (client != null) {
                send(client.socket(), message);
            }
        }
    }

    private void send(WebSocketSession socket, RealtimeMessage message) {
        if (socket == null || !socket.isOpen()) {
            return;
        }
        try {
            socket.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (Exception e) {
            log.warn("下行消息发送失败 socketId={} error={}", socket.getId(), e.getMessage());
        }
    }

    private void closeQuietly(WebSocketSession socket, CloseStatus status) {
        try {
            socket.close(status);
        } catch (Exception ignored) {
            // 关闭失败无需处理
        }
    }
}
```

### 5.5 注册入口与空闲回收

### 文件：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/ws/WebSocketConfig.java

``` code-block-container
package cn.net.susan.realtime.ws;

import cn.net.susan.realtime.config.RealtimeProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册长连接入口：ws://host:9096/ws/realtime?token=xxx
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler handler;
    private final AuthHandshakeInterceptor authInterceptor;
    private final RealtimeProperties properties;

    public WebSocketConfig(
            RealtimeWebSocketHandler handler,
            AuthHandshakeInterceptor authInterceptor,
            RealtimeProperties properties
    ) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, properties.getPath())
                .addInterceptors(authInterceptor)
                // 访客来自企业自己的网站/小程序，域名不可枚举，这里放开跨域；生产建议按渠道域名白名单收紧
                .setAllowedOriginPatterns("*");
    }
}
```

### 文件：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/ws/IdleConnectionMonitor.java

``` code-block-container
package cn.net.susan.realtime.ws;

import cn.net.susan.realtime.config.RealtimeProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

import java.util.Map;

/**
 * 心跳超时巡检：客户端 30 秒一次 PING，超过阈值没消息就判掉线并断开。
 */
@Component
public class IdleConnectionMonitor {

    private static final Logger log = LoggerFactory.getLogger(IdleConnectionMonitor.class);

    private final ConnectionRegistry registry;
    private final RealtimeProperties properties;

    public IdleConnectionMonitor(ConnectionRegistry registry, RealtimeProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 30000L, initialDelay = 30000L)
    public void sweep() {
        Map<String, ConnectionRegistry.Client> idle = registry.idleClients(properties.getIdleTimeoutSeconds());
        if (idle.isEmpty()) {
            return;
        }
        idle.forEach((socketId, client) -> {
            try {
                client.socket().close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (Exception e) {
                log.debug("关闭空闲连接失败 socketId={} error={}", socketId, e.getMessage());
            }
            registry.unbind(socketId);
            log.info("心跳超时断开连接 userId={} identity={} 当前连接数={}",
                    client.principal().id(), client.principal().identity(), registry.totalConnections());
        });
    }
}
```

### 5.6 调用 customer-service

### 文件：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/client/SessionApiClient.java

``` code-block-container
package cn.net.susan.realtime.client;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * customer-service 内部接口客户端：会话查询、消息落库、坐席接入、结束会话。
 *
 * <p>实时网关只做"连接与转发"，业务数据一律交给 customer-service 落库，
 * 保证一个库只有一个写入口。</p>
 */
@Component
public class SessionApiClient {

    private static final Logger log = LoggerFactory.getLogger(SessionApiClient.class);

    private final RestClient restClient;

    public SessionApiClient(@Value("${yunti.customer.internal-base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /** 会话信息 */
    public record SessionInfo(
            String sessionNo,
            String tenantCode,
            Long sessionId,
            Integer status,
            Long agentId,
            Long customerId,
            String startTime,
            String endTime
    ) {
        public boolean closed() {
            return status != null && status == 4;
        }
    }

    /** 消息视图 */
    public record MessageView(
            String msgId,
            String msgNo,
            Long sessionId,
            Integer senderType,
            Long senderId,
            Integer msgType,
            String content,
            String sendTime
    ) {
    }

    public SessionInfo requireSession(String tenantCode, String sessionNo) {
        ApiResponse<SessionInfo> response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/customer/internal/sessions/{sessionNo}")
                        .queryParam("tenantCode", tenantCode)
                        .build(sessionNo))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<SessionInfo>>() {
                });
        return unwrap(response);
    }

    public List<MessageView> history(String tenantCode, String sessionNo, Long beforeId, int limit) {
        ApiResponse<List<MessageView>> response = restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/api/customer/internal/sessions/{sessionNo}/messages")
                            .queryParam("tenantCode", tenantCode)
                            .queryParam("limit", limit);
                    if (beforeId != null) {
                        uriBuilder.queryParam("beforeId", beforeId);
                    }
                    return uriBuilder.build(sessionNo);
                })
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<MessageView>>>() {
                });
        return unwrap(response);
    }

    public MessageView appendMessage(
            String tenantCode,
            String sessionNo,
            int senderType,
            Long senderId,
            int msgType,
            String content
    ) {
        ApiResponse<MessageView> response = restClient.post()
                .uri("/api/customer/internal/sessions/{sessionNo}/messages", sessionNo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "tenantCode", tenantCode,
                        "senderType", senderType,
                        "senderId", senderId == null ? 0L : senderId,
                        "msgType", msgType,
                        "content", content
                ))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<MessageView>>() {
                });
        return unwrap(response);
    }

    public SessionInfo assignAgent(String tenantCode, String sessionNo, long agentId) {
        ApiResponse<SessionInfo> response = restClient.post()
                .uri("/api/customer/internal/sessions/{sessionNo}/assign", sessionNo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantCode", tenantCode, "agentId", agentId))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<SessionInfo>>() {
                });
        return unwrap(response);
    }

    public SessionInfo close(String tenantCode, String sessionNo) {
        ApiResponse<SessionInfo> response = restClient.post()
                .uri("/api/customer/internal/sessions/{sessionNo}/close", sessionNo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantCode", tenantCode))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<SessionInfo>>() {
                });
        return unwrap(response);
    }

    private static <T> T unwrap(ApiResponse<T> response) {
        if (response == null || response.code() != 0) {
            String message = response == null ? "customer-service 无响应" : response.message();
            int code = response == null ? 50000 : response.code();
            log.warn("customer-service 内部调用失败 code={} message={}", code, message);
            throw new BizException(code, message == null ? "会话服务暂不可用" : message);
        }
        return response.data();
    }
}
```

##
