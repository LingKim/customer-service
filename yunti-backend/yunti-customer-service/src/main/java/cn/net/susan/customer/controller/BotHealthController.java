package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.internal.BotAiClient;
import cn.net.susan.customer.internal.RealtimeNotifyClient;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.BotBrainService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「智能客服不回话」的一键体检。
 *
 * <p>机器人回一句话要跨三个服务：customer-service（落库与编排）→ yunti-ai（意图/情绪/回答）
 * → yunti-realtime-service（把回复推给客户）。任何一环没起、或者是旧版本，客户侧看到的
 * 现象都一模一样：**发出去没人理**。日志散在三个服务里，靠翻日志定位很慢。</p>
 *
 * <p>所以这里把"该通的三条路"一次性探完，并直接给出下一步该做什么：
 * 开关是不是关着、AI 有没有 /agent/brain、实时网关有没有 /internal/message。</p>
 */
@RestController
@RequestMapping("/api/customer/bot")
public class BotHealthController {

    private final BotBrainService botBrainService;
    private final BotAiClient aiClient;
    private final RealtimeNotifyClient realtimeClient;
    private final JwtTokenParser jwtTokenParser;

    public BotHealthController(
            BotBrainService botBrainService,
            BotAiClient aiClient,
            RealtimeNotifyClient realtimeClient,
            JwtTokenParser jwtTokenParser
    ) {
        this.botBrainService = botBrainService;
        this.aiClient = aiClient;
        this.realtimeClient = realtimeClient;
        this.jwtTokenParser = jwtTokenParser;
    }

    /**
     * 机器人链路体检（需要坐席登录态，走租户鉴权）。
     */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        Map<String, Object> ai = aiClient.health();
        Map<String, Object> realtime = realtimeClient.health();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", user.tenantCode());
        // 两个总开关：关掉了机器人自然不回话（这是配置，不是故障）
        body.put("botEnabled", botBrainService.enabled());
        body.put("receptionEnabled", botBrainService.receptionEnabled());
        body.put("ai", ai);
        body.put("realtime", realtime);
        body.put("hint", hint(botBrainService, ai, realtime));
        return ApiResponse.ok(body);
    }

    /** 一句话结论：按"最可能的原因"排优先级，直接告诉用户下一步干什么。 */
    private String hint(BotBrainService brain, Map<String, Object> ai, Map<String, Object> realtime) {
        if (!brain.enabled()) {
            return "机器人总开关是关的（yunti.bot.enabled=false），客户进线会直接排人工队列";
        }
        if (!brain.receptionEnabled()) {
            return "「机器人首轮接待」是关的（yunti.bot.reception-enabled=false），"
                    + "客户进线直接进人工队列；想让它先接待就打开这个开关";
        }
        if (!Boolean.TRUE.equals(ai.get("reachable"))) {
            return String.valueOf(ai.getOrDefault("hint",
                    "连不上 yunti-ai：机器人拿不到回复，客户会看到「正在为您转接人工客服」"));
        }
        if (!Boolean.TRUE.equals(realtime.get("reachable"))) {
            return String.valueOf(realtime.getOrDefault("hint",
                    "连不上实时网关：机器人回复能落库但推不到客户页面（客户刷新才能看到）"));
        }
        return "链路正常：机器人可以接待。若客户仍看不到回复，"
                + "确认会话状态是 2-机器人接待（已转人工或已被坐席接手的会话，机器人不会再回话）";
    }
}
