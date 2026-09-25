package cn.net.susan.customer.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 反向通知实时网关：智能路由自动接入后，告诉对应坐席"这条会话分给你了"。
 *
 * <p>路由分配发生在 customer-service，长连接那边完全不知道；不通知的话，
 * 坐席工作台要等到下一次列表对账才看得到，体验上像"没有接入效果"。</p>
 */
@Component
public class RealtimeNotifyClient {

    private static final Logger log = LoggerFactory.getLogger(RealtimeNotifyClient.class);

    private final RestClient restClient;

    public RealtimeNotifyClient(@Value("${yunti.realtime.base-url:http://127.0.0.1:9096}") String baseUrl,
                                @Value("${yunti.internal.shared-secret:}") String sharedSecret) {
        this.restClient = RestClient.builder().baseUrl(baseUrl)
                .defaultHeader("X-Yunti-Internal-Secret", sharedSecret).build();
    }

    /**
     * 通知坐席已被自动分配会话；失败只记日志，不影响主流程。
     */
    public void notifyAssigned(String tenantCode, long agentId, String sessionNo, String reason) {
        try {
            restClient.post()
                    .uri("/api/realtime/internal/assigned")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "tenantCode", tenantCode,
                            "agentId", agentId,
                            "sessionNo", sessionNo,
                            "reason", reason == null ? "routing" : reason))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("通知坐席自动接入失败 tenant={} agentId={} sessionNo={} error={}",
                    tenantCode, agentId, sessionNo, e.getMessage());
        }
    }
}
