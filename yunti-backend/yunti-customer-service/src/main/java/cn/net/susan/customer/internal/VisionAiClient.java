package cn.net.susan.customer.internal;

import cn.net.susan.common.exception.BizException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * yunti-ai「图片识别」客户端：把图片交给视觉大模型（千问 VL）做 OCR 与截图判读。
 *
 * <p>为什么图片要 Java 侧先压好再送过去：图片存在 RustFS，只有 customer-service 手里有对象存储客户端；
 * 而且压缩这步放在 Java 能保证 payload 可控（base64 会让体积涨约 1/3）。
 * 这和"企业智能招聘系统"里的做法一致——那边也是 Java 把 PDF/图片渲染成 JPEG 的 data URL，再交给模型识别。</p>
 */
@Component
public class VisionAiClient {

    private static final Logger log = LoggerFactory.getLogger(VisionAiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final boolean logPayload;
    private final int timeoutSeconds;
    private final String internalSharedSecret;

    public VisionAiClient(
            @Value("${yunti.ai.vision-base-url:http://127.0.0.1:9100}") String baseUrl,
            @Value("${yunti.ai.log-payload:false}") boolean logPayload,
            @Value("${yunti.ai.vision-timeout-seconds:90}") int timeoutSeconds,
            @Value("${yunti.internal.shared-secret:}") String internalSharedSecret
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "http://127.0.0.1:9100" : baseUrl.replaceAll("/+$", "");
        this.logPayload = logPayload;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 90 : timeoutSeconds;
        this.internalSharedSecret = internalSharedSecret;
    }

    /**
     * 识别一组图片。
     *
     * @param images data URL 列表（`data:image/jpeg;base64,...`）
     * @return 识别结果（available / summary / ocr_text / order_no / amount / error_text / need_human …）
     */
    public Map<String, Object> recognize(String tenantCode, String sessionNo,
                                         List<String> images, String question) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenant_code", tenantCode);
        body.put("session_id", sessionNo == null ? "" : sessionNo);
        body.put("images", images);
        body.put("question", question == null ? "" : question);
        if (internalSharedSecret == null || internalSharedSecret.isBlank()) {
            throw new BizException(50001, "未配置 AI 内部共享密钥");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/agent/vision"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json;charset=UTF-8")
                    .header("X-Tenant-Code", tenantCode)
                    .header("X-Yunti-Internal-Secret", internalSharedSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (logPayload) {
                // 图片 base64 不能整段打进日志；这里按字段打，识别结论一眼可见
                Map<String, Object> parsed = objectMapper.readValue(response.body(), new TypeReference<>() {
                });
                log.info("图片识别响应 tenant={} session={} status={} 可用={} 模型={} OCR={}字",
                        tenantCode, sessionNo, response.statusCode(), parsed.get("available"),
                        parsed.get("model"),
                        parsed.get("ocr_text") == null ? 0 : String.valueOf(parsed.get("ocr_text")).length());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(50001, parseDetail(response.body()));
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {
            });
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "图片识别调用失败：" + friendly(e));
        }
    }

    private String parseDetail(String body) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {
            });
            Object detail = parsed.get("detail");
            if (detail != null) {
                return String.valueOf(detail);
            }
        } catch (Exception ignored) {
            // 解析不出来就退回原文
        }
        return safe(body);
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 400 ? value.substring(0, 400) + "..." : value;
    }

    private String friendly(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("Connection refused") || message.contains("I/O error")) {
            return "AI 服务（yunti-ai）没启动";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
