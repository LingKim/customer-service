package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.entity.AgentStatus;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.AgentStatusService;
import cn.net.susan.customer.service.SessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 坐席状态：工作台右上角切「在线 / 忙碌 / 小休」，路由据此决定谁能接新会话。
 */
@RestController
@RequestMapping("/api/customer/agent-status")
public class AgentStatusController {

    private final AgentStatusService agentStatusService;
    private final SessionService sessionService;
    private final JwtTokenParser jwtTokenParser;

    public AgentStatusController(
            AgentStatusService agentStatusService,
            SessionService sessionService,
            JwtTokenParser jwtTokenParser
    ) {
        this.agentStatusService = agentStatusService;
        this.sessionService = sessionService;
        this.jwtTokenParser = jwtTokenParser;
    }

    /**
     * 我的状态 + 同事状态（工作台一次性拿全）。
     */
    @GetMapping
    public ApiResponse<StatusView> list(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        AgentStatus mine = agentStatusService.of(user);
        List<AgentStatus> all = agentStatusService.list(mine.getTenantCode());
        // 当前接待量：路由派单时就是按"已接待单数 < 上限"筛人的。
        // 工作台把它显示出来，坐席才能明白"我明明在线，为什么没派给我"（接满了）。
        Map<Long, Integer> workload = sessionService.agentWorkload(mine.getTenantCode()).stream()
                .collect(Collectors.toMap(SessionService.AgentLoadVO::agentId,
                        load -> load.sessionCount() == null ? 0 : load.sessionCount(), (left, right) -> left));
        return ApiResponse.ok(new StatusView(
                toVO(mine, workload),
                all.stream().map(item -> toVO(item, workload)).toList()));
    }

    /**
     * 改我的状态（可选同时改"最多同时接待几单"）。
     */
    @PutMapping
    public ApiResponse<AgentStatusVO> update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody UpdateBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        AgentStatus updated = agentStatusService.update(user, body.status(), body.maxConcurrency());
        Map<Long, Integer> workload = sessionService.agentWorkload(updated.getTenantCode()).stream()
                .collect(Collectors.toMap(SessionService.AgentLoadVO::agentId,
                        load -> load.sessionCount() == null ? 0 : load.sessionCount(), (left, right) -> left));
        return ApiResponse.ok(toVO(updated, workload));
    }

    private AgentStatusVO toVO(AgentStatus status, Map<Long, Integer> workload) {
        int code = status.getStatus() == null ? AgentStatusService.STATUS_ONLINE : status.getStatus();
        int max = status.getMaxConcurrency() == null ? 5 : status.getMaxConcurrency();
        int active = workload.getOrDefault(status.getAgentId(), 0);
        return new AgentStatusVO(
                String.valueOf(status.getAgentId()),
                code,
                statusText(code),
                max,
                active,
                Boolean.TRUE.equals(status.getIsConnected()));
    }

    private String statusText(int status) {
        return switch (status) {
            case AgentStatusService.STATUS_BUSY -> "忙碌";
            case AgentStatusService.STATUS_BREAK -> "小休";
            default -> "在线";
        };
    }

    /**
     * 坐席状态。
     *
     * @param maxConcurrency 最多同时接待几单
     * @param activeCount    当前正在接待几单（坐席看到这个才知道自己是不是接满了）
     */
    public record AgentStatusVO(String agentId, int status, String statusText, int maxConcurrency,
                                int activeCount, boolean connected) {
    }

    /** 我的 + 同事的 */
    public record StatusView(AgentStatusVO mine, List<AgentStatusVO> agents) {
    }

    /** 改状态请求体 */
    public record UpdateBody(
            @Min(value = 1, message = "状态取值 1-在线、2-忙碌、3-小休")
            @Max(value = 3, message = "状态取值 1-在线、2-忙碌、3-小休")
            Integer status,

            @Min(value = 1, message = "最多同时接待数至少 1")
            @Max(value = 50, message = "最多同时接待数最多 50")
            Integer maxConcurrency
    ) {
    }
}
