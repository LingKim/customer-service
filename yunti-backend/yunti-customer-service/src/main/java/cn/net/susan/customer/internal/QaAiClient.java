package cn.net.susan.customer.internal;

import cn.net.susan.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 将租户 JWT 与对话文本转发给 AI 质检服务。 */
@Component
public class QaAiClient {

    private static final Logger log = LoggerFactory.getLogger(QaAiClient.class);
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;

    public QaAiClient(@Value("${yunti.ai.qa-base-url:http://127.0.0.1:9100}") String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public Evaluation evaluate(Request request, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BizException(40101, "缺少登录令牌");
        }
        String trace = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant_code", request.tenantCode());
        body.put("session_name", request.sessionName());
        body.put("agent_name", request.agentName());
        body.put("transcript", request.transcript());
        body.put("rule_names", request.rules().stream().map(Rule::name).toList());
        body.put("rules", request.rules());
        long started = System.currentTimeMillis();
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/qa/evaluate"))
                    .timeout(Duration.ofSeconds(35))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authorization)
                    .header("X-Tenant-Code", request.tenantCode())
                    .header("X-Request-Id", trace)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("AI HTTP " + response.statusCode());
            }
            Map<String, Object> envelope = json.readValue(response.body(), new TypeReference<>() { });
            if (!(envelope.get("code") instanceof Number code) || code.intValue() != 0
                    || !(envelope.get("data") instanceof Map<?, ?> data)) {
                throw new IllegalStateException("AI 响应不完整");
            }
            if (!(data.get("aiScore") instanceof Number) || !(data.get("riskLevel") instanceof Number)) {
                throw new IllegalStateException("AI 评分缺失");
            }
            Evaluation result = new Evaluation(
                    number(data.get("aiScore"), 0), number(data.get("riskLevel"), 3),
                    strings(data.get("rules")), String.valueOf(data.get("comment")),
                    String.valueOf(data.get("source")));
            log.info("AI 质检完成 trace={} tenant={} source={} costMs={} score={}",
                    trace, request.tenantCode(), result.source(), System.currentTimeMillis() - started, result.aiScore());
            return result;
        } catch (Exception e) {
            log.warn("AI 质检降级 trace={} tenant={} costMs={} error={}",
                    trace, request.tenantCode(), System.currentTimeMillis() - started, e.getClass().getSimpleName());
            return fallback(request);
        }
    }

    private Evaluation fallback(Request request) {
        List<String> hits = request.rules().stream()
                .map(Rule::name)
                .filter(name -> request.transcript().contains(name))
                .toList();
        int score = Math.max(60, 88 - hits.size() * 8);
        return new Evaluation(score, hits.isEmpty() ? 1 : hits.size() == 1 ? 2 : 3,
                hits, "AI 服务不可用，已使用本地规则兜底，请人工复核。", "fallback-rule");
    }

    private static int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    public record Rule(String name, String content) { }
    public record Request(String tenantCode, String sessionName, String agentName,
                          String transcript, List<Rule> rules) { }
    public record Evaluation(int aiScore, int riskLevel, List<String> rules,
                             String comment, String source) { }
}
