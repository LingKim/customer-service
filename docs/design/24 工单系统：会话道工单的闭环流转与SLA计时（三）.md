---
title: "24 工单系统：会话道工单的闭环流转与SLA计时（三）"
source: "https://articles.zsxq.com/id_ct2iqvo9fq81.html"
author:
  - "[[苏三]]"
published:
created: 2026-09-25
description:
tags:
  - "clippings"
---
[来自： Java突击队&AI项目实战](https://wx.zsxq.com/group/28851182188851)

### 4.3 平台支持工单（企业提给平台）

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/service/PlatformTicketService.java

平台侧服务，单独一个类而不是塞进 TicketService：**权限模型完全不同**——企业侧是"租户内"，平台侧是"跨租户、按人认领"，混在一起迟早出现"某个方法忘了校验租户"。这里所有查询都显式带 `ticket_type=2`，每条操作都要求传租户号（工单号只在租户内唯一）。SLA 用**平台统一档位**，不读企业自己的规则——企业改配置不该影响"平台承诺多久响应我"。

``` code-block-container
package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.customer.entity.Ticket;
import cn.net.susan.customer.entity.TicketEvent;
import cn.net.susan.customer.mapper.TicketEventMapper;
import cn.net.susan.customer.mapper.TicketMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台支持工单：企业提给平台的问题（渠道接入失败、计费异常、平台故障…），平台侧跨租户处理。
 *
 * <p>为什么单独一个 Service 而不是塞进 {@link TicketService}：权限模型完全不同——
 * 企业侧是"租户内"，平台侧是"跨租户、按人（平台运营）认领"，混在一起写迟早会出现
 * "某个方法忘了校验租户"的漏洞。这里所有查询都显式带 `ticket_type = 2`，</p>
 *
 * <p>共用的东西照旧复用：工单表、流转记录表、SLA 判定口径（{@link TicketService} 里的包内方法），
 * 所以企业侧看到的进展、时长、流转时间和平台侧是同一套数字。</p>
 */
@Service
public class PlatformTicketService {

    private static final Logger log = LoggerFactory.getLogger(PlatformTicketService.class);

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPORT_LIMIT = 5000;

    private final TicketMapper ticketMapper;
    private final TicketEventMapper ticketEventMapper;
    private final TicketService ticketService;
    private final NotificationService notificationService;

    public PlatformTicketService(TicketMapper ticketMapper,
                                 TicketEventMapper ticketEventMapper,
                                 TicketService ticketService,
                                 NotificationService notificationService) {
        this.ticketMapper = ticketMapper;
        this.ticketEventMapper = ticketEventMapper;
        this.ticketService = ticketService;
        this.notificationService = notificationService;
    }

    /** 平台账号才能进：userType=1（平台运营 / 平台管理员都在这一类） */
    private void requirePlatform(LoginUser user) {
        if (user == null || user.userType() != 1) {
            throw new BizException(40301, "仅平台账号可使用支持工单");
        }
    }

    private LambdaQueryWrapper<Ticket> baseQuery() {
        return Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getTicketType, TicketService.TICKET_TYPE_PLATFORM)
                .eq(Ticket::getDeleted, false);
    }

    /** 跨租户列表：按创建时间倒序（和企业侧工单中心保持一致） */
    public TicketService.TicketPageVO list(LoginUser user, TicketService.TicketQuery query,
                                           Integer page, Integer pageSize) {
        requirePlatform(user);
        int current = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1
                ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        // 导出要突破单页上限（5000 条），所以内部走不受 100 限制的版本
        return queryPage(user, query, current, size);
    }

    private TicketService.TicketPageVO queryPage(LoginUser user, TicketService.TicketQuery query,
                                                 int current, int size) {
        TicketService.TicketQuery q = query == null
                ? new TicketService.TicketQuery(null, null, null, null, null, null, null, null, null, null)
                : query;
        LocalDateTime now = LocalDateTime.now();

        Long total = ticketMapper.selectCount(conditions(q, user));
        var wrapper = conditions(q, user);
        wrapper.last("ORDER BY create_time DESC"
                + " LIMIT " + size + " OFFSET " + (long) (current - 1) * size);
        List<Ticket> rows = ticketMapper.selectList(wrapper);
        List<TicketService.TicketVO> items = new ArrayList<>(rows.size());
        for (Ticket row : rows) {
            items.add(ticketService.toVO(row, now));
        }
        return new TicketService.TicketPageVO(total == null ? 0 : total, current, size, items);
    }

    private LambdaQueryWrapper<Ticket> conditions(TicketService.TicketQuery q, LoginUser user) {
        var wrapper = baseQuery();
        if (q.status() != null) {
            wrapper.eq(Ticket::getStatus, q.status());
        }
        if (q.priority() != null) {
            wrapper.eq(Ticket::getPriority, q.priority());
        }
        if (q.slaState() != null) {
            wrapper.eq(Ticket::getSlaState, q.slaState());
        }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            String keyword = q.keyword().trim();
            wrapper.and(w -> w.like(Ticket::getTitle, keyword)
                    .or().like(Ticket::getTicketNo, keyword)
                    .or().like(Ticket::getDesc, keyword));
        }
        // 租户号筛选放在 sessionNo 字段里传（前端筛选栏是"租户号"输入框），语义等价于"哪家企业提的"
        if (q.sessionNo() != null && !q.sessionNo().isBlank()) {
            wrapper.likeRight(Ticket::getTenantCode, q.sessionNo().trim());
        }
        if (Boolean.TRUE.equals(q.mineOnly())) {
            wrapper.eq(Ticket::getAssigneeId, user.userId());
        }
        if (Boolean.TRUE.equals(q.unassignedOnly())) {
            wrapper.isNull(Ticket::getAssigneeId);
        }
        return wrapper;
    }

    /** 平台看板：待处理 / 处理中 / 待企业确认 / 已解决 / 超时 / 我处理的 */
    public OverviewVO overview(LoginUser user) {
        requirePlatform(user);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        List<Ticket> open = ticketMapper.selectList(baseQuery()
                .in(Ticket::getStatus, TicketService.STATUS_PENDING, TicketService.STATUS_PROCESSING,
                        TicketService.STATUS_CONFIRMING));
        int pending = 0;
        int processing = 0;
        int confirming = 0;
        int overdue = 0;
        int warning = 0;
        int mine = 0;
        for (Ticket ticket : open) {
            int status = ticket.getStatus() == null ? TicketService.STATUS_PENDING : ticket.getStatus();
            if (status == TicketService.STATUS_PENDING) {
                pending++;
            } else if (status == TicketService.STATUS_PROCESSING) {
                processing++;
            } else {
                confirming++;
            }
            int state = ticketService.computeSlaState(ticket, now);
            if (state == TicketService.SLA_OVERDUE) {
                overdue++;
            } else if (state == TicketService.SLA_WARNING) {
                warning++;
            }
            if (ticket.getAssigneeId() != null && ticket.getAssigneeId() == user.userId()) {
                mine++;
            }
        }
        Long resolvedToday = ticketMapper.selectCount(baseQuery()
                .in(Ticket::getStatus, TicketService.STATUS_RESOLVED, TicketService.STATUS_CLOSED)
                .ge(Ticket::getResolvedAt, todayStart));
        return new OverviewVO(pending, processing, confirming, overdue, warning, mine,
                resolvedToday == null ? 0 : resolvedToday.intValue());
    }

    public TicketService.TicketDetailVO detail(LoginUser user, String tenantCode, String ticketNo) {
        requirePlatform(user);
        Ticket ticket = requirePlatformTicket(tenantCode, ticketNo);
        LocalDateTime now = LocalDateTime.now();
        List<TicketEvent> events = ticketEventMapper.selectList(Wrappers.<TicketEvent>lambdaQuery()
                .eq(TicketEvent::getTenantCode, tenantCode)
                .eq(TicketEvent::getTicketId, ticket.getId())
                .orderByAsc(TicketEvent::getEventTime)
                .last("LIMIT 200"));
        List<TicketService.EventVO> timeline = new ArrayList<>(events.size());
        for (TicketEvent event : events) {
            timeline.add(ticketService.toEventVO(event));
        }
        return new TicketService.TicketDetailVO(ticketService.toVO(ticket, now), ticket.getDesc(),
                timeline, new TicketService.SlaVO(
                ticket.getSlaFirstMinutes() == null ? 0 : ticket.getSlaFirstMinutes(),
                ticket.getSlaResolveMinutes() == null ? 0 : ticket.getSlaResolveMinutes()),
                tenantCode);
    }

    /** 平台认领：把处理人设成自己（谁接谁负责，和租户内的口径一致） */
    @Transactional
    public TicketService.TicketDetailVO claim(LoginUser user, String tenantCode, String ticketNo) {
        requirePlatform(user);
        Ticket ticket = requirePlatformTicket(tenantCode, ticketNo);
        requirePlatformOpen(ticket, "认领");
        int fromStatus = ticket.getStatus() == null ? TicketService.STATUS_PENDING : ticket.getStatus();
        int toStatus = fromStatus == TicketService.STATUS_PENDING
                ? TicketService.STATUS_PROCESSING : fromStatus;
        LocalDateTime now = LocalDateTime.now();
        Ticket update = new Ticket();
        update.setId(ticket.getId());
        update.setAssigneeId(user.userId());
        update.setAssigneeName(user.name());
        update.setStatus(toStatus);
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(now);
        ticketMapper.updateById(update);
        ticketService.appendEvent(tenantCode, ticket.getId(), TicketService.EVENT_ASSIGN,
                user.userId(), user.name(), fromStatus, toStatus, false, "平台认领：" + user.name());
        log.info("平台认领支持工单 tenant={} ticketNo={} 处理人={}", tenantCode, ticketNo, user.name());
        return detail(user, tenantCode, ticketNo);
    }

    /** 平台回复：第一条对**企业可见**的回复就是首次响应（SL A 是平台对企业的承诺） */
    @Transactional
    public TicketService.TicketDetailVO reply(LoginUser user, String tenantCode, String ticketNo,
                                              TicketService.ReplyBody body) {
        requirePlatform(user);
        Ticket ticket = requirePlatformTicket(tenantCode, ticketNo);
        requirePlatformOpen(ticket, "回复");
        String content = body == null || body.content() == null ? "" : body.content().trim();
        if (content.isEmpty()) {
            throw new BizException(40001, "回复内容不能为空");
        }
        boolean visible = body.visibleToCustomer() == null || body.visibleToCustomer();
        LocalDateTime now = LocalDateTime.now();
        int fromStatus = ticket.getStatus() == null ? TicketService.STATUS_PENDING : ticket.getStatus();
        int toStatus = fromStatus == TicketService.STATUS_PENDING
                ? TicketService.STATUS_PROCESSING : fromStatus;
        Ticket update = new Ticket();
        update.setId(ticket.getId());
        update.setStatus(toStatus);
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(now);
        if (ticket.getFirstResponseAt() == null && visible) {
            update.setFirstResponseAt(now);
        }
        if (ticket.getAssigneeId() == null) {
            update.setAssigneeId(user.userId());
            update.setAssigneeName(user.name());
        }
        ticketMapper.updateById(update);
        ticketService.appendEvent(tenantCode, ticket.getId(), TicketService.EVENT_REPLY,
                user.userId(), user.name(), fromStatus, toStatus, visible,
                visible ? content : "【内部备注】" + content);
        return detail(user, tenantCode, ticketNo);
    }

    /** 平台流转状态：处理中 / 待企业确认 / 已解决 / 已关闭（已关闭可重开） */
    @Transactional
    public TicketService.TicketDetailVO changeStatus(LoginUser user, String tenantCode, String ticketNo,
                                                     TicketService.StatusBody body) {
        requirePlatform(user);
        Ticket ticket = requirePlatformTicket(tenantCode, ticketNo);
        int target = body == null || body.status() == null ? 0 : body.status();
        if (target < TicketService.STATUS_PENDING || target > TicketService.STATUS_CLOSED) {
            throw new BizException(40001, "工单状态不合法");
        }
        int current = ticket.getStatus() == null ? TicketService.STATUS_PENDING : ticket.getStatus();
        if (current == target) {
            throw new BizException(40001, "工单已经是「" + TicketService.statusText(target) + "」了");
        }
        if (current == TicketService.STATUS_CLOSED && target != TicketService.STATUS_PROCESSING) {
            throw new BizException(40001, "已关闭的工单只能重新打开（转为处理中）");
        }
        String remark = body == null || body.remark() == null ? null : body.remark().trim();
        LocalDateTime now = LocalDateTime.now();
        boolean finished = target == TicketService.STATUS_RESOLVED || target == TicketService.STATUS_CLOSED;
        boolean reopen = current == TicketService.STATUS_CLOSED
                && target == TicketService.STATUS_PROCESSING;
        Ticket update = new Ticket();
        update.setId(ticket.getId());
        update.setStatus(target);
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(now);
        if (target == TicketService.STATUS_RESOLVED && ticket.getResolvedAt() == null) {
            update.setResolvedAt(now);
        }
        if (target == TicketService.STATUS_CLOSED) {
            update.setCloseTime(now);
            if (ticket.getResolvedAt() == null) {
                update.setResolvedAt(now);
            }
        }
        if (reopen) {
            update.setResolvedAt(null);
            update.setCloseTime(null);
        }
        if (finished) {
            update.setSlaState(TicketService.SLA_NORMAL);
            update.setSlaAlerted(false);
        }
        ticketMapper.updateById(update);
        int eventType = reopen ? TicketService.EVENT_REOPEN
                : (finished ? TicketService.EVENT_CLOSE : TicketService.EVENT_STATUS);
        ticketService.appendEvent(tenantCode, ticket.getId(), eventType, user.userId(), user.name(),
                current, target, true,
                (reopen ? "平台重新打开工单" : "平台状态变更为「" + TicketService.statusText(target) + "」")
                        + (remark == null || remark.isEmpty() ? "" : "：" + remark));
        log.info("平台流转支持工单 tenant={} ticketNo={} {} → {} 操作人={}",
                tenantCode, ticketNo, TicketService.statusText(current),
                TicketService.statusText(target), user.name());
        return detail(user, tenantCode, ticketNo);
    }

    private Ticket requirePlatformTicket(String tenantCode, String ticketNo) {
        if (tenantCode == null || tenantCode.isBlank() || ticketNo == null || ticketNo.isBlank()) {
            throw new BizException(40001, "缺少租户号或工单号");
        }
        Ticket ticket = ticketMapper.selectOne(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getTenantCode, tenantCode.trim())
                .eq(Ticket::getTicketNo, ticketNo.trim())
                .eq(Ticket::getTicketType, TicketService.TICKET_TYPE_PLATFORM)
                .eq(Ticket::getDeleted, false)
                .last("LIMIT 1"));
        if (ticket == null) {
            throw new BizException(40401, "支持工单不存在：" + tenantCode + " / " + ticketNo);
        }
        return ticket;
    }

    private void requirePlatformOpen(Ticket ticket, String action) {
        if (ticketService.isFinished(ticket)) {
            throw new BizException(40001, "工单" + TicketService.statusText(ticket.getStatus())
                    + "，不能再" + action + "；还需要跟进请先重新打开");
        }
    }

    /** 平台看板数字（和企业侧同构，前端可以复用同一套卡片样式） */
    public record OverviewVO(int pending, int processing, int confirming, int overdue, int warning,
                             int mine, int resolvedToday) {
    }

    /**
     * 平台侧导出 CSV（跨租户，带租户号）：一线/二线把支持工单拉出来对账用。
     */
    public String exportCsv(LoginUser user, TicketService.TicketQuery query) {
        requirePlatform(user);
        TicketService.TicketPageVO page = queryPage(user, query, 1, EXPORT_LIMIT);
        StringBuilder csv = new StringBuilder("\ufeff");
        csv.append("工单号,租户,主题,分类,优先级,状态,SLA,剩余/超时(分钟),处理人,提交人,创建时间\n");
        for (TicketService.TicketVO item : page.list()) {
            csv.append(cell(item.ticketNo())).append(',')
                    .append(cell(item.tenantCode())).append(',')
                    .append(cell(item.title())).append(',')
                    .append(cell(item.categoryText())).append(',')
                    .append(cell(item.priorityText())).append(',')
                    .append(cell(item.statusText())).append(',')
                    .append(cell(item.slaStateText())).append(',')
                    .append(item.remainMinutes() == null ? "" : item.remainMinutes()).append(',')
                    .append(cell(item.assigneeName())).append(',')
                    .append(cell(item.creatorName())).append(',')
                    .append(cell(item.createTime())).append('\n');
        }
        return csv.toString();
    }

    private static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value.replace("\"", "\"\"");
        return text.contains(",") || text.contains("\"") || text.contains("\n")
                ? "\"" + text + "\"" : text;
    }

    /**
     * 平台侧升级：优先级提一档（一线处理不动就升级到二线关注），并通知企业。
     *
     * <p>和企业侧同口径：**不重算 SLA 截止时间**，只把优先级提上去、记录留痕、通知到位。</p>
     */
    @Transactional
    public TicketService.TicketDetailVO escalate(LoginUser user, String tenantCode, String ticketNo,
                                                 TicketService.StatusBody body) {
        requirePlatform(user);
        Ticket ticket = requirePlatformTicket(tenantCode, ticketNo);
        requirePlatformOpen(ticket, "升级");
        int current = ticket.getPriority() == null ? TicketService.PRIORITY_NORMAL : ticket.getPriority();
        int next = Math.min(TicketService.PRIORITY_URGENT, current + 1);
        String remark = body == null || body.remark() == null ? null : body.remark().trim();
        LocalDateTime now = LocalDateTime.now();
        Ticket update = new Ticket();
        update.setId(ticket.getId());
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(now);
        if (next != current) {
            update.setPriority(next);
        }
        ticketMapper.updateById(update);
        ticketService.appendEvent(tenantCode, ticket.getId(), TicketService.EVENT_ESCALATE,
                user.userId(), user.name(), null, null, true,
                "平台升级：" + TicketService.priorityText(current) + " → " + TicketService.priorityText(next)
                        + (remark == null || remark.isEmpty() ? "" : "：" + remark));
        notificationService.publishToTenant(tenantCode, NotificationService.TYPE_TICKET,
                "支持工单升级：" + ticketNo,
                "[" + TicketService.priorityText(next) + "] " + ticket.getTitle()
                        + (remark == null || remark.isEmpty() ? "" : "（" + remark + "）"),
                "platform-support");
        log.info("平台升级支持工单 tenant={} ticketNo={} {} → {} 操作人={}",
                tenantCode, ticketNo, TicketService.priorityText(current),
                TicketService.priorityText(next), user.name());
        return detail(user, tenantCode, ticketNo);
    }
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/controller/PlatformTicketController.java

平台侧接口，挂在 `/api/platform/tickets` 而不是 `/api/customer/**`：前缀分开，鉴权口径才能分开（这边只认平台账号），也方便网关以后给平台接口统一加限流。

``` code-block-container
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
```

### 4.4 权限：角色从 user-service 反查

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/internal/UserRoleClient.java

内部客户端：查"这个人在这个租户下是什么角色"。为什么不用 JWT 里带的角色：角色会变（今天把客服提成主管，明天就该生效），塞进令牌要等过期才生效；而且令牌是给前端的，不该承载服务间的权限判断。注意返回值语义：**null = 问不到**（用户服务不可用），**空集合 = 这个租户还没有角色数据**（老租户），调用方要区别对待。

``` code-block-container
package cn.net.susan.customer.internal;

import cn.net.susan.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * user-service 内部客户端：查用户的租户角色（工单权限判断用）。
 *
 * <p>为什么让 customer-service 反向查一次，而不是把角色塞进 JWT：
 * 角色是**会变的**（今天把某个客服提成主管，明天就该生效），塞进令牌要等令牌过期才生效；
 * 而且令牌是给前端用的，不该承载服务间的权限判断。这里一次内部调用就够（本地毫秒级）。</p>
 */
@Component
public class UserRoleClient {

    private static final Logger log = LoggerFactory.getLogger(UserRoleClient.class);

    private final RestClient restClient;

    public UserRoleClient(@Value("${yunti.user.internal-base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 查用户在某租户下的角色编码。
     *
     * @return 角色编码列表；**返回 null 表示"问不到"**（用户服务不可用），
     *         返回空列表表示"这个租户还没有角色数据"（老租户，按企业管理员兼容）。
     *         两者语义不同，调用方要区别对待：问不到时保守拒绝管理类操作，
     *         但日常作业（建单/回复）照常放行。
     */
    public List<String> rolesOf(long userId, String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return List.of();
        }
        try {
            ApiResponse<List<String>> response = restClient.get()
                    .uri("/api/user/internal/users/{userId}/roles?tenantCode={tenant}",
                            userId, tenantCode)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return response == null || response.data() == null ? List.of() : response.data();
        } catch (Exception e) {
            log.warn("查询用户角色失败（按保守策略处理）userId={} tenant={} error={}",
                    userId, tenantCode, e.getMessage());
            return null;
        }
    }
}
```

### 改动：yunti-backend/yunti-user-service/src/main/java/cn/net/susan/user/service/MemberInviteService.java

改动点：新增角色查询：口径和成员管理一致（没配角色的老租户返回空集合，按企业管理员兼容）

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 140 行附近）**

原来是这样：

``` code-block-container
    }

    /**
     * 企业成员列表；返回前补全当前管理员的默认角色/成员关系，保证列表口径完整。
     */
    @Transactional
```

改成：

``` code-block-container
    }

    /**
     * 某个用户在某租户下的角色编码（内部接口：customer-service 判工单权限用）。
     *
     * <p>口径和 {@link #memberAccess(LoginUser)} 保持一致：没配角色的老租户返回空集合，
     * 调用方按"老账号 = 企业管理员"兼容；平台租户也返回空集合。</p>
     */
    public List<String> roleCodesOf(String tenantCode, long userId) {
        if (tenantCode == null || tenantCode.isBlank() || AuthConstants.TENANT_CODE_PLATFORM.equals(tenantCode)) {
            return List.of();
        }
        List<SysUserRole> relations = sysUserRoleMapper.selectList(
                Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getTenantCode, tenantCode)
                        .eq(SysUserRole::getUserId, userId));
        if (relations.isEmpty()) {
            return List.of();
        }
        List<SysRole> roles = sysRoleMapper.selectBatchIds(
                relations.stream().map(SysUserRole::getRoleId).distinct().toList());
        return roles.stream()
                .filter(role -> Boolean.FALSE.equals(role.getDeleted()))
                .filter(role -> role.getRoleCode() != null && !role.getRoleCode().isBlank())
                .map(SysRole::getRoleCode)
                .distinct()
                .toList();
    }

    /**
     * 企业成员列表；返回前补全当前管理员的默认角色/成员关系，保证列表口径完整。
     */
    @Transactional
