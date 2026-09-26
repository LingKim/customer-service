package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.Customer360Service;
import cn.net.susan.customer.service.CustomerTagRuleService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 客户 360：客户画像、标签体系与会话轨迹。
 *
 * <p>挂在 {@code /api/customer/customers} 下（不是 {@code /api/customer}）：
 * 前缀单独一段，网关与前端代理都不用再动，日后要给"客户资料"单独加限流或审计也方便。</p>
 */
@RestController
@RequestMapping("/api/customer/customers")
public class Customer360Controller {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final Customer360Service customer360Service;
    private final JwtTokenParser jwtTokenParser;

    public Customer360Controller(Customer360Service customer360Service,
                                 JwtTokenParser jwtTokenParser) {
        this.customer360Service = customer360Service;
        this.jwtTokenParser = jwtTokenParser;
    }

    /** 客户列表（筛选 + 分页） */
    @GetMapping
    public ApiResponse<Customer360Service.CustomerPageVO> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) Integer riskLevel,
            @RequestParam(required = false) Integer customerType,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Integer activeDays,
            @RequestParam(required = false) Boolean hasTicket,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        Customer360Service.CustomerQuery query = new Customer360Service.CustomerQuery(
                keyword, level, riskLevel, customerType, tagId, channel, activeDays, hasTicket, sort);
        return ApiResponse.ok(customer360Service.list(user, query, page, pageSize));
    }

    /** 客户经营概览（顶部数字） */
    @GetMapping("/overview")
    public ApiResponse<Customer360Service.OverviewVO> overview(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.overview(user));
    }

    /** 当前登录人在客户 360 里的权限（前端据此显隐按钮） */
    @GetMapping("/access")
    public ApiResponse<Customer360Service.AccessVO> access(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.access(user));
    }

    /** 标签体系（含每个标签打了多少客户） */
    @GetMapping("/tags")
    public ApiResponse<List<Customer360Service.TagDefVO>> tags(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.tagDefs(user));
    }

    /** 规则标签可选的指标（前端规则编辑器用） */
    @GetMapping("/tags/metrics")
    public ApiResponse<List<Customer360Service.MetricOptionVO>> metricOptions(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.metricOptions());
    }

    /**
     * 手动重算全部客户的规则标签（仅企业管理员）。
     *
     * <p>改了规则的阈值之后必须按一次，存量客户才会回到新口径。</p>
     */
    @PostMapping("/tags/recalculate")
    public ApiResponse<CustomerTagRuleService.BatchResult> recalculateAllTags(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.recalculateAllTags(user));
    }

    /** 批量打标：勾一批客户打同一个标签（幂等，已打过的跳过） */
    @PostMapping("/tags/batch")
    public ApiResponse<Customer360Service.BatchTagResult> batchAddTag(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Customer360Service.BatchTagBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.batchAddTag(user, body));
    }

    /**
     * 客户导入（CSV，UTF-8）：表头 `姓名,手机号,会员等级,标签,备注`。
     *
     * <p>逐行容错：某一行有问题只跳过它，返回里带"第几行、为什么"。</p>
     */
    @PostMapping("/import")
    public ApiResponse<Customer360Service.ImportResult> importCustomers(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.importCsv(user, file));
    }

    /** 新建标签（仅企业管理员） */
    @PostMapping("/tags")
    public ApiResponse<Customer360Service.TagDefVO> createTag(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Customer360Service.TagDefBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.createTag(user, body));
    }

    /** 修改标签（仅企业管理员） */
    @PutMapping("/tags/{tagId}")
    public ApiResponse<Customer360Service.TagDefVO> updateTag(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long tagId,
            @RequestBody Customer360Service.TagDefBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.updateTag(user, tagId, body));
    }

    /** 启用 / 停用标签（仅企业管理员） */
    @PostMapping("/tags/{tagId}/enabled")
    public ApiResponse<Customer360Service.TagDefVO> toggleTag(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long tagId,
            @RequestParam boolean enabled
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.toggleTag(user, tagId, enabled));
    }

    /** 删除标签（仅企业管理员；还有客户在用时会拒绝） */
    @DeleteMapping("/tags/{tagId}")
    public ApiResponse<Void> deleteTag(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long tagId
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        customer360Service.deleteTag(user, tagId);
        return ApiResponse.ok();
    }

    /**
     * 导出客户 CSV（按当前筛选）。
     *
     * <p>注意：这里返回的是 {@code text/csv}，不是统一的 {@code {code,data}} 结构，
     * 前端要走原始 axios 实例，别被统一解包当成业务失败。</p>
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer level,
            @RequestParam(required = false) Integer riskLevel,
            @RequestParam(required = false) Integer customerType,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Integer activeDays,
            @RequestParam(required = false) Boolean hasTicket,
            @RequestParam(required = false) String sort
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        Customer360Service.CustomerQuery query = new Customer360Service.CustomerQuery(
                keyword, level, riskLevel, customerType, tagId, channel, activeDays, hasTicket, sort);
        String csv = customer360Service.exportCsv(user, query);
        String fileName = "客户列表-" + LocalDateTime.now().format(FILE_TIME) + ".csv";
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"customers.csv\"; filename*=UTF-8''" + encoded)
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** 客户 360 画像（档案 + 标签 + 统计 + 最近订单 + 关联工单） */
    @GetMapping("/{customerNo}")
    public ApiResponse<Customer360Service.CustomerDetailVO> detail(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String customerNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.detail(user, customerNo));
    }

    /** 会话轨迹 */
    @GetMapping("/{customerNo}/sessions")
    public ApiResponse<List<Customer360Service.SessionTraceVO>> sessions(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String customerNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.sessions(user, customerNo));
    }

    /** 客户动态 */
    @GetMapping("/{customerNo}/events")
    public ApiResponse<List<Customer360Service.EventVO>> events(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String customerNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.events(user, customerNo));
    }

    /** 打标 */
    @PostMapping("/{customerNo}/tags")
    public ApiResponse<Customer360Service.CustomerDetailVO> addTag(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String customerNo,
            @RequestBody Customer360Service.TagAssignBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.addTag(user, customerNo,
                body == null ? null : body.tagId(), body == null ? null : body.remark()));
    }

    /** 去标 */
    @DeleteMapping("/{customerNo}/tags/{tagId}")
    public ApiResponse<Customer360Service.CustomerDetailVO> removeTag(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String customerNo,
            @PathVariable Long tagId
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.removeTag(user, customerNo, tagId));
    }

    /** 改客户档案（姓名 / 手机号 / 会员等级 / 风险等级 / 备注） */
    @PostMapping("/{customerNo}/profile")
    public ApiResponse<Customer360Service.CustomerDetailVO> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String customerNo,
            @RequestBody Customer360Service.ProfileBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.updateProfile(user, customerNo,
                body == null ? new Customer360Service.ProfileBody(null, null, null, null, null, null) : body));
    }

    /** 查看完整手机号（仅管理员 / 客服主管，且每次查看都会写一条客户动态） */
    @PostMapping("/{customerNo}/phone/reveal")
    public ApiResponse<Customer360Service.PhoneVO> revealPhone(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String customerNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.revealPhone(user, customerNo));
    }

    /** 重算这个客户的规则标签 */
    @PostMapping("/{customerNo}/tags/recalculate")
    public ApiResponse<CustomerTagRuleService.ApplyResult> recalculateTags(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String customerNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.recalculateTags(user, customerNo));
    }

    /** 合并重复客户（仅企业管理员）：把 source 的关系迁到 target，源客户软删 */
    @PostMapping("/{sourceNo}/merge")
    public ApiResponse<Customer360Service.MergeResult> merge(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String sourceNo,
            @RequestParam String targetCustomerNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.merge(user, sourceNo, targetCustomerNo));
    }

    /** 匿名化（仅企业管理员）：抹掉姓名 / 手机号 / 备注，保留会话与统计 */
    @PostMapping("/{customerNo}/anonymize")
    public ApiResponse<Customer360Service.CustomerDetailVO> anonymize(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String customerNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(customer360Service.anonymize(user, customerNo));
    }

    /** 删除客户（仅企业管理员）：软删 + 抹 PII，列表里不再出现 */
    @DeleteMapping("/{customerNo}")
    public ApiResponse<Void> deleteCustomer(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String customerNo
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        customer360Service.deleteCustomer(user, customerNo);
        return ApiResponse.ok();
    }
}
