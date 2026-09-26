package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.QaService;
import cn.net.susan.customer.service.RealtimeQaService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 质检中心：看板、任务、扫描、复核与规则配置。
 */
@RestController
@RequestMapping("/api/customer/qa")
public class QaController {

    private final QaService qaService;
    private final RealtimeQaService realtimeQaService;
    private final JwtTokenParser jwtTokenParser;

    public QaController(QaService qaService,
                        RealtimeQaService realtimeQaService,
                        JwtTokenParser jwtTokenParser) {
        this.qaService = qaService;
        this.realtimeQaService = realtimeQaService;
        this.jwtTokenParser = jwtTokenParser;
    }

    /**
     * 实时质检告警列表：质检中心「实时预警」用。
     */
    @GetMapping("/alerts")
    public ApiResponse<List<RealtimeQaService.AlertVO>> alerts(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer severity,
            @RequestParam(required = false) Integer ruleType,
            @RequestParam(required = false) String sessionNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(realtimeQaService.alerts(user, new RealtimeQaService.AlertQuery(
                status, severity, ruleType, sessionNo, keyword, startTime, endTime), authorization));
    }

    /**
     * 某个会话的告警：坐席工作台切到这条会话时拉一次，之前错过的预警也不会漏。
     */
    @GetMapping("/alerts/session/{sessionNo}")
    public ApiResponse<List<RealtimeQaService.AlertVO>> sessionAlerts(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sessionNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(realtimeQaService.alertsOfSession(user, sessionNo, authorization));
    }

    /**
     * 标记告警已处理（坐席当场处置完点一下）。
     */
    @PostMapping("/alerts/{id}/handle")
    public ApiResponse<RealtimeQaService.AlertVO> handleAlert(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id,
            @RequestBody(required = false) HandleAlertBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(realtimeQaService.handle(user, id, body == null ? null : body.remark(), authorization));
    }

    @GetMapping("/overview")
    public ApiResponse<QaService.OverviewVO> overview(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(qaService.overview(user));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<QaService.TaskVO>> tasks(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(qaService.tasks(user, status, keyword));
    }

    @GetMapping("/tasks/{taskNo}")
    public ApiResponse<QaService.TaskDetailVO> detail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String taskNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(qaService.detail(user, taskNo));
    }

    @PostMapping("/scan")
    public ApiResponse<QaService.ScanResult> scan(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(qaService.scan(user));
    }

    @PostMapping("/tasks/manual")
    public ApiResponse<QaService.TaskVO> createManual(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ManualTaskBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(qaService.createManual(
                user,
                body.sessionName(),
                body.agentName(),
                body.aiScore(),
                body.riskLevel(),
                body.comment(),
                body.ruleNames(),
                body.transcript(),
                authorization
        ));
    }

    @PostMapping("/tasks/{taskNo}/review")
    public ApiResponse<QaService.TaskVO> review(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String taskNo,
            @Valid @RequestBody ReviewBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(qaService.review(user, taskNo, body.action(), body.score(), body.comment()));
    }

    @PostMapping("/tasks/batch-review")
    public ApiResponse<QaService.BatchReviewResult> batchReview(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody BatchReviewBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(qaService.batchReview(
                user,
                body.taskNos(),
                body.action(),
                body.score(),
                body.comment()
        ));
    }

    @PostMapping("/tasks/batch-ai")
    public ApiResponse<QaService.BatchAiResult> batchAi(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody AiBatchBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(qaService.batchAiCheck(user, body.taskNos(), authorization));
    }

    @GetMapping("/rules")
    public ApiResponse<List<QaService.RuleVO>> rules(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(qaService.rules(user));
    }

    @PostMapping("/rules")
    public ApiResponse<QaService.RuleVO> saveRule(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody RuleBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(qaService.saveRule(
                user,
                body.id(),
                body.ruleName(),
                body.ruleType(),
                body.ruleContent(),
                body.weight(),
                body.enabled(),
                body.realtime() == null || body.realtime(),
                body.hitKeywords(),
                body.severity(),
                body.timeoutSeconds()
        ));
    }

    @DeleteMapping("/rules/{id}")
    public ApiResponse<Void> deleteRule(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String id
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        qaService.deleteRule(user, id);
        return ApiResponse.ok();
    }

    public record RuleBody(
            @Size(max = 40)
            String id,

            @NotBlank(message = "请填写规则名称")
            @Size(max = 64, message = "规则名称最长 64 个字符")
            String ruleName,

            @NotNull(message = "请选择规则类型")
            @Min(1)
            @Max(5)
            Integer ruleType,

            @Size(max = 500, message = "规则内容最长 500 个字符")
            String ruleContent,

            @Min(value = 0, message = "权重不能小于 0")
            @Max(value = 100, message = "权重不能大于 100")
            int weight,

            boolean enabled,

            /** 是否参与实时质检（不传按参与处理） */
            Boolean realtime,

            @Size(max = 512, message = "命中词最长 512 个字符")
            String hitKeywords,

            @Min(value = 1, message = "告警级别取值 1-3")
            @Max(value = 3, message = "告警级别取值 1-3")
            Integer severity,

            /** 仅"5-响应超时"类规则使用：客户发完消息多久没回算超时 */
            @Min(value = 10, message = "响应超时秒数至少 10 秒")
            @Max(value = 3600, message = "响应超时秒数最多 3600 秒")
            Integer timeoutSeconds
    ) {
    }

    public record ReviewBody(
            @NotNull(message = "请选择复核动作")
            @Min(1)
            @Max(3)
            Integer action,

            @Min(value = 0, message = "复核评分不能小于 0")
            @Max(value = 100, message = "复核评分不能大于 100")
            Double score,

            @Size(max = 512, message = "复核意见最长 512 个字符")
            String comment
    ) {
    }

    /** 处理告警请求体 */
    public record HandleAlertBody(@Size(max = 512, message = "处理说明最长 512 个字符") String remark) {
    }

    public record BatchReviewBody(
            @Size(min = 1, max = 200, message = "请选择 1~200 条质检任务")
            List<@NotBlank(message = "质检任务编号不能为空") @Size(max = 40) String> taskNos,

            @NotNull(message = "请选择复核动作")
            @Min(1)
            @Max(3)
            Integer action,

            @Min(value = 0, message = "复核评分不能小于 0")
            @Max(value = 100, message = "复核评分不能大于 100")
            Double score,

            @Size(max = 512, message = "复核意见最长 512 个字符")
            String comment
    ) {
    }

    public record AiBatchBody(
            @Size(min = 1, max = 200, message = "请选择 1~200 条质检任务")
            List<@NotBlank(message = "质检任务编号不能为空") @Size(max = 40) String> taskNos
    ) {
    }

    public record ManualTaskBody(
            @NotBlank(message = "请填写质检会话名称")
            @Size(max = 128, message = "质检会话名称最长 128 个字符")
            String sessionName,

            @NotBlank(message = "请填写接待客服")
            @Size(max = 64, message = "接待客服最长 64 个字符")
            String agentName,

            @Min(value = 0, message = "AI 初检分不能小于 0")
            @Max(value = 100, message = "AI 初检分不能大于 100")
            Double aiScore,

            @Min(1)
            @Max(3)
            Integer riskLevel,

            @Size(max = 512, message = "质检备注最长 512 个字符")
            String comment,

            List<@Size(max = 64) String> ruleNames,

            @Size(max = 20000, message = "会话文本最长 20000 字符")
            String transcript
    ) {
    }
}