```

### 改动：yunti-backend/yunti-user-service/src/main/java/cn/net/susan/user/controller/UserInternalController.java

改动点：内部接口：查用户在某租户下的角色编码

这个文件一共 3 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 3 行附近）**

原来是这样：

``` code-block-container
import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.user.entity.SysUser;
import cn.net.susan.user.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.constraints.NotBlank;
```

改成：

``` code-block-container
import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.user.entity.SysUser;
import cn.net.susan.user.mapper.SysUserMapper;
import cn.net.susan.user.service.MemberInviteService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.validation.constraints.NotBlank;
```

**修改 2（第 29 行附近）**

原来是这样：

``` code-block-container
public class UserInternalController {

    private final SysUserMapper sysUserMapper;

    public UserInternalController(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    /**
```

改成：

``` code-block-container
public class UserInternalController {

    private final SysUserMapper sysUserMapper;
    private final MemberInviteService memberInviteService;

    public UserInternalController(SysUserMapper sysUserMapper, MemberInviteService memberInviteService) {
        this.sysUserMapper = sysUserMapper;
        this.memberInviteService = memberInviteService;
    }

    /**
```

**新增 3（第 76 行附近）**

原来是这样：

``` code-block-container
    }

    /**
     * 请求体。
     */
    public record BackfillBody(
```

改成：

``` code-block-container
    }

    /**
     * 查询用户在某租户下的角色编码（内部接口：customer-service 判工单权限用）。
     *
     * <p>走内部接口而不是转发调用方的令牌：服务间调用不该依赖租户侧的登录态，
     * 也避免把用户令牌在多个服务之间传来传去。</p>
     */
    @GetMapping("/users/{userId}/roles")
    public ApiResponse<List<String>> userRoles(
            @PathVariable long userId,
            @RequestParam String tenantCode
    ) {
        return ApiResponse.ok(memberInviteService.roleCodesOf(tenantCode, userId));
    }

    /**
     * 请求体。
     */
    public record BackfillBody(
```

### 4.5 工单提醒：长连接推送

### 改动：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/internal/RealtimeNotifyClient.java

改动点：新增 notifyTicketAlert：工单分派与 SLA 预警都走它

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 52 行附近）**

原来是这样：

``` code-block-container
    }

    /**
     * 通知坐席已被自动分配会话；失败只记日志，不影响主流程。
     */
    public void notifyAssigned(String tenantCode, long agentId, String sessionNo, String reason) {
```

改成：

``` code-block-container
    }

    /**
     * 工单提醒：分派通知与 SLA 预警都走它。
     *
     * <p>发给谁：工单有处理人就发给处理人，没人认领（assigneeId 为空）就发给全租户的坐席连接——
     * 没人认领的工单超时了，光在列表里变红是没人看的。</p>
     */
    public void notifyTicketAlert(String tenantCode, Long assigneeId, Map<String, Object> payload) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("tenantCode", tenantCode);
            body.put("assigneeId", assigneeId);
            body.put("payload", payload);
            restClient.post()
                    .uri("/api/realtime/internal/ticket-alert")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // 提醒推不出去不影响工单本身：页面上还有角标和列表
            log.warn("推送工单提醒失败 tenant={} assigneeId={} error={}",
                    tenantCode, assigneeId, e.getMessage());
        }
    }

    /**
     * 通知坐席已被自动分配会话；失败只记日志，不影响主流程。
     */
    public void notifyAssigned(String tenantCode, long agentId, String sessionNo, String reason) {
```

### 改动：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/ws/RealtimeWebSocketHandler.java

改动点：工单提醒按"人"投递：有处理人只发给他，没人认领就发给全租户坐席连接

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 339 行附近）**

原来是这样：

``` code-block-container
    }

    /**
     * 通知某个坐席"这条会话分给你了"（智能路由自动接入时由 customer-service 调用）。
     *
     * <p>路由分配发生在 customer-service，不经过长连接，所以要反向通知一次，
```

改成：

``` code-block-container
    }

    /**
     * 工单提醒：分派通知与 SLA 预警都走它（由 customer-service 调用）。
     *
     * <p>工单不像会话那样有"订阅关系"，所以按人来发：有处理人就发给他；
     * 没人认领（assigneeId 为空）就发给全租户的坐席连接——没主的工单超时了，
     * 光在列表里变红是没人看的。</p>
     *
     * @return 实际送达的连接数
     */
    public int notifyTicketAlert(String tenantCode, Long assigneeId, Map<String, Object> payload) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return 0;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (payload != null) {
            data.putAll(payload);
        }
        RealtimeMessage message = RealtimeMessage.withData("TICKET_ALERT", "", data);
        int delivered = 0;
        for (ConnectionRegistry.Client client : registry.agentClients(tenantCode)) {
            if (client.principal().isVisitor()) {
                continue;
            }
            // 有处理人就只发给他；没人认领就发给全租户坐席——超时的工单不能被"没人负责"吞掉
            if (assigneeId != null && client.principal().id() != assigneeId) {
                continue;
            }
            send(client.socket(), message);
            delivered++;
        }
        log.info("推送工单提醒 tenant={} assigneeId={} 送达连接数={}",
                tenantCode, assigneeId == null ? "（未认领→全体坐席）" : assigneeId, delivered);
        return delivered;
    }

    /**
     * 通知某个坐席"这条会话分给你了"（智能路由自动接入时由 customer-service 调用）。
     *
     * <p>路由分配发生在 customer-service，不经过长连接，所以要反向通知一次，
```

### 改动：yunti-backend/yunti-realtime-service/src/main/java/cn/net/susan/realtime/controller/PresenceController.java

改动点：内部入口 /api/realtime/internal/ticket-alert

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 116 行附近）**

原来是这样：

``` code-block-container
    }

    /**
     * 内部接口：customer-service 落了一条"机器人回复 / 系统提示"后调用，让长连接广播出去。
     *
     * <p>机器人回复不是从前端长连接发起的，长连接手里没有这条消息；
```

改成：

``` code-block-container
    }

    /**
     * 内部接口：工单提醒（分派 / SLA 即将超时 / 已超时）。
     *
     * <p>和质检预警不同，工单没有"会话订阅关系"，所以按**人**投递：
     * 有处理人发给处理人，没人认领就发给全租户坐席。</p>
     */
    @PostMapping("/internal/ticket-alert")
    public ApiResponse<Map<String, Object>> ticketAlert(@RequestBody TicketAlertBody body) {
        int delivered = handler.notifyTicketAlert(body.tenantCode(), body.assigneeId(), body.payload());
        return ApiResponse.ok(Map.of("delivered", delivered));
    }

    /** 工单提醒请求体 */
    public record TicketAlertBody(String tenantCode, Long assigneeId, Map<String, Object> payload) {
    }

    /**
     * 内部接口：customer-service 落了一条"机器人回复 / 系统提示"后调用，让长连接广播出去。
     *
     * <p>机器人回复不是从前端长连接发起的，长连接手里没有这条消息；
```

### 4.6 消息中心（把第 4 篇设计的表用起来）

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/entity/Notification.java

消息实体：`target_type=2` 是发给整个企业的（SLA 预警），`target_type=1` 是发给某个人的（分给我的工单）。

``` code-block-container
package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * notification 消息表（消息中心组件的后端，第 4 篇设计里就留好了这张表）。
 *
 * <p>消息按"租户 + 类型 + 目标"存：`target_type=2` 是发给整个企业的（工单 SLA 预警这类），
 * `target_type=1` 是发给具体某个人的（分派给我的工单）；已读状态另存
 * {@link NotificationRead}，同一条消息每人各有一行。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("notification")
public class Notification {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 1-系统、2-工单、3-审核、4-质检、5-公告、6-账单 */
    private Integer notifyType;

    private String title;

    private String content;

    /** 点击后跳转的页面标识（前端自己映射到路由） */
    private String linkView;

    /** 1-用户、2-企业 */
    private Integer targetType;

    private Long targetId;

    private LocalDateTime publishTime;

    private LocalDateTime createTime;
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/entity/NotificationRead.java

