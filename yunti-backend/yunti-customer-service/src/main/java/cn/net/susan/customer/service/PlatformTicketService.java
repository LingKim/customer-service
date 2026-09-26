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
