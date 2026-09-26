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
 * yunti-ai「AI 客服大脑」客户端：意图识别 / 情绪识别 / 多轮状态 / 转人工决策都在 Python 侧。
 *
 * <p>为什么判断逻辑不放 Java：意图匹配要复用知识库那套中文片段切分，情绪和转人工策略
 * 又会不断调（加词、改阈值、换成模型判断），这些待在 Python 里改一版就能上线；
 * Java 侧只做它擅长的事——把消息落库、把会话状态流转、把结果推给长连接。</p>
 *
 * <p>调用失败一律抛业务异常，由 {@code BotBrainService} 决定"降级转人工"还是"直接放行"，
 * 绝不在这里假装成功——机器人答不出和机器人挂了，对客户来说是两回事。</p>
 */
@Component
public class BotAiClient {

    private static final Logger log = LoggerFactory.getLogger(BotAiClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final boolean logPayload;
    private final int timeoutSeconds;
    private final String internalSharedSecret;

    public BotAiClient(
            @Value("${yunti.ai.bot-base-url:http://127.0.0.1:9100}") String baseUrl,
            @Value("${yunti.ai.log-payload:false}") boolean logPayload,
            @Value("${yunti.ai.bot-timeout-seconds:60}") int timeoutSeconds,
            @Value("${yunti.internal.shared-secret:}") String internalSharedSecret
    ) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUrl = (baseUrl == null || baseUrl.isBlank())
                ? "http://127.0.0.1:9100" : baseUrl.replaceAll("/+$", "");
        this.logPayload = logPayload;
        this.timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        this.internalSharedSecret = internalSharedSecret;
    }