已读实体：一条消息每人一行（`uk_tenant_notify_user` 唯一索引挡重复），已读是"我的"状态，不影响别人。

``` code-block-container
package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * notification_read 消息已读表：一条消息一个人一行（唯一索引 uk_tenant_notify_user 挡重复）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("notification_read")
public class NotificationRead {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long notifyId;

    private Long userId;

    private LocalDateTime readTime;

    private LocalDateTime createTime;
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/NotificationMapper.java

消息 Mapper。

``` code-block-container
package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.Notification;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/mapper/NotificationReadMapper.java

已读 Mapper。

``` code-block-container
package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.NotificationRead;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationReadMapper extends BaseMapper<NotificationRead> {
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/service/NotificationService.java

消息中心服务：发布（企业级 / 个人级）、列表（带"我读没读"）、未读数、标记已读、全部已读。三个约定：**消息存租户、已读存人**；**写消息失败只记日志**（工单该流转还得流转，不能因为提醒没写进去把主流程带崩）；跳转用页面标识（`link_view`），路由是前端的事。

``` code-block-container
package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.Notification;
import cn.net.susan.customer.entity.NotificationRead;
import cn.net.susan.customer.mapper.NotificationMapper;
import cn.net.susan.customer.mapper.NotificationReadMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 消息中心：把"系统 / 工单 / 审核 / 质检"各类提醒收进一个地方。
 *
 * <p>设计要点：</p>
 * <ol>
 *   <li><b>消息存租户、已读存人</b>：一条"3 个工单即将超时"是发给整个企业的，
 *       不该给每个坐席各存一份；谁读过谁写 {@code notification_read}（唯一索引挡重复）；</li>
 *   <li><b>写消息不许影响业务</b>：发布失败只记日志——工单该流转还得流转，
 *       不能因为"提醒没写进去"把主流程带崩；</li>
 *   <li><b>跳转用页面标识</b>：存 {@code link_view}（如 tickets / platform-support），
 *       路由是前端的事，后端不该知道 URL。</li>
 * </ol>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    /** 类型码：1-系统、2-工单、3-审核、4-质检、5-公告、6-账单 */
    public static final int TYPE_SYSTEM = 1;
    public static final int TYPE_TICKET = 2;
    public static final int TYPE_AUDIT = 3;
    public static final int TYPE_QA = 4;

    /** 目标：1-用户、2-企业 */
    private static final int TARGET_USER = 1;
    private static final int TARGET_TENANT = 2;

    private static final int LIST_LIMIT = 100;

    private final NotificationMapper notificationMapper;
    private final NotificationReadMapper notificationReadMapper;
    private final SnowflakeIdGenerator idGenerator;

    public NotificationService(NotificationMapper notificationMapper,
                               NotificationReadMapper notificationReadMapper,
                               SnowflakeIdGenerator idGenerator) {
        this.notificationMapper = notificationMapper;
        this.notificationReadMapper = notificationReadMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 发一条给整个企业的消息（工单 SLA 预警、平台回复这类）。
     */
    public void publishToTenant(String tenantCode, int notifyType, String title, String content, String linkView) {
        publish(tenantCode, notifyType, title, content, linkView, null);
    }

    /**
     * 发一条给具体某个人的消息（"这个工单分给你了"）。
     *
     * <p>userId 为空时退化成企业级消息：宁可发宽一点，也别让提醒丢掉。</p>
     */
    public void publishToUser(String tenantCode, long userId, int notifyType, String title, String content,
                              String linkView) {
        publish(tenantCode, notifyType, title, content, linkView, userId);
    }

    private void publish(String tenantCode, int notifyType, String title, String content, String linkView,
                         Long userId) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            notificationMapper.insert(Notification.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .notifyType(notifyType)
                    .title(limit(title, 128))
                    .content(content)
                    .linkView(linkView)
                    .targetType(userId == null ? TARGET_TENANT : TARGET_USER)
                    .targetId(userId)
                    .publishTime(now)
                    .createTime(now)
                    .build());
        } catch (Exception e) {
            // 提醒写不进去不影响工单流转本身，但要留痕（否则"为什么没收到提醒"没法查）
            log.warn("写消息中心失败 tenant={} type={} title={} error={}",
                    tenantCode, notifyType, title, e.getMessage());
        }
    }

    /** 消息列表：企业级 + 发给我的，带已读状态 */
    public List<NotificationVO> list(LoginUser user, Integer notifyType, Integer limit) {
        String tenant = tenantOf(user);
        int size = limit == null || limit < 1 ? 50 : Math.min(limit, LIST_LIMIT);
        List<Notification> rows = notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getTenantCode, tenant)
                .eq(notifyType != null, Notification::getNotifyType, notifyType)
                .and(w -> w.eq(Notification::getTargetType, TARGET_TENANT)
                        .or(x -> x.eq(Notification::getTargetType, TARGET_USER)
                                .eq(Notification::getTargetId, user.userId())))
                .orderByDesc(Notification::getPublishTime)
                .last("LIMIT " + size));
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(Notification::getId).toList();
        Set<Long> readIds = notificationReadMapper.selectList(Wrappers.<NotificationRead>lambdaQuery()
                        .eq(NotificationRead::getTenantCode, tenant)
                        .eq(NotificationRead::getUserId, user.userId())
                        .in(NotificationRead::getNotifyId, ids))
                .stream().map(NotificationRead::getNotifyId).collect(Collectors.toSet());
        List<NotificationVO> result = new ArrayList<>(rows.size());
        for (Notification row : rows) {
            result.add(new NotificationVO(
                    String.valueOf(row.getId()),
                    row.getNotifyType(),
                    notifyTypeText(row.getNotifyType()),
                    row.getTitle(),
                    row.getContent(),
                    row.getLinkView(),
                    row.getPublishTime() == null ? null
                            : row.getPublishTime().toString().replace('T', ' ').substring(0, 19),
                    readIds.contains(row.getId())));
        }
        return result;
    }

    /** 未读数：前端右上角红点用 */
    public int unreadCount(LoginUser user) {
        String tenant = tenantOf(user);
        List<Notification> rows = notificationMapper.selectList(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getTenantCode, tenant)
                .and(w -> w.eq(Notification::getTargetType, TARGET_TENANT)
                        .or(x -> x.eq(Notification::getTargetType, TARGET_USER)
                                .eq(Notification::getTargetId, user.userId())))
                .orderByDesc(Notification::getPublishTime)
                .last("LIMIT " + LIST_LIMIT));
        if (rows.isEmpty()) {
            return 0;
        }
        Set<Long> readIds = notificationReadMapper.selectList(Wrappers.<NotificationRead>lambdaQuery()
                        .eq(NotificationRead::getTenantCode, tenant)
                        .eq(NotificationRead::getUserId, user.userId()))
                .stream().map(NotificationRead::getNotifyId).collect(Collectors.toSet());
        return (int) rows.stream().filter(row -> !readIds.contains(row.getId())).count();
    }

    /** 标记一条已读（重复标记不报错） */
    public void markRead(LoginUser user, String notifyId) {
        String tenant = tenantOf(user);
        long id;
        try {
            id = Long.parseLong(notifyId);
        } catch (NumberFormatException e) {
            throw new BizException(40001, "消息 ID 不合法");
        }
        Long exists = notificationReadMapper.selectCount(Wrappers.<NotificationRead>lambdaQuery()
                .eq(NotificationRead::getTenantCode, tenant)
                .eq(NotificationRead::getNotifyId, id)
                .eq(NotificationRead::getUserId, user.userId()));
        if (exists != null && exists > 0) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        notificationReadMapper.insert(NotificationRead.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .notifyId(id)
                .userId(user.userId())
                .readTime(now)
                .createTime(now)
                .build());
    }

    /** 全部标记已读：把当前可见的未读一次性写进已读表 */
    public int markAllRead(LoginUser user, Integer notifyType) {
        String tenant = tenantOf(user);
        List<Notification> rows = list(user, notifyType, LIST_LIMIT).stream()
                .filter(item -> !item.read())
                .map(item -> Notification.builder().id(Long.parseLong(item.id())).build())
                .toList();
        int count = 0;
        for (Notification row : rows) {
            markRead(user, String.valueOf(row.getId()));
            count++;
        }
        return count;
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2) {
            throw new BizException(40301, "仅企业成员可使用消息中心");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用消息中心");
        }
        return tenant;
    }

    private static String limit(String text, int max) {
        if (text == null) {
            return "-";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    public static String notifyTypeText(Integer type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case TYPE_SYSTEM -> "系统通知";
            case TYPE_TICKET -> "工单提醒";
            case TYPE_AUDIT -> "审核通知";
            case TYPE_QA -> "质检通知";
            case 5 -> "公告";
            default -> "账单通知";
        };
    }

    public record NotificationVO(String id, Integer notifyType, String notifyTypeText, String title,
                                 String content, String linkView, String publishTime, boolean read) {
    }

    /** 前端 tab 用：类型码 → 文案（避免两边各写一份） */
    public Map<String, String> typeTexts() {
        Map<String, String> map = new HashMap<>();
        map.put("1", "系统通知");
        map.put("2", "工单提醒");
        map.put("3", "审核通知");
        map.put("4", "质检通知");
        return map;
    }
}
```

### 文件：yunti-backend/yunti-customer-service/src/main/java/cn/net/susan/customer/controller/NotificationController.java

消息中心接口：列表 / 未读数 / 标记已读 / 全部已读。

``` code-block-container
package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.customer.security.JwtTokenParser;
import cn.net.susan.customer.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 消息中心：右上角铃铛点开的那一栏。
 *
 * <p>企业级消息（工单 SLA 预警）+ 发给我的消息（分派给我的工单）一起返回，
 * 每条带"我读没读"；已读是写在 notification_read 里，不影响别人。</p>
 */
