package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.PlatformTicketService;
import cn.net.susan.customer.service.TicketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台侧：支持工单（企业提给平台的问题）列表与处理。
 *
 * <p>和 `/api/customer/tickets` 分开挂：那边是企业租户内的工单，鉴权口径是"本租户"；
 * 这边是跨租户的平台视角，鉴权口径是"平台账号"，两者混在一个 Controller 里最容易出越权。</p>
 */
@RestController
@RequestMapping("/api/platform/tickets")
public class PlatformTicketController {

    private final PlatformTicketService platformTicketService;
    private final JwtTokenParser jwtTokenParser;

    public PlatformTicketController(PlatformTicketService platformTicketService,
                                    JwtTokenParser jwtTokenParser) {
        this.platformTicketService = platformTicketService;
        this.jwtTokenParser = jwtTokenParser;
    }

    @GetMapping("/overview")
    public ApiResponse<PlatformTicketService.OverviewVO> overview(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(platformTicketService.overview(user));
    }

    /**
     * 跨租户列表：`tenant` 传租户号前几位即可模糊筛（前端筛选栏的"租户号"）。
     */
    @GetMapping
    public ApiResponse<TicketService.TicketPageVO> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) Integer slaState,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean mineOnly,
            @RequestParam(required = false) Boolean unassignedOnly,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(platformTicketService.list(user, new TicketService.TicketQuery(
                status, priority, null, slaState, null, mineOnly, unassignedOnly, tenant, keyword, null),
                page, pageSize));
    }

    @GetMapping("/{ticketNo}")
    public ApiResponse<TicketService.TicketDetailVO> detail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ticketNo,
            @RequestParam String tenant
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(platformTicketService.detail(user, tenant, ticketNo));
    }

    @PostMapping("/{ticketNo}/claim")
    public ApiResponse<TicketService.TicketDetailVO> claim(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ticketNo,
            @RequestParam String tenant
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(platformTicketService.claim(user, tenant, ticketNo));
    }

    @PostMapping("/{ticketNo}/reply")
    public ApiResponse<TicketService.TicketDetailVO> reply(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ticketNo,
            @RequestParam String tenant,
            @RequestBody TicketService.ReplyBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(platformTicketService.reply(user, tenant, ticketNo, body));
    }

    @PostMapping("/{ticketNo}/status")
    public ApiResponse<TicketService.TicketDetailVO> changeStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ticketNo,
            @RequestParam String tenant,
            @RequestBody TicketService.StatusBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(platformTicketService.changeStatus(user, tenant, ticketNo, body));
    }

    /** 平台侧升级：一线处理不动就提优先级（并通知企业），同样不重算 SLA 截止时间。 */
    @PostMapping("/{ticketNo}/escalate")
    public ApiResponse<TicketService.TicketDetailVO> escalate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ticketNo,
            @RequestParam String tenant,
            @RequestBody(required = false) TicketService.StatusBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(platformTicketService.escalate(user, tenant, ticketNo, body));
    }

    /** 平台侧导出 CSV（跨租户，带租户号） */
    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) Integer slaState,
            @RequestParam(required = false) String tenant,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean mineOnly,
            @RequestParam(required = false) Boolean unassignedOnly
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        String csv = platformTicketService.exportCsv(user, new TicketService.TicketQuery(
                status, priority, null, slaState, null, mineOnly, unassignedOnly, tenant, keyword, null));
        return TicketController.csvResponse(csv, "platform-support-tickets.csv");
    }
}
