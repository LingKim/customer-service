package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.QaService;
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
    private final JwtTokenParser jwtTokenParser;

    public QaController(QaService qaService, JwtTokenParser jwtTokenParser) {
        this.qaService = qaService;
        this.jwtTokenParser = jwtTokenParser;
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
                body.ruleNames()
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
        return ApiResponse.ok(qaService.batchAiCheck(user, body.taskNos()));
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
                body.enabled()
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
            @Max(4)
            Integer ruleType,

            @Size(max = 500, message = "规则内容最长 500 个字符")
            String ruleContent,

            @Min(value = 0, message = "权重不能小于 0")
            @Max(value = 100, message = "权重不能大于 100")
            int weight,

            boolean enabled
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

            List<@Size(max = 64) String> ruleNames
    ) {
    }
}