@RestController
@RequestMapping("/api/customer/notifications")
public class NotificationController {

    private final NotificationService notificationService;
    private final JwtTokenParser jwtTokenParser;

    public NotificationController(NotificationService notificationService,
                                  JwtTokenParser jwtTokenParser) {
        this.notificationService = notificationService;
        this.jwtTokenParser = jwtTokenParser;
    }

    @GetMapping
    public ApiResponse<List<NotificationService.NotificationVO>> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer notifyType,
            @RequestParam(required = false) Integer limit
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(notificationService.list(user, notifyType, limit));
    }

    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(Map.of("count", notificationService.unreadCount(user)));
    }

    @PostMapping("/{notifyId}/read")
    public ApiResponse<Void> markRead(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String notifyId
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        notificationService.markRead(user, notifyId);
        return ApiResponse.ok();
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) Integer notifyType
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(Map.of("count", notificationService.markAllRead(user, notifyType)));
    }
}
```

### 4.7 网关放行新前缀

### 改动：yunti-backend/yunti-gateway/src/main/resources/application.yml

改动点：新增 /api/platform/\*\* 路由（转给 customer-service）

这一条是踩出来的：平台接口挂在 `/api/platform/**`，网关没有这条路由 → 线上 404；前端 dev 代理没配 → 本地返回 index.html（第七章第 2 条）。

这个文件一共 1 处改动，按下面的「原来 → 改成」逐处调整即可（行号是参考值，改动后会往下漂）：

**新增 1（第 23 行附近）**

原来是这样：

``` code-block-container
              uri: ${YUNTI_TENANT_SERVICE_URL:http://127.0.0.1:9092}
              predicates:
                - Path=/api/tenant/**
            - id: yunti-customer-service
              uri: ${YUNTI_CUSTOMER_SERVICE_URL:http://127.0.0.1:9093}
              predicates:
```

改成：

``` code-block-container
              uri: ${YUNTI_TENANT_SERVICE_URL:http://127.0.0.1:9092}
              predicates:
                - Path=/api/tenant/**
            # 平台侧接口（目前是"支持工单"）：也是 customer-service 提供的，
            # 但按"平台视角"单独开一条前缀，方便以后给平台接口统一加鉴权/限流
            - id: yunti-platform-api
              uri: ${YUNTI_CUSTOMER_SERVICE_URL:http://127.0.0.1:9093}
              predicates:
                - Path=/api/platform/**
            - id: yunti-customer-service
              uri: ${YUNTI_CUSTOMER_SERVICE_URL:http://127.0.0.1:9093}
              predicates:
```

## 五、前端：工单中心与平台支持工单

### 5.1 三个接口文件

### 文件：yunti-frontend/src/api/customer/ticket.ts

企业侧工单接口与类型。两个细节：① 编号一律用**字符串**（工单号、用户 ID），雪花 ID 用 number 接会丢精度；② 导出走**原始 axios 实例**而不是统一的 `request()`——CSV 不是 `{code,data}` 结构，统一解包会把它当成业务失败。

``` code-block-container
import { default as service, request } from '../request'

/**
 * 工单中心接口。
 *
 * <p>编号一律用字符串：工单号是 TK 开头的字符串，用户 ID 是雪花 ID（19 位数字），
 * 用 number 接会在前端丢精度——第 19 篇踩过这个坑，这里从一开始就按字符串走。</p>
 */

