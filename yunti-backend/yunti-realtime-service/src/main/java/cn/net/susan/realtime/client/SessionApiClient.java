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

    public SessionApiClient(
            @Value("${yunti.customer.internal-base-url}") String baseUrl,
            @Value("${yunti.internal.shared-secret:}") String sharedSecret
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Yunti-Internal-Secret", sharedSecret)
                .build();
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
            String endTime,
            /** 会话当前最大消息序号：用来告诉客户端要不要补拉 */
            Long lastSeq
    ) {
        public boolean closed() {
            return status != null && status == 4;
        }
    }

    /** 消息视图 */
    public record MessageView(
            String msgId,
            String msgNo,
            /** 客户端消息号：回 ACK 时带上，客户端据此把"发送中"改成"已发送" */
            String clientMsgNo,
            /** 会话内序号：双方按它排序，也是增量补拉的游标 */
            Long seq,
            Long sessionId,
            Integer senderType,
            Long senderId,
            Integer msgType,
            String content,
            Integer visibleTo,
            String sendTime
    ) {
    }

    /** 坐席接待量 */
    public record AgentLoadView(Long agentId, Integer sessionCount) {
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

    public List<MessageView> history(String tenantCode, String sessionNo, Long beforeId, int limit, boolean agentView) {
        ApiResponse<List<MessageView>> response = restClient.get()
                .uri(uriBuilder -> {
                    uriBuilder.path("/api/customer/internal/sessions/{sessionNo}/messages")
                            .queryParam("tenantCode", tenantCode)
                            .queryParam("limit", limit)
                            .queryParam("agentView", agentView);
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
            String content,
            int visibleTo,
            String clientMsgNo
    ) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("tenantCode", tenantCode);
        body.put("senderType", senderType);
        body.put("senderId", senderId == null ? 0L : senderId);
        body.put("msgType", msgType);
        body.put("content", content);
        body.put("visibleTo", visibleTo);
        if (clientMsgNo != null && !clientMsgNo.isBlank()) {
            body.put("clientMsgNo", clientMsgNo);
        }
        ApiResponse<MessageView> response = restClient.post()
                .uri("/api/customer/internal/sessions/{sessionNo}/messages", sessionNo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<MessageView>>() {
                });
        return unwrap(response);
    }

    public SessionInfo assignAgent(String tenantCode, String sessionNo, long agentId) {
        return claim(tenantCode, sessionNo, agentId);
    }

    public SessionInfo claim(String tenantCode, String sessionNo, long agentId) {
        ApiResponse<SessionInfo> response = restClient.post()
                .uri("/api/customer/internal/sessions/{sessionNo}/claim", sessionNo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantCode", tenantCode, "agentId", agentId))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<SessionInfo>>() {
                });
        return unwrap(response);
    }

    public SessionInfo release(String tenantCode, String sessionNo, long agentId) {
        ApiResponse<SessionInfo> response = restClient.post()
                .uri("/api/customer/internal/sessions/{sessionNo}/release", sessionNo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("tenantCode", tenantCode, "agentId", agentId))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<SessionInfo>>() {
                });
        return unwrap(response);
    }

    public SessionInfo transfer(
            String tenantCode,
            String sessionNo,
            Long fromAgentId,
            long toAgentId,
            String remark
    ) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("tenantCode", tenantCode);
        body.put("fromAgentId", fromAgentId == null ? 0L : fromAgentId);
        body.put("toAgentId", toAgentId);
        if (remark != null && !remark.isBlank()) {
            body.put("remark", remark);
        }
        ApiResponse<SessionInfo> response = restClient.post()
                .uri("/api/customer/internal/sessions/{sessionNo}/transfer", sessionNo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<SessionInfo>>() {
                });
        return unwrap(response);
    }

    public SessionInfo close(String tenantCode, String sessionNo, Long operatorId, String remark) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("tenantCode", tenantCode);
        if (operatorId != null) {
            body.put("operatorId", operatorId);
        }
        if (remark != null && !remark.isBlank()) {
            body.put("remark", remark);
        }
        ApiResponse<SessionInfo> response = restClient.post()
                .uri("/api/customer/internal/sessions/{sessionNo}/close", sessionNo)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<SessionInfo>>() {
                });
        return unwrap(response);
    }

    public List<AgentLoadView> workload(String tenantCode) {
        ApiResponse<List<AgentLoadView>> response = restClient.get()
                .uri(uriBuilder -> uriBuilder.path("/api/customer/internal/sessions/workload")
                        .queryParam("tenantCode", tenantCode)
                        .build())
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<List<AgentLoadView>>>() {
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
