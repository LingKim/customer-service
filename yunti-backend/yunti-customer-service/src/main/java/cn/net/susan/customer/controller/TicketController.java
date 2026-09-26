package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.TicketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单中心：列表 / 详情 / 建单（含会话转单）/ 分派认领 / 回复 / 状态流转 / SLA 规则。
 *
 * <p>权限口径和在线客服一致：只有企业成员（userType=2）能用，租户从登录态里取，
 * 前端传什么都不影响工单归属。</p>
 */
@RestController
@RequestMapping("/api/customer/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final JwtTokenParser jwtTokenParser;

    public TicketController(TicketService ticketService, JwtTokenParser jwtTokenParser) {
        this.ticketService = ticketService;
        this.jwtTokenParser = jwtTokenParser;
    }

    @GetMapping("/overview")
    public ApiResponse<TicketService.OverviewVO> overview(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.overview(user));
    }

    /**
     * 当前登录人的工单权限：前端据此显隐按钮（服务端每个写接口仍会再校验一次）。
     */
    @GetMapping("/access")
    public ApiResponse<TicketService.AccessVO> access(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.access(user));
    }

    @GetMapping
    public ApiResponse<TicketService.TicketPageVO> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) Integer category,
            @RequestParam(required = false) Integer slaState,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Boolean mineOnly,
            @RequestParam(required = false) Boolean unassignedOnly,
            @RequestParam(required = false) String sessionNo,
            @RequestParam(required = false) String keyword,
            /** 工单类型：1-企业内部（默认）、2-平台支持（企业侧切到"提交给平台"页签时传 2） */
            @RequestParam(required = false) Integer ticketType,
            @RequestParam(required = false, defaultValue = "1") Integer page,
            @RequestParam(required = false, defaultValue = "10") Integer pageSize
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.list(user, new TicketService.TicketQuery(
                status, priority, category, slaState, assigneeId, mineOnly, unassignedOnly, sessionNo,
                keyword, ticketType),
                page, pageSize));
    }

    @GetMapping("/{ticketNo}")
    public ApiResponse<TicketService.TicketDetailVO> detail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ticketNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.detail(user, ticketNo));
    }

    /**
     * 建单：坐席手工建，或者工作台上"会话转工单"（带 sessionNo 就把会话上下文一起带进来）。
     */
    @PostMapping
    public ApiResponse<TicketService.TicketVO> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody TicketService.CreateBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.create(user, body));
    }

    /**
     * 企业侧：提交一条**平台支持工单**（渠道接入失败、计费异常这类平台才能处理的问题）。
     */
    @PostMapping("/platform")
    public ApiResponse<TicketService.TicketVO> createPlatform(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody TicketService.CreateBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.createPlatform(user, body));
    }

    /**
     * 企业侧：在平台支持工单下补充说明（不算平台的首次响应，也不改状态）。
     */
    @PostMapping("/{ticketNo}/supplement")
    public ApiResponse<TicketService.TicketDetailVO> supplement(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ticketNo,
            @RequestBody TicketService.ReplyBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.supplement(user, ticketNo, body));
    }

    /** 分派 / 转派 / 认领（toUserId 为空 = 认领给自己）。 */
    @PostMapping("/{ticketNo}/assign")
    public ApiResponse<TicketService.TicketDetailVO> assign(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ticketNo,
            @RequestBody(required = false) TicketService.AssignBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.assign(user, ticketNo, body));
    }

    /** 回复（visibleToCustomer=false 是内部备注，不算首次响应）。 */
    @PostMapping("/{ticketNo}/reply")
    public ApiResponse<TicketService.TicketDetailVO> reply(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ticketNo,
            @RequestBody TicketService.ReplyBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.reply(user, ticketNo, body));
    }

    /** 状态流转：处理中 / 待客户确认 / 已解决 / 已关闭（已关闭可重开成处理中）。 */
    @PostMapping("/{ticketNo}/status")
    public ApiResponse<TicketService.TicketDetailVO> changeStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ticketNo,
            @RequestBody TicketService.StatusBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.changeStatus(user, ticketNo, body));
    }

    /** 升级：优先级提一档 + 记一条「升级」（不重算 SLA 截止时间）。 */
    @PostMapping("/{ticketNo}/escalate")
    public ApiResponse<TicketService.TicketDetailVO> escalate(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String ticketNo,
            @RequestBody(required = false) TicketService.StatusBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.escalate(user, ticketNo, body));
    }

    /**
     * 导出 CSV（按当前筛选，最多 5000 条）。
     *
     * <p>带 UTF-8 BOM：Excel 打开中文才不是乱码。</p>
     */
    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> export(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) Integer category,
            @RequestParam(required = false) Integer slaState,
            @RequestParam(required = false) Boolean mineOnly,
            @RequestParam(required = false) Boolean unassignedOnly,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer ticketType
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        String csv = ticketService.exportCsv(user, new TicketService.TicketQuery(
                status, priority, category, slaState, null, mineOnly, unassignedOnly, null, keyword,
                ticketType));
        return csvResponse(csv, "tickets.csv");
    }

    /** CSV 响应（BOM 已在内容里，这里只补下载头） */
    static ResponseEntity<byte[]> csvResponse(String csv, String fileName) {
        byte[] body = csv.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"" + fileName + "\"; filename*=UTF-8''" + fileName)
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(body);
    }

    /** 手动触发一次 SLA 扫描（页面上的"检查超时"，也是验证脚本的入口）。 */
    @PostMapping("/scan-sla")
    public ApiResponse<TicketService.ScanResult> scanSla(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.scanSla(user));
    }

    @GetMapping("/sla-rules")
    public ApiResponse<List<TicketService.SlaRuleVO>> slaRules(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.slaRules(user));
    }

    @PutMapping("/sla-rules")
    public ApiResponse<List<TicketService.SlaRuleVO>> saveSlaRules(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody List<TicketService.SlaRuleBody> body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(ticketService.saveSlaRules(user, body));
    }
}