export interface TicketItem {
  /** 租户号（平台工单跨租户展示时要用） */
  tenantCode?: string | null
  /** 1-企业内部工单、2-平台支持工单 */
  ticketType?: number | null
  ticketTypeText?: string | null
  ticketNo: string
  title: string
  /** 来源渠道（1-在线会话、2-电话热线、3-邮件、4-工单导入、5-其它） */
  sourceChannel?: number | null
  sourceChannelText?: string | null
  category: number
  categoryText: string
  priority: number
  priorityText: string
  status: number
  statusText: string
  source: number
  sourceText: string
  sessionNo?: string | null
  customerName?: string | null
  assigneeId?: string | null
  assigneeName?: string | null
  /** 1-正常、2-即将超时、3-已超时 */
  slaState: number
  slaStateText: string
  /** 距离下一个截止时间还有多少分钟，负数表示已经超了 */
  remainMinutes?: number | null
  firstResponded: boolean
  firstResponseDue?: string | null
  resolveDue?: string | null
  createTime?: string | null
  creatorName?: string | null
  /** 已解决时的"耗时 x 分钟" */
  finishText?: string | null
}

export interface TicketEventItem {
  id: string
  eventType: number
  eventTypeText: string
  operatorName?: string | null
  content?: string | null
  fromStatusText?: string | null
  toStatusText?: string | null
  visibleToCustomer: boolean
  eventTime?: string | null
}

