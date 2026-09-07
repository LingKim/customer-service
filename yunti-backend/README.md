# 云梯智能客服平台 · yunti-backend 后端骨架

基于 [01 SaaS智能客服系统架构设计](../docs/design/01%20SaaS智能客服系统架构设计.md) 的 6+1 微服务划分搭建的 Maven 多模块工程骨架。

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

```bash
cd yunti-backend
mvn -q clean package -DskipTests
java -jar yunti-user-service/target/yunti-user-service-1.0.0-SNAPSHOT.jar
```

验证：`curl http://localhost:9091/api/user/ping`、`curl http://localhost:9091/actuator/health`

当前骨架**不连接数据库**，仅验证微服务可独立启动与调用；数据源、消息队列、注册中心等在后续迭代接入。
