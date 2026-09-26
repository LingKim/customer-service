package cn.net.susan.customer.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
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
     * 通知实时网关：这条会话刚刚命中实时质检规则，把预警推给它的坐席。
     *
     * <p>和自动接入通知一样，失败只记日志——预警发不出去不能反过来影响客户消息。</p>
     */
    public void notifyQaAlert(String tenantCode, String sessionNo, Long agentId, Map<String, Object> payload) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantCode", tenantCode);
            body.put("sessionNo", sessionNo);
            body.put("agentId", agentId);
            body.put("payload", payload);
            restClient.post()
                    .uri("/api/realtime/internal/qa-alert")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("推送实时质检告警失败 tenant={} sessionNo={} error={}",
                    tenantCode, sessionNo, e.getMessage());
        }
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

    /**
     * 广播一条消息（机器人回复 / 系统提示）。
     *
     * <p>机器人回复是在 customer-service 里生成并落库的，长连接手里没有这条消息；
     * 不反向推一次，访客要刷新页面才看得到机器人的回话。同时提醒坐席刷新列表，
     * 让"最新一条消息 + 意图/情绪标签"跟着变。</p>
     *
     * @param refreshAgents 是否顺带提醒租户内的坐席刷新会话列表
     */
    public void notifyMessage(String tenantCode, String sessionNo, Map<String, Object> message,
                              boolean refreshAgents) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantCode", tenantCode);
            body.put("sessionNo", sessionNo);
            body.put("message", message);
            body.put("refreshAgents", refreshAgents);
            restClient.post()
                    .uri("/api/realtime/internal/message")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // 推送失败不能让消息白落库：历史里已经有这条，刷新就能看到。
            // 但必须在日志里说清楚"客户现在看不到"，并把最常见的原因（网关是旧版本）点出来，
            // 否则现象是"机器人不回话"，而真相是"消息推不出去"。
            String error = e.getMessage() == null ? "" : e.getMessage();
            if (error.contains("404")) {
                log.error("广播机器人消息失败：实时网关没有 /api/realtime/internal/message 接口"
                                + "（yunti-realtime-service 还是旧版本，请重启它）。"
                                + "消息已落库，客户刷新页面才能看到 tenant={} sessionNo={}",
                        tenantCode, sessionNo);
                return;
            }
            log.error("广播机器人消息失败（消息已落库，客户刷新页面才能看到）"
                            + " tenant={} sessionNo={} error={}",
                    tenantCode, sessionNo, error);
        }
    }

    /**
     * 会话被自动分配后，让实时网关把"已接入"同步给会话里的所有人（**包括客户**）。
     *
     * <p>只 notifyAssigned 给坐席是不够的：客户那边会一直停在"正在为您转接人工客服，请稍候"，
     * 头上的状态也还写着"等待客服接入"。失败只记日志——同步不到顶多让客户多等一次刷新。</p>
     */
    public void notifyClaimed(String tenantCode, String sessionNo, Long agentId) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantCode", tenantCode);
            body.put("sessionNo", sessionNo);
            body.put("agentId", agentId);
            restClient.post()
                    .uri("/api/realtime/internal/claimed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("同步会话已接入状态失败 tenant={} sessionNo={} error={}",
                    tenantCode, sessionNo, e.getMessage());
        }
    }

    /**
     * 通知"机器人/坐席正在输入"，让对面亮起"正在输入…"。
     *
     * <p>客户发完消息到机器人答上来有几秒（检索 + 模型），这期间界面完全没反应，
     * 客户会以为消息没发出去。失败只记日志——提示没了不影响消息本身。</p>
     *
     * @param who BOT-机器人、AGENT-坐席
     */
    public void notifyTyping(String tenantCode, String sessionNo, String who, boolean typing) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantCode", tenantCode);
            body.put("sessionNo", sessionNo);
            body.put("who", who);
            body.put("typing", typing);
            restClient.post()
                    .uri("/api/realtime/internal/typing")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.debug("推送输入状态失败 tenant={} sessionNo={} error={}",
                    tenantCode, sessionNo, e.getMessage());
        }
    }

    /**
     * 探测实时网关的内部接口是否可用（体检用）。
     *
     * <p>只报"通不通"，不抛异常——体检接口自己挂了就没意义了。</p>
     */
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            String body = restClient.get()
                    .uri("/api/realtime/internal/health")
                    .retrieve()
                    .body(String.class);
            result.put("reachable", body != null);
            result.put("detail", body);
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            result.put("reachable", false);
            result.put("hint", message.contains("404")
                    ? "实时网关没有 /api/realtime/internal/health 接口：yunti-realtime-service 还是旧版本，请重启它"
                    : "连不上实时网关（" + message + "），请确认 yunti-realtime-service 已启动（默认 9096）");
        }
        return result;
    }
}