export interface TicketSla {
  firstResponseMinutes: number
  resolveMinutes: number
}

export interface TicketDetail {
  ticket: TicketItem
  content?: string | null
  events: TicketEventItem[]
  sla: TicketSla
  /** 租户号：工单号只在租户内唯一，报给平台排查时要一起给 */
  tenantCode?: string | null
}

export interface TicketOverview {
  pending: number
  processing: number
  confirming: number
  overdue: number
  warning: number
  mine: number
  resolvedToday: number
}

export interface TicketSlaRule {
  priority: number
  priorityText: string
  firstResponseMinutes: number
  resolveMinutes: number
  enabled: boolean
}

export interface TicketQuery {
  status?: number
  priority?: number
  category?: number
  slaState?: number
  assigneeId?: string
  mineOnly?: boolean
  unassignedOnly?: boolean
  sessionNo?: string
  keyword?: string
  /** 工单类型：1-企业内部（默认）、2-平台支持 */
  ticketType?: number
  /** 分页：第几页（从 1 开始）与每页条数 */
  page?: number
  pageSize?: number
}

export interface TicketPage {
  total: number
  page: number
  pageSize: number
  list: TicketItem[]
}

/** 当前登录人在工单里的权限（后端算好下发，前端只负责显隐按钮） */
export interface TicketAccess {
  roleCode?: string | null
  roleName: string
  /** 能不能建单与处理（质检专员 / AI运营这类只读角色为 false） */
  canOperate: boolean
  /** 能不能改 SLA 规则、手动扫超时（仅企业管理员） */
  canManageSla: boolean
  hint?: string | null
}