    /**
     * 让客服大脑处理一轮对话。
     *
     * @param messages 会话历史（按时间正序，role 取 user / assistant）
     * @return 大脑的处理结果（reply / intent / emotion / need_human / transfer_reason 等，键名是下划线风格）
     */
    public Map<String, Object> think(String tenantCode, String sessionNo,
                                     List<Map<String, String>> messages, int topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionNo == null ? "" : sessionNo);
        body.put("tenant_code", tenantCode);
        body.put("messages", messages == null ? List.of() : messages);
        body.put("top_k", topK <= 0 ? 5 : topK);
        // 调用前先留一行：这样"卡在 AI 调用上"和"压根没调"在日志里能区分开
        log.info("调用客服大脑 tenant={} session={} 历史={}条 问题={}",
                tenantCode, sessionNo, messages == null ? 0 : messages.size(),
                lastQuestion(messages));
        try {
            requireInternalSecret();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/agent/brain"))
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
                log.info("客服大脑响应 tenant={} session={} status={} body={}",
                        tenantCode, sessionNo, response.statusCode(), safe(response.body()));
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BizException(50001, describeStatus(response.statusCode(), parseDetail(response.body())));
            }
            return objectMapper.readValue(response.body(), new TypeReference<>() {
            });
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "客服大脑调用失败：" + friendly(e));
        }
    }

    /**
     * 大脑探活 + 体检（转发 yunti-ai 的 /agent/brain/health）。
     *
     * @return 体检结果；调不通时返回带 hint 的结果而不是抛异常——体检本身不该把调用方打挂
     */
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            requireInternalSecret();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/agent/brain/health"))
                    .timeout(Duration.ofSeconds(8))
                    .header("X-Yunti-Internal-Secret", internalSharedSecret)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            result.put("reachable", response.statusCode() >= 200 && response.statusCode() < 300);
            result.put("status", response.statusCode());
            result.put("baseUrl", baseUrl);
            if (response.statusCode() == 404) {
                result.put("hint", "yunti-ai 里没有 /api/ai/v1/agent/brain 接口：它还是旧版本，请重启 yunti-ai（或确认已拉最新代码）");
            } else if (response.statusCode() >= 300) {
                result.put("hint", describeStatus(response.statusCode(), parseDetail(response.body())));
            }
            Object detail = objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {
            }).get("detail");
            if (detail != null) {
                result.put("detail", detail);
            }
        } catch (Exception e) {
            result.put("reachable", false);
            result.put("baseUrl", baseUrl);
            result.put("hint", "连不上 yunti-ai（" + friendly(e) + "），请确认它已启动，端口与 yunti.ai.bot-base-url 一致");
        }
        return result;
    }

    /** 把 HTTP 状态码翻译成"下一步该干什么"，而不是只丢一个 404/500 给用户猜。 */
    private String describeStatus(int status, String detail) {
        if (status == 404) {
            return "AI 服务没有 /api/ai/v1/agent/brain 接口（服务是旧版本，请重启 yunti-ai）";
        }
        if (status >= 500) {
            return "AI 服务内部报错：" + detail;
        }
        return "AI 服务返回 " + status + "：" + detail;
    }

    /**
     * 取租户配置的欢迎语（进线时发一条，之后不再重复）。
     *
     * <p>为什么由 Java 来取：欢迎语存在 AI 侧的 bot_setting，而"客户进线"这件事发生在
     * customer-service。让开场白跟着会话开场走，而不是粘在第一条回答前面，
     * 客户体验才是"先说欢迎、再认真回答"。</p>
     *
     * <p>结果缓存 60 秒：欢迎语是低频变更的配置，没必要每开一个会话就多打一次 HTTP。</p>
     *
     * @return 欢迎语；没配置或 AI 服务不可用时返回 null（调用方退回一句通用系统提示）
     */
    public BotProfile botProfile(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return BotProfile.EMPTY;
        }
        long now = System.currentTimeMillis();
        Long cachedAt = greetingCacheAt.get(tenantCode);
        if (cachedAt != null && now - cachedAt < GREETING_CACHE_MILLIS) {
            return greetingCache.getOrDefault(tenantCode, BotProfile.EMPTY);
        }
        BotProfile profile = BotProfile.EMPTY;
        try {
            requireInternalSecret();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ai/v1/bot/internal/profile?tenant_code="
                            + java.net.URLEncoder.encode(tenantCode, StandardCharsets.UTF_8)))
                    // 超时给得短：这句欢迎语是在"客户开会话"路径上取的，
                    // AI 服务不可用时宁可立刻退回通用提示，也不能让客户等着开门
                    .timeout(Duration.ofSeconds(3))
                    .header("X-Tenant-Code", tenantCode)
                    .header("X-Yunti-Internal-Secret", internalSharedSecret)
                    .GET()
                    .build();
            String body = httpClient.send(request, HttpResponse.BodyHandlers.ofString(
                    StandardCharsets.UTF_8)).body();
            Map<String, Object> parsed = objectMapper.readValue(body, new TypeReference<>() {
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) parsed.get("data");
            if (data != null) {
                profile = new BotProfile(text(data.get("botName")), text(data.get("welcomeMessage")));
            }
        } catch (Exception e) {
            // 取不到不是错误：退回中性称呼即可，别让开场白拖住会话
            log.warn("读取机器人配置失败 tenant={} error={}", tenantCode, e.getMessage());
        }
        greetingCache.put(tenantCode, profile);
        greetingCacheAt.put(tenantCode, now);
        return profile;
    }

    private void requireInternalSecret() {
        if (internalSharedSecret == null || internalSharedSecret.isBlank()) {
            throw new BizException(50001, "未配置 AI 内部共享密钥");
        }
    }

    /** 取值并去掉空白；null 转成 null（调用方用默认值兜） */
    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String trimmed = String.valueOf(value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 机器人档案：客户侧显示什么名字、开场说什么。
     *
     * <p>为什么客户侧要用"机器人的名字"而不是"机器人"三个字：真实客服系统（企业微信、
     * 京东、淘宝店小蜜、Intercom…）给客户看的是**品牌/助手名字**，客户知道对面是什么，
     * 但不需要每条消息都被提醒"你在跟机器人说话"。所以名字来自配置（bot_name），
     * 缺省时才退回中性的"智能客服"。</p>
     */
    public record BotProfile(String botName, String welcomeMessage) {
        public static final BotProfile EMPTY = new BotProfile(null, null);
    }

    /** 机器人档案缓存：配置改动低频，60 秒内不重复问 AI 服务 */
    private final Map<String, BotProfile> greetingCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> greetingCacheAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long GREETING_CACHE_MILLIS = 60_000L;

    /** 日志里显示"这一轮客户问的是什么"：取最后一条 user 消息的前 60 字 */
    private String lastQuestion(List<Map<String, String>> messages) {
        if (messages == null || messages.isEmpty()) {
            return "-";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, String> item = messages.get(i);
            if ("user".equals(item.get("role"))) {
                return preview(item.get("content"));
            }
        }
        return preview(messages.get(messages.size() - 1).get("content"));
    }

    /** 内容预览：换行压平、超长截断 */
    private String preview(String content) {
        if (content == null || content.isBlank()) {
            return "-";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    /** 组装一条会话消息（Java 侧只负责角色映射，判断交给 Python）。 */
    public static Map<String, String> message(String role, String content) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("role", role);
        item.put("content", content == null ? "" : content);
        return item;
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
        return value.length() > 600 ? value.substring(0, 600) + "..." : value;
    }

    private String friendly(Exception e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("Connection refused") || message.contains("I/O error")) {
            return "AI 服务（yunti-ai）没启动";
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