export interface CreateTicketBody {
  title?: string
  content?: string
  category?: number
  priority?: number
  /** 1-会话转单、2-客户自助、3-坐席新建（带 sessionNo 时后端会按会话转单处理） */
  source?: number
  sessionNo?: string
  assigneeId?: string
  assigneeName?: string
}

export function fetchTicketOverview(): Promise<TicketOverview> {
  return request<TicketOverview>({ url: '/customer/tickets/overview', method: 'get' })
}

export function fetchTicketAccess(): Promise<TicketAccess> {
  return request<TicketAccess>({ url: '/customer/tickets/access', method: 'get' })
}

export function fetchTickets(query: TicketQuery = {}): Promise<TicketPage> {
  return request<TicketPage>({ url: '/customer/tickets', method: 'get', params: query })
}

export function fetchTicketDetail(ticketNo: string): Promise<TicketDetail> {
  return request<TicketDetail>({ url: `/customer/tickets/${ticketNo}`, method: 'get' })
}

export function createTicket(body: CreateTicketBody): Promise<TicketItem> {
  return request<TicketItem>({ url: '/customer/tickets', method: 'post', data: body })
}

/** 提交给平台支持（渠道接入失败、计费异常这类平台才能处理的问题） */
export function createPlatformTicket(body: CreateTicketBody): Promise<TicketItem> {
  return request<TicketItem>({ url: '/customer/tickets/platform', method: 'post', data: body })
}

/** 在平台支持工单下补充说明（不算平台首次响应，也不改状态） */
export function supplementTicket(ticketNo: string, content: string): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/customer/tickets/${ticketNo}/supplement`,
    method: 'post',
    data: { content, visibleToCustomer: true },
  })
}

/** 升级工单：优先级提一档（不重算 SLA 截止时间），并通知处理人与主管 */
export function escalateTicket(
  ticketNo: string,
  body: { remark?: string } = {},
): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/customer/tickets/${ticketNo}/escalate`,
    method: 'post',
    data: body,
  })
}

/** 分派 / 转派；toUserId 不传表示认领给自己 */
export function assignTicket(
  ticketNo: string,
  body: { toUserId?: string; toUserName?: string; remark?: string } = {},
): Promise<TicketDetail> {
  return request<TicketDetail>({ url: `/customer/tickets/${ticketNo}/assign`, method: 'post', data: body })
}

export function replyTicket(
  ticketNo: string,
  body: { content: string; visibleToCustomer?: boolean },
): Promise<TicketDetail> {
  return request<TicketDetail>({ url: `/customer/tickets/${ticketNo}/reply`, method: 'post', data: body })
}

export function changeTicketStatus(
  ticketNo: string,
  body: { status: number; remark?: string },
): Promise<TicketDetail> {
  return request<TicketDetail>({ url: `/customer/tickets/${ticketNo}/status`, method: 'post', data: body })
}

/** 手动触发一次 SLA 扫描（页面上的"检查超时"） */
export function scanTicketSla(): Promise<{ scanned: number; alerted: number; overdue: number }> {
  return request({ url: '/customer/tickets/scan-sla', method: 'post' })
}

export function fetchTicketSlaRules(): Promise<TicketSlaRule[]> {
  return request<TicketSlaRule[]>({ url: '/customer/tickets/sla-rules', method: 'get' })
}

export function saveTicketSlaRules(
  rules: Array<{ priority: number; firstResponseMinutes: number; resolveMinutes: number; enabled: boolean }>,
): Promise<TicketSlaRule[]> {
  return request<TicketSlaRule[]>({ url: '/customer/tickets/sla-rules', method: 'put', data: rules })
}

/**
 * 导出工单 CSV（按当前筛选）。
 *
 * <p>走原始 axios 实例而不是 `request()`：CSV 不是 `{code,data}` 结构，
 * 统一解包会把它当成业务失败。这里直接把响应当 blob 交给浏览器下载。</p>
 */
export async function downloadTicketsCsv(query: TicketQuery, fileName = '工单列表.csv') {
  const response = await service.get('/customer/tickets/export', {
    params: query,
    responseType: 'blob',
  })
  saveBlob(response.data as Blob, fileName)
}

/** 触发浏览器下载（BOM 由后端写入，Excel 打开中文不乱码） */
export function saveBlob(blob: Blob, fileName: string) {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(url)
}
```

### 文件：yunti-frontend/src/api/customer/notification.ts

消息中心接口：列表、未读数、标记已读、全部已读。

``` code-block-container
import { request } from '../request'

/**
 * 消息中心（后端）。
 *
 * <p>消息按"租户 + 类型"存，已读按人存：工单 SLA 预警、被分派的工单、
 * 平台回复企业这些都会写成 notification，右上角铃铛读的就是它。</p>
 */

export interface NotificationItem {
  id: string
  /** 1-系统、2-工单、3-审核、4-质检、5-公告、6-账单 */
  notifyType: number
  notifyTypeText: string
  title: string
  content?: string | null
  /** 点击后跳转的页面标识（tickets / platform-support …） */
  linkView?: string | null
  publishTime?: string | null
  read: boolean
}

export function fetchNotifications(params: { notifyType?: number; limit?: number } = {}) {
  return request<NotificationItem[]>({ url: '/customer/notifications', method: 'get', params })
}

export function fetchNotificationUnreadCount(): Promise<{ count: number }> {
  return request<{ count: number }>({ url: '/customer/notifications/unread-count', method: 'get' })
}

export function markNotificationRead(notifyId: string): Promise<void> {
  return request<void>({ url: `/customer/notifications/${notifyId}/read`, method: 'post' })
}

export function markAllNotificationsRead(notifyType?: number): Promise<{ count: number }> {
  return request<{ count: number }>({
    url: '/customer/notifications/read-all',
    method: 'post',
    params: { notifyType },
  })
}
```

### 文件：yunti-frontend/src/api/platform/ticket.ts

平台侧支持工单接口。每条都要显式带 `tenant`（工单号只在租户内唯一），这也是它和企业侧接口分开一个文件的原因。

``` code-block-container
import { default as service, request } from '../request'
import type { TicketDetail, TicketItem, TicketPage } from '../customer/ticket'
import { saveBlob } from '../customer/ticket'

/**
 * 平台侧支持工单接口（跨租户）。
 *
 * <p>和 `/customer/tickets` 分开：那边是企业租户内的工单（租户从登录态取），
 * 这边是平台账号的跨租户视角，每条都要显式带 `tenant`（工单号只在租户内唯一）。</p>
 */

export interface PlatformTicketOverview {
  pending: number
  processing: number
  confirming: number
  overdue: number
  warning: number
  mine: number
  resolvedToday: number
}

export interface PlatformTicketQuery {
  status?: number
  priority?: number
  slaState?: number
  /** 租户号（支持前缀模糊） */
  tenant?: string
  keyword?: string
  mineOnly?: boolean
  unassignedOnly?: boolean
  page?: number
  pageSize?: number
}

export function fetchPlatformTicketOverview(): Promise<PlatformTicketOverview> {
  return request<PlatformTicketOverview>({ url: '/platform/tickets/overview', method: 'get' })
}

export function fetchPlatformTickets(query: PlatformTicketQuery = {}): Promise<TicketPage> {
  return request<TicketPage>({ url: '/platform/tickets', method: 'get', params: query })
}

export function fetchPlatformTicketDetail(ticketNo: string, tenant: string): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/platform/tickets/${ticketNo}`,
    method: 'get',
    params: { tenant },
  })
}

/** 平台认领（把处理人设成自己） */
export function claimPlatformTicket(ticketNo: string, tenant: string): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/platform/tickets/${ticketNo}/claim`,
    method: 'post',
    params: { tenant },
  })
}

export function replyPlatformTicket(
  ticketNo: string,
  tenant: string,
  body: { content: string; visibleToCustomer?: boolean },
): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/platform/tickets/${ticketNo}/reply`,
    method: 'post',
    params: { tenant },
    data: body,
  })
}

export function changePlatformTicketStatus(
  ticketNo: string,
  tenant: string,
  body: { status: number; remark?: string },
): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/platform/tickets/${ticketNo}/status`,
    method: 'post',
    params: { tenant },
    data: body,
  })
}

/** 平台侧升级（一线处理不动就提优先级，并通知企业） */
export function escalatePlatformTicket(
  ticketNo: string,
  tenant: string,
  body: { remark?: string } = {},
): Promise<TicketDetail> {
  return request<TicketDetail>({
    url: `/platform/tickets/${ticketNo}/escalate`,
    method: 'post',
    params: { tenant },
    data: body,
  })
}

/** 平台侧导出 CSV（跨租户，带租户号） */
export async function downloadPlatformTicketsCsv(
  query: PlatformTicketQuery,
  fileName = '支持工单.csv',
) {
  const response = await service.get('/platform/tickets/export', {
    params: query,
    responseType: 'blob',
  })
  saveBlob(response.data as Blob, fileName)
}

export type { TicketDetail, TicketItem, TicketPage }
```

###
