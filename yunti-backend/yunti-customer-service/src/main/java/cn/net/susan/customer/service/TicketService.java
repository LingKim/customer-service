package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.Ticket;
import cn.net.susan.customer.entity.TicketEvent;
import cn.net.susan.customer.entity.TicketSlaRule;
import cn.net.susan.customer.internal.RealtimeNotifyClient;
import cn.net.susan.customer.internal.UserRoleClient;
import cn.net.susan.customer.mapper.TicketEventMapper;
import cn.net.susan.customer.mapper.TicketMapper;
import cn.net.susan.customer.mapper.TicketSlaRuleMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工单中心：会话转工单 → 分派/认领 → 回复 → 解决 → 关闭，外加两段 SLA 计时。
 *
 * <p>三个设计要点，写在最前面：</p>
 *
 * <ol>
 *   <li><b>工单是"客户的问题"而不是"一张记录表"</b>：所以必须能回到来源会话看上下文，
 *       转单时也把最近这段对话的摘要带进描述里——坐席不用再手工复制一遍；</li>
 *   <li><b>SLA 分两段</b>：首次响应（客户等多久有人理）与解决（多久真正处理完）。
 *       只卡一个总时限的话，"回了句'正在处理'然后一直不动"和"压根没人理"会被算成同一件事；
 *       规则按**优先级**配，建单时**快照**进工单——规则后来改了，不影响存量工单的口径；</li>
 *   <li><b>超时不是"页面上变个颜色"就完了</b>：定时扫描把状态改成"即将超时/已超时"，
 *       写一条流转记录，并推一条提醒给处理人（没人认领就推给全租户坐席）——客户催之前，
 *       系统先催一遍坐席。</li>
 * </ol>
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);

    /* ---------------- 状态 ---------------- */
    public static final int STATUS_PENDING = 1;
    public static final int STATUS_PROCESSING = 2;
    public static final int STATUS_CONFIRMING = 3;
    public static final int STATUS_RESOLVED = 4;
    public static final int STATUS_CLOSED = 5;

    /* ---------------- 优先级 ---------------- */
    public static final int PRIORITY_LOW = 1;
    public static final int PRIORITY_NORMAL = 2;
    public static final int PRIORITY_HIGH = 3;
    public static final int PRIORITY_URGENT = 4;

    /* ---------------- 来源 ---------------- */
    public static final int SOURCE_SESSION = 1;
    public static final int SOURCE_SELF = 2;
    public static final int SOURCE_AGENT = 3;

    /* ---------------- 工单类型 ---------------- */
    /** 企业内部工单：租户内部闭环 */
    public static final int TICKET_TYPE_INTERNAL = 1;
    /** 平台支持工单：企业提给平台，平台运营/平台管理员处理 */
    public static final int TICKET_TYPE_PLATFORM = 2;

    /* ---------------- SLA 状态 ---------------- */
    public static final int SLA_NORMAL = 1;
    public static final int SLA_WARNING = 2;
    public static final int SLA_OVERDUE = 3;

    /* ---------------- 流转事件 ---------------- */
    public static final int EVENT_CREATE = 1;
    public static final int EVENT_ASSIGN = 2;
    public static final int EVENT_REPLY = 3;
    public static final int EVENT_ESCALATE = 4;
    public static final int EVENT_CLOSE = 5;
    public static final int EVENT_REOPEN = 6;
    public static final int EVENT_STATUS = 7;
    public static final int EVENT_SLA_ALERT = 8;

    /** 租户没配规则时的兜底（分钟）：[首次响应, 解决]，下标 = 优先级 - 1 */
    private static final int[][] DEFAULT_SLA_MINUTES = {
            {240, 2880},   // 低：4 小时响应、2 天解决
            {60, 480},     // 中：1 小时响应、8 小时解决
            {30, 240},     // 高：30 分钟响应、4 小时解决
            {15, 120},     // 紧急：15 分钟响应、2 小时解决
    };

    /** 剩余时间不足这个比例就算"即将超时" */
    private static final double WARNING_RATIO = 0.2;
    /** 预警下限（分钟）：紧急工单 20% 才 3 分钟，太晚了，这里给个兜底 */
    private static final int WARNING_FLOOR_MINUTES = 15;

    /** 列表默认每页条数 / 单页上限（前端可以选 10 / 20 / 50） */
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    /** 导出上限：一次最多 5000 条，够运营用了；再多请分月导，别把内存拉爆 */
    private static final int EXPORT_LIMIT = 5000;
    private static final int EVENT_LIMIT = 200;
    private static final int SCAN_LIMIT = 500;
    /** 会话转单时带多少条最近消息进工单描述 */
    private static final int TRANSCRIPT_LIMIT = 12;

    /**
     * 只读角色：能看工单，但不参与处理。
     *
     * <p>质检专员看工单是为了复盘（哪些问题最后靠人工跟到底），AI 运营看工单是为了调机器人策略——
     * 他们不需要抢单、也不需要回复客户，放开操作权限只会让"谁在处理"变模糊。</p>
     */
    private static final Set<String> READ_ONLY_ROLES = Set.of("QUALITY", "AI_OPERATOR");
    /** 管理员角色：SLA 规则、强制关闭这类"配置与兜底"动作只给它 */
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN");
    private static final Map<String, String> ROLE_NAMES = Map.of(
            "ADMIN", "企业管理员",
            "SUPERVISOR", "客服主管",
            "SENIOR_AGENT", "高级客服",
            "AGENT", "客服专员",
            "QUALITY", "质检专员",
            "AI_OPERATOR", "AI运营");

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final TicketMapper ticketMapper;
    private final TicketEventMapper ticketEventMapper;
    private final TicketSlaRuleMapper slaRuleMapper;
    private final SessionService sessionService;
    private final RealtimeNotifyClient notifyClient;
    private final UserRoleClient userRoleClient;
    private final NotificationService notificationService;
    private final SnowflakeIdGenerator idGenerator;

    public TicketService(TicketMapper ticketMapper,
                         TicketEventMapper ticketEventMapper,
                         TicketSlaRuleMapper slaRuleMapper,
                         SessionService sessionService,
                         RealtimeNotifyClient notifyClient,
                         UserRoleClient userRoleClient,
                         NotificationService notificationService,
                         SnowflakeIdGenerator idGenerator) {
        this.ticketMapper = ticketMapper;
        this.ticketEventMapper = ticketEventMapper;
        this.slaRuleMapper = slaRuleMapper;
        this.sessionService = sessionService;
        this.notifyClient = notifyClient;
        this.userRoleClient = userRoleClient;
        this.notificationService = notificationService;
        this.idGenerator = idGenerator;
    }

    // ------------------------------------------------------------------ 查询

    /**
     * 工单列表。
     *
     * <p>排序：**按创建时间倒序**（最新的在最上面）。
     * 超时/优先级不参与排序，但仍然是醒目的——超时行会标红、菜单上有超时角标，
     * 需要按紧急程度看的时候用「优先级 / SLA」筛选即可。</p>
     */
    public TicketPageVO list(LoginUser user, TicketQuery query, Integer page, Integer pageSize) {
        String tenant = tenantOf(user);
        int current = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        return queryPage(tenant, query, user, current, size);
    }

    /**
     * 导出 CSV（按当前筛选，最多 5000 条）：运营/客服把工单拉出来对账、贴进周报都用得上。
     *
     * <p>带 UTF-8 BOM——不加的话 Excel 打开中文是乱码，这是导出功能最常见的"看起来能用其实不能用"。</p>
     */
    public String exportCsv(LoginUser user, TicketQuery query) {
        String tenant = tenantOf(user);
        TicketPageVO page = queryPage(tenant, query, user, 1, EXPORT_LIMIT);
        StringBuilder csv = new StringBuilder("\ufeff");
        csv.append("工单号,类型,主题,来源渠道,分类,优先级,状态,SLA,剩余/超时(分钟),处理人,客户,创建人,创建时间\n");
        for (TicketVO item : page.list()) {
            csv.append(csv(item.ticketNo())).append(',')
                    .append(csv(item.ticketTypeText())).append(',')
                    .append(csv(item.title())).append(',')
                    .append(csv(item.sourceChannelText())).append(',')
                    .append(csv(item.categoryText())).append(',')
                    .append(csv(item.priorityText())).append(',')
                    .append(csv(item.statusText())).append(',')
                    .append(csv(item.slaStateText())).append(',')
                    .append(item.remainMinutes() == null ? "" : item.remainMinutes()).append(',')
                    .append(csv(item.assigneeName())).append(',')
                    .append(csv(item.customerName())).append(',')
                    .append(csv(item.creatorName())).append(',')
                    .append(csv(item.createTime())).append('\n');
        }
        return csv.toString();
    }

    /** CSV 单元格转义：逗号/引号/换行都要包起来 */
    private static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value.replace("\"", "\"\"");
        return text.contains(",") || text.contains("\"") || text.contains("\n")
                ? "\"" + text + "\"" : text;
    }

    /** 分页查询的实际实现（导出走同一个方法，只是 size 上限不一样） */
    private TicketPageVO queryPage(String tenant, TicketQuery query, LoginUser user, int current, int size) {
        TicketQuery q = query == null
                ? new TicketQuery(null, null, null, null, null, null, null, null, null, null) : query;
        LocalDateTime now = LocalDateTime.now();
        // 先数总数（此时条件里不能带 order / limit），再取当前页
        Long total = ticketMapper.selectCount(conditions(tenant, q, user));
        long totalValue = total == null ? 0 : total;
        var wrapper = conditions(tenant, q, user);
        // 排序与分页片段都只含常量与已校验的整数，不会有注入面
        wrapper.last("ORDER BY create_time DESC"
                + " LIMIT " + size + " OFFSET " + (long) (current - 1) * size);
        List<Ticket> rows = ticketMapper.selectList(wrapper);
        List<TicketVO> items = new ArrayList<>(rows.size());
        for (Ticket row : rows) {
            items.add(toVO(row, now));
        }
        return new TicketPageVO(totalValue, current, size, items);
    }

    /** 列表的查询条件（数总数与取分页各用一份，互不干扰）。 */
    private LambdaQueryWrapper<Ticket> conditions(String tenant, TicketQuery q, LoginUser user) {
        var wrapper = Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getTenantCode, tenant)
                .eq(Ticket::getDeleted, false);
        // 工单类型：企业侧默认只看"企业内部工单"；点「提交给平台」那个页签才看平台支持工单
        wrapper.eq(Ticket::getTicketType,
                q.ticketType() == null ? TICKET_TYPE_INTERNAL : q.ticketType());
        if (q.status() != null) {
            wrapper.eq(Ticket::getStatus, q.status());
        }
        if (q.priority() != null) {
            wrapper.eq(Ticket::getPriority, q.priority());
        }
        if (q.category() != null) {
            wrapper.eq(Ticket::getCategory, q.category());
        }
        if (q.slaState() != null) {
            wrapper.eq(Ticket::getSlaState, q.slaState());
        }
        if (q.assigneeId() != null) {
            wrapper.eq(Ticket::getAssigneeId, q.assigneeId());
        }
        if (Boolean.TRUE.equals(q.mineOnly())) {
            wrapper.eq(Ticket::getAssigneeId, user.userId());
        }
        if (Boolean.TRUE.equals(q.unassignedOnly())) {
            wrapper.isNull(Ticket::getAssigneeId);
        }
        if (q.sessionNo() != null && !q.sessionNo().isBlank()) {
            wrapper.eq(Ticket::getSessionNo, q.sessionNo().trim());
        }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            String keyword = q.keyword().trim();
            wrapper.and(w -> w.like(Ticket::getTitle, keyword)
                    .or().like(Ticket::getTicketNo, keyword)
                    .or().like(Ticket::getDesc, keyword)
                    .or().like(Ticket::getCustomerName, keyword));
        }
        return wrapper;
    }

    /** 工单详情：基本信息 + SLA + 完整流转时间线（含回复与内部备注）。 */
    public TicketDetailVO detail(LoginUser user, String ticketNo) {
        String tenant = tenantOf(user);
        Ticket ticket = requireTicket(tenant, ticketNo);
        LocalDateTime now = LocalDateTime.now();
        List<TicketEvent> events = ticketEventMapper.selectList(Wrappers.<TicketEvent>lambdaQuery()
                .eq(TicketEvent::getTenantCode, tenant)
                .eq(TicketEvent::getTicketId, ticket.getId())
                .orderByAsc(TicketEvent::getEventTime)
                .last("LIMIT " + EVENT_LIMIT));
        List<EventVO> timeline = new ArrayList<>(events.size());
        for (TicketEvent event : events) {
            timeline.add(toEventVO(event));
        }
        int[] sla = resolveSla(tenant, ticket.getPriority());
        SlaVO slaVO = new SlaVO(ticket.getSlaFirstMinutes() == null || ticket.getSlaFirstMinutes() <= 0
                ? sla[0] : ticket.getSlaFirstMinutes(),
                ticket.getSlaResolveMinutes() == null || ticket.getSlaResolveMinutes() <= 0
                        ? sla[1] : ticket.getSlaResolveMinutes());
        // 详情里带上租户号：列表不用显示（租户在登录态里是隐含的，每个页面都不显示），
        // 但工单号只在租户内唯一——坐席把工单号报给平台排查时，得能一眼给出租户
        return new TicketDetailVO(toVO(ticket, now), ticket.getDesc(), timeline, slaVO, tenant);
    }

    /** 工单看板数字：待处理 / 处理中 / 待确认 / 今日解决 / 超时 / 即将超时 / 我处理的。 */
    public OverviewVO overview(LoginUser user) {
        String tenant = tenantOf(user);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
        List<Ticket> open = ticketMapper.selectList(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getTenantCode, tenant)
                .eq(Ticket::getDeleted, false)
                .in(Ticket::getStatus, STATUS_PENDING, STATUS_PROCESSING, STATUS_CONFIRMING));
        int pending = 0;
        int processing = 0;
        int confirming = 0;
        int overdue = 0;
        int warning = 0;
        int mine = 0;
        for (Ticket ticket : open) {
            int status = ticket.getStatus() == null ? STATUS_PENDING : ticket.getStatus();
            if (status == STATUS_PENDING) {
                pending++;
            } else if (status == STATUS_PROCESSING) {
                processing++;
            } else {
                confirming++;
            }
            int state = computeSlaState(ticket, now);
            if (state == SLA_OVERDUE) {
                overdue++;
            } else if (state == SLA_WARNING) {
                warning++;
            }
            if (user.userId() == (ticket.getAssigneeId() == null ? -1L : ticket.getAssigneeId())) {
                mine++;
            }
        }
        Long resolvedToday = ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getTenantCode, tenant)
                .eq(Ticket::getDeleted, false)
                .in(Ticket::getStatus, STATUS_RESOLVED, STATUS_CLOSED)
                .ge(Ticket::getResolvedAt, todayStart));
        return new OverviewVO(pending, processing, confirming, overdue, warning, mine,
                resolvedToday == null ? 0 : resolvedToday.intValue());
    }

    // ------------------------------------------------------------------ 建单

    /**
     * 新建工单：坐席手工建，或者从会话转过来。
     *
     * <p>会话转单时自动带上"来源会话 + 客户 + 最近 12 条对话摘要"——这是工单能不能闭环的起点：
     * 接单的人第一眼就该知道客户到底遇到了什么，而不是再去问一遍。</p>
     */
    @Transactional
    public TicketVO create(LoginUser user, CreateBody body) {
        requireOperate(user);
        String tenant = tenantOf(user);
        SessionService.SessionVO session = null;
        Long sessionId = null;
        String sessionNo = body.sessionNo() == null ? null : body.sessionNo().trim();
        if (sessionNo != null && !sessionNo.isBlank()) {
            session = sessionService.sessionDetail(user, sessionNo);
            sessionId = sessionService.requireSession(tenant, sessionNo).getId();
        }
        int priority = normalizePriority(body.priority());
        int category = normalizeCategory(body.category());
        int source = session != null ? SOURCE_SESSION : normalizeSource(body.source());
        int[] sla = resolveSla(tenant, priority);

        LocalDateTime now = LocalDateTime.now();
        Ticket ticket = Ticket.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .ticketNo(nextTicketNo(tenant))
                .ticketType(TICKET_TYPE_INTERNAL)
                .category(category)
                .title(titleOf(body, session))
                .customerId(session == null ? null : session.customerId())
                // 客户名称：会话转单自动带，手建由坐席填（原型里是必填项）
                .customerName(session == null ? blankToNull(body.customerName()) : session.customerName())
                .desc(describe(tenant, body, session, sessionNo))
                .priority(priority)
                .status(STATUS_PENDING)
                .source(source)
                // 来源渠道：会话转单就是"在线会话"，手建由坐席选
                .sourceChannel(session == null ? normalizeSourceChannel(body.sourceChannel()) : 1)
                .sessionNo(session == null ? null : session.sessionNo())
                .sourceSessionId(sessionId)
                .assigneeId(body.assigneeId())
                .assigneeName(blankToNull(body.assigneeName()))
                .slaFirstMinutes(sla[0])
                .slaResolveMinutes(sla[1])
                .firstResponseDue(now.plusMinutes(sla[0]))
                .resolveDue(now.plusMinutes(sla[1]))
                // 旧的单段口径保留兼容：等于解决截止时间
                .slaDeadline(now.plusMinutes(sla[1]))
                .slaState(SLA_NORMAL)
                .slaAlerted(false)
                .createTime(now)
                .updateTime(now)
                .creator(String.valueOf(user.userId()))
                .creatorName(user.name())
                .editor(String.valueOf(user.userId()))
                .deleted(false)
                .build();
        ticketMapper.insert(ticket);
        appendEvent(tenant, ticket.getId(), EVENT_CREATE, user.userId(), user.name(),
                null, STATUS_PENDING, false,
                ticket.getSessionNo() == null
                        ? "创建工单（" + priorityText(priority) + " / " + categoryText(category) + "）"
                        : "从会话 " + ticket.getSessionNo() + " 转工单（" + priorityText(priority) + "）");
        if (ticket.getAssigneeId() != null) {
            appendEvent(tenant, ticket.getId(), EVENT_ASSIGN, user.userId(), user.name(),
                    STATUS_PENDING, STATUS_PENDING, false,
                    "分派给 " + displayName(ticket.getAssigneeName(), ticket.getAssigneeId()));
        }
        log.info("新建工单 tenant={} ticketNo={} 优先级={} 来源={} 会话={} 处理人={} SLA(响应/解决)=({}min/{}min)",
                tenant, ticket.getTicketNo(), priorityText(priority), source, ticket.getSessionNo(),
                displayName(ticket.getAssigneeName(), ticket.getAssigneeId()), sla[0], sla[1]);
        return toVO(ticket, now);
    }

    // ------------------------------------------------------------------ 流转

    /**
     * 企业侧：提交一条**平台支持工单**（渠道接入失败、计费异常、平台故障这类平台才能处理的问题）。
     *
     * <p>和内部工单的区别：</p>
     * <ul>
     *   <li>处理人是**平台运营 / 平台管理员**，企业侧只能看进展、不能自己改状态；</li>
     *   <li>SLA 用平台的统一档位（不查企业自己的 SLA 规则）——企业改自己的配置，
     *       不该影响"平台承诺多久响应我"；</li>
     *   <li>回复与流转记录**双向可见**：平台回复企业看得到，企业补充平台也看得到。</li>
     * </ul>
     */
    @Transactional
    public TicketVO createPlatform(LoginUser user, CreateBody body) {
        requireOperate(user);
        String tenant = tenantOf(user);
        int priority = normalizePriority(body.priority());
        int category = normalizeCategory(body.category());
        int[] sla = DEFAULT_SLA_MINUTES[priority - 1].clone();
        String title = blankToNull(body.title());
        if (title == null) {
            throw new BizException(40001, "请填写要提交给平台的问题主题");
        }
        if (title.length() > 128) {
            title = title.substring(0, 128);
        }
        LocalDateTime now = LocalDateTime.now();
        Ticket ticket = Ticket.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .ticketNo(nextTicketNo(tenant))
                .ticketType(TICKET_TYPE_PLATFORM)
                .category(category)
                .title(title)
                .customerName(null)
                .desc(blankToNull(body.content()))
                .priority(priority)
                .status(STATUS_PENDING)
                .source(SOURCE_AGENT)
                .sessionNo(blankToNull(body.sessionNo()))
                .slaFirstMinutes(sla[0])
                .slaResolveMinutes(sla[1])
                .firstResponseDue(now.plusMinutes(sla[0]))
                .resolveDue(now.plusMinutes(sla[1]))
                .slaDeadline(now.plusMinutes(sla[1]))
                .slaState(SLA_NORMAL)
                .slaAlerted(false)
                .createTime(now)
                .updateTime(now)
                .creator(String.valueOf(user.userId()))
                .creatorName(user.name())
                .editor(String.valueOf(user.userId()))
                .deleted(false)
                .build();
        ticketMapper.insert(ticket);
        appendEvent(tenant, ticket.getId(), EVENT_CREATE, user.userId(), user.name(),
                null, STATUS_PENDING, true,
                "提交给平台支持（租户 " + tenant + "，" + priorityText(priority) + "）：" + title);
        log.info("企业提交平台支持工单 tenant={} ticketNo={} 优先级={} 提交人={}",
                tenant, ticket.getTicketNo(), priorityText(priority), user.name());
        return toVO(ticket, now);
    }

    /**
     * 分派 / 转派 / 认领。
     *
     * @param body toUserId 为空表示"认领给自己"
     */
    @Transactional
    public TicketDetailVO assign(LoginUser user, String ticketNo, AssignBody body) {
        requireOperate(user);
        String tenant = tenantOf(user);
        Ticket ticket = requireTicket(tenant, ticketNo);
        requireInternal(ticket, "认领 / 转派");
        requireOpen(ticket, "分派");
        long targetId = body == null || body.toUserId() == null ? user.userId() : body.toUserId();
        String targetName = body != null && body.toUserName() != null && !body.toUserName().isBlank()
                ? body.toUserName().trim()
                : (targetId == user.userId() ? user.name() : String.valueOf(targetId));
        boolean selfClaim = targetId == user.userId();
        String remark = body == null ? null : blankToNull(body.remark());

        int fromStatus = ticket.getStatus();
        int toStatus = fromStatus == STATUS_PENDING ? STATUS_PROCESSING : fromStatus;
        LocalDateTime now = LocalDateTime.now();
        Ticket update = new Ticket();
        update.setId(ticket.getId());
        update.setAssigneeId(targetId);
        update.setAssigneeName(targetName);
        update.setStatus(toStatus);
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(now);
        ticketMapper.updateById(update);
        appendEvent(tenant, ticket.getId(), EVENT_ASSIGN, user.userId(), user.name(),
                fromStatus, toStatus, false,
                (selfClaim ? "认领工单" : "转派给 " + targetName)
                        + (remark == null ? "" : "：" + remark));
        log.info("工单分派 tenant={} ticketNo={} 处理人={} 状态={} 操作人={}",
                tenant, ticketNo, targetName, toStatus, user.name());
        if (!selfClaim && targetId > 0) {
            // 被分派的人要马上知道（长连接在线就秒到，不在线也不影响主流程）
            notifyAssignee(tenant, targetId, ticket, "分派工单");
        }
        return detail(user, ticketNo);
    }

    /** 回复工单：对客户可见的回复会记入"首次响应"。 */
    @Transactional
    public TicketDetailVO reply(LoginUser user, String ticketNo, ReplyBody body) {
        requireOperate(user);
        String tenant = tenantOf(user);
        Ticket ticket = requireTicket(tenant, ticketNo);
        requireInternal(ticket, "回复");
        requireOpen(ticket, "回复");
        String content = body == null || body.content() == null ? "" : body.content().trim();
        if (content.isEmpty()) {
            throw new BizException(40001, "回复内容不能为空");
        }
        boolean visibleToCustomer = body.visibleToCustomer() == null || body.visibleToCustomer();
        LocalDateTime now = LocalDateTime.now();
        int fromStatus = ticket.getStatus();
        int toStatus = fromStatus == STATUS_PENDING ? STATUS_PROCESSING : fromStatus;
        Ticket update = new Ticket();
        update.setId(ticket.getId());
        update.setStatus(toStatus);
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(now);
        // 首次响应：第一条"对客户可见"的回复才算数——内部备注客户看不到，不能算已经响应
        if (visibleToCustomer && ticket.getFirstResponseAt() == null) {
            update.setFirstResponseAt(now);
        }
        // 回复的人顺手把工单认领了：不然"回复了但没处理人"的工单没人跟
        if (ticket.getAssigneeId() == null) {
            update.setAssigneeId(user.userId());
            update.setAssigneeName(user.name());
        }
        ticketMapper.updateById(update);
        appendEvent(tenant, ticket.getId(), EVENT_REPLY, user.userId(), user.name(),
                fromStatus, toStatus, visibleToCustomer, content);
        if (visibleToCustomer && ticket.getFirstResponseAt() == null) {
            log.info("工单首次响应 tenant={} ticketNo={} 距创建={}分钟 响应人={}",
                    tenant, ticketNo, minutesBetween(ticket.getCreateTime(), now), user.name());
        }
        return detail(user, ticketNo);
    }

    /**
     * 升级工单：优先级提一档（最高到紧急）+ 记一条「升级」流转 + 通知处理人与全组。
     *
     * <p>为什么升级**不重算 SLA 截止时间**：那样就成了"一升级就不超时"，考核形同虚设。
     * 升级只提高优先级（排序更靠前、提醒更强），建单时快照下来的截止时间不动。</p>
     */
    @Transactional
    public TicketDetailVO escalate(LoginUser user, String ticketNo, StatusBody body) {
        requireOperate(user);
        String tenant = tenantOf(user);
        Ticket ticket = requireTicket(tenant, ticketNo);
        requireInternal(ticket, "升级");
        requireOpen(ticket, "升级");
        int current = ticket.getPriority() == null ? PRIORITY_NORMAL : ticket.getPriority();
        int next = Math.min(PRIORITY_URGENT, current + 1);
        String remark = body == null ? null : blankToNull(body.remark());
        LocalDateTime now = LocalDateTime.now();

        Ticket update = new Ticket();
        update.setId(ticket.getId());
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(now);
        if (next != current) {
            update.setPriority(next);
        }
        ticketMapper.updateById(update);
        appendEvent(tenant, ticket.getId(), EVENT_ESCALATE, user.userId(), user.name(),
                null, null, false,
                "升级：" + priorityText(current) + " → " + priorityText(next)
                        + (remark == null ? "" : "：" + remark));

        // 升级要"被看见"：处理人单独提醒，企业级消息让主管在消息中心也能发现
        String text = "[" + priorityText(next) + "] " + ticket.getTitle()
                + (remark == null ? "" : "（" + remark + "）");
        if (ticket.getAssigneeId() != null) {
            notificationService.publishToUser(tenant, ticket.getAssigneeId(),
                    NotificationService.TYPE_TICKET, "工单升级：" + ticketNo, text, "tickets");
        }
        notificationService.publishToTenant(tenant, NotificationService.TYPE_TICKET,
                "工单升级：" + ticketNo, text + " · 升级人 " + user.name(), "tickets");
        log.info("工单升级 tenant={} ticketNo={} {} → {} 操作人={}",
                tenant, ticketNo, priorityText(current), priorityText(next), user.name());
        return detail(user, ticketNo);
    }

    /**
     * 状态流转：待处理 → 处理中 → 待客户确认 → 已解决 → 已关闭；已关闭可以重开。
     *
     * <p>解决/关闭时把 SLA 归位成"正常"：工单都处理完了，再挂一个"已超时"的红标没意义——
     * 到底有没有超时，看流转记录里的时间线更准。</p>
     */
    @Transactional
    public TicketDetailVO changeStatus(LoginUser user, String ticketNo, StatusBody body) {
        requireOperate(user);
        String tenant = tenantOf(user);
        Ticket ticket = requireTicket(tenant, ticketNo);
        requireInternal(ticket, "流转");
        int target = body == null || body.status() == null ? 0 : body.status();
        if (target < STATUS_PENDING || target > STATUS_CLOSED) {
            throw new BizException(40001, "工单状态不合法：1-待处理、2-处理中、3-待客户确认、4-已解决、5-已关闭");
        }
        int current = ticket.getStatus() == null ? STATUS_PENDING : ticket.getStatus();
        if (current == target) {
            throw new BizException(40001, "工单已经是「" + statusText(target) + "」了");
        }
        // 关闭别人的工单算"兜底处置"：不是负责人又不是管理员，就别动（客户还在等的话会被关掉）
        AccessVO access = access(user);
        if (target == STATUS_CLOSED && !access.canManageSla()
                && user.userId() != (ticket.getAssigneeId() == null ? -1L : ticket.getAssigneeId())) {
            throw new BizException(40302, "只有工单负责人或企业管理员能关闭工单（当前角色："
                    + access.roleName() + "）");
        }
        if (current == STATUS_CLOSED && target != STATUS_PROCESSING) {
            throw new BizException(40001, "已关闭的工单只能重新打开（转为处理中）");
        }
        String remark = body == null ? null : blankToNull(body.remark());
        LocalDateTime now = LocalDateTime.now();
        boolean finished = target == STATUS_RESOLVED || target == STATUS_CLOSED;
        boolean reopen = current == STATUS_CLOSED && target == STATUS_PROCESSING;

        Ticket update = new Ticket();
        update.setId(ticket.getId());
        update.setStatus(target);
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(now);
        if (target == STATUS_RESOLVED && ticket.getResolvedAt() == null) {
            update.setResolvedAt(now);
        }
        if (target == STATUS_CLOSED) {
            update.setCloseTime(now);
            if (ticket.getResolvedAt() == null) {
                update.setResolvedAt(now);
            }
        }
        if (reopen) {
            // 重开：清掉解决时间，SLA 重新进入扫描（截止时间不重算，超了就是超了）
            update.setResolvedAt(null);
            update.setCloseTime(null);
        }
        if (finished) {
            update.setSlaState(SLA_NORMAL);
            update.setSlaAlerted(false);
        }
        ticketMapper.updateById(update);

        int eventType = reopen ? EVENT_REOPEN : (finished ? EVENT_CLOSE : EVENT_STATUS);
        String action = reopen ? "重新打开工单" : "状态变更为「" + statusText(target) + "」";
        appendEvent(tenant, ticket.getId(), eventType, user.userId(), user.name(),
                current, target, false, action + (remark == null ? "" : "：" + remark));
        log.info("工单状态流转 tenant={} ticketNo={} {} → {} 操作人={}",
                tenant, ticketNo, statusText(current), statusText(target), user.name());
        return detail(user, ticketNo);
    }

    // ------------------------------------------------------------------ SLA 规则

    /** SLA 规则（租户没配过就先落一套默认值，页面直接可编辑）。 */
    public List<SlaRuleVO> slaRules(LoginUser user) {
        requireManage(user);
        String tenant = tenantOf(user);
        ensureDefaultRules(tenant);
        List<TicketSlaRule> rules = slaRuleMapper.selectList(Wrappers.<TicketSlaRule>lambdaQuery()
                .eq(TicketSlaRule::getTenantCode, tenant)
                .eq(TicketSlaRule::getDeleted, false)
                .orderByAsc(TicketSlaRule::getPriority));
        List<SlaRuleVO> result = new ArrayList<>(rules.size());
        for (TicketSlaRule rule : rules) {
            result.add(new SlaRuleVO(rule.getPriority(), priorityText(rule.getPriority()),
                    rule.getFirstResponseMinutes(), rule.getResolveMinutes(),
                    rule.getIsEnabled() == null || rule.getIsEnabled()));
        }
        return result;
    }

    /** 保存 SLA 规则：按优先级覆盖（页面是一张四行的表）。 */
    @Transactional
    public List<SlaRuleVO> saveSlaRules(LoginUser user, List<SlaRuleBody> bodies) {
        requireManage(user);
        String tenant = tenantOf(user);
        if (bodies == null || bodies.isEmpty()) {
            throw new BizException(40001, "没有要保存的 SLA 规则");
        }
        ensureDefaultRules(tenant);
        for (SlaRuleBody body : bodies) {
            int priority = normalizePriority(body.priority());
            int first = body.firstResponseMinutes() == null ? 0 : body.firstResponseMinutes();
            int resolve = body.resolveMinutes() == null ? 0 : body.resolveMinutes();
            if (first <= 0 || resolve <= 0) {
                throw new BizException(40001, priorityText(priority) + "的 SLA 时长必须大于 0 分钟");
            }
            if (first > resolve) {
                throw new BizException(40001, priorityText(priority) + "的首次响应时限不能大于解决时限");
            }
            TicketSlaRule existing = slaRuleMapper.selectOne(Wrappers.<TicketSlaRule>lambdaQuery()
                    .eq(TicketSlaRule::getTenantCode, tenant)
                    .eq(TicketSlaRule::getPriority, priority)
                    .eq(TicketSlaRule::getDeleted, false)
                    .last("LIMIT 1"));
            LocalDateTime now = LocalDateTime.now();
            if (existing == null) {
                slaRuleMapper.insert(TicketSlaRule.builder()
                        .id(idGenerator.nextId())
                        .tenantCode(tenant)
                        .priority(priority)
                        .firstResponseMinutes(first)
                        .resolveMinutes(resolve)
                        .isEnabled(body.isEnabled() == null || body.isEnabled())
                        .createTime(now)
                        .updateTime(now)
                        .creator(String.valueOf(user.userId()))
                        .editor(String.valueOf(user.userId()))
                        .deleted(false)
                        .build());
            } else {
                TicketSlaRule update = new TicketSlaRule();
                update.setId(existing.getId());
                update.setFirstResponseMinutes(first);
                update.setResolveMinutes(resolve);
                update.setIsEnabled(body.isEnabled() == null || body.isEnabled());
                update.setEditor(String.valueOf(user.userId()));
                update.setUpdateTime(now);
                slaRuleMapper.updateById(update);
            }
        }
        log.info("更新工单 SLA 规则 tenant={} 条数={} 操作人={}", tenant, bodies.size(), user.name());
        return slaRules(user);
    }

    private void ensureDefaultRules(String tenant) {
        Long count = slaRuleMapper.selectCount(Wrappers.<TicketSlaRule>lambdaQuery()
                .eq(TicketSlaRule::getTenantCode, tenant)
                .eq(TicketSlaRule::getDeleted, false));
        if (count != null && count > 0) {
            return;
        }
        for (int index = 0; index < DEFAULT_SLA_MINUTES.length; index++) {
            LocalDateTime now = LocalDateTime.now();
            slaRuleMapper.insert(TicketSlaRule.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .priority(index + 1)
                    .firstResponseMinutes(DEFAULT_SLA_MINUTES[index][0])
                    .resolveMinutes(DEFAULT_SLA_MINUTES[index][1])
                    .isEnabled(true)
                    .createTime(now)
                    .updateTime(now)
                    .creator("SYSTEM")
                    .editor("SYSTEM")
                    .deleted(false)
                    .build());
        }
        log.info("工单 SLA 规则初始化默认值 tenant={} 低/中/高/紧急 = {}", tenant,
                "240-2880 / 60-480 / 30-240 / 15-120 分钟");
    }

    private int[] resolveSla(String tenant, Integer priority) {
        int index = normalizePriority(priority) - 1;
        try {
            TicketSlaRule rule = slaRuleMapper.selectOne(Wrappers.<TicketSlaRule>lambdaQuery()
                    .eq(TicketSlaRule::getTenantCode, tenant)
                    .eq(TicketSlaRule::getPriority, index + 1)
                    .eq(TicketSlaRule::getIsEnabled, true)
                    .eq(TicketSlaRule::getDeleted, false)
                    .last("LIMIT 1"));
            if (rule != null && rule.getFirstResponseMinutes() != null && rule.getResolveMinutes() != null
                    && rule.getFirstResponseMinutes() > 0 && rule.getResolveMinutes() > 0) {
                return new int[]{rule.getFirstResponseMinutes(), rule.getResolveMinutes()};
            }
        } catch (Exception e) {
            log.warn("读取工单 SLA 规则失败，按默认值处理 tenant={} error={}", tenant, e.getMessage());
        }
        return DEFAULT_SLA_MINUTES[index].clone();
    }

    // ------------------------------------------------------------------ SLA 扫描

    /**
     * 定时扫描（30 秒一轮）：把"即将超时/已超时"标出来、记一条流转、推一条提醒。
     *
     * <p>为什么用轮询而不是"到期那一刻定时"：工单的截止时间是建单时算好的，
     * 期间可能改优先级、可能重开——扫当前状态永远比维护一堆定时任务简单可靠。</p>
     */
    @Scheduled(fixedDelay = 30_000L, initialDelay = 20_000L)
    public void scheduledSlaScan() {
        try {
            ScanResult result = scanSla();
            if (result.alerted() > 0 || result.overdue() > 0) {
                log.info("工单 SLA 扫描：在办 {} 条，超时 {} 条，本轮提醒 {} 条",
                        result.scanned(), result.overdue(), result.alerted());
            }
        } catch (Exception e) {
            // 定时任务里抛异常只会打到 stderr，工单不会因此坏掉，但要留下痕迹
            log.warn("工单 SLA 扫描失败：{}", e.getMessage(), e);
        }
    }

    /** 手动触发一次 SLA 扫描（页面上的"立即检查超时"，也是验证脚本用的入口）。 */
    public ScanResult scanSla(LoginUser user) {
        requireManage(user);
        return scanSla();
    }

    /** 定时任务用的入口（没有登录人，跑的是全库在办工单）。 */
    public ScanResult scanSla() {
        LocalDateTime now = LocalDateTime.now();
        List<Ticket> open = ticketMapper.selectList(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getDeleted, false)
                .in(Ticket::getStatus, STATUS_PENDING, STATUS_PROCESSING, STATUS_CONFIRMING)
                .isNotNull(Ticket::getResolveDue)
                .orderByAsc(Ticket::getResolveDue)
                .last("LIMIT " + SCAN_LIMIT));
        int alerted = 0;
        int overdue = 0;
        for (Ticket ticket : open) {
            int state = computeSlaState(ticket, now);
            if (state == SLA_OVERDUE) {
                overdue++;
            }
            boolean stateChanged = !Integer.valueOf(state).equals(ticket.getSlaState());
            if (stateChanged) {
                Ticket update = new Ticket();
                update.setId(ticket.getId());
                update.setSlaState(state);
                // 状态变了就允许再提醒一次（正常→预警一次，预警→超时再一次）
                update.setSlaAlerted(false);
                update.setUpdateTime(now);
                ticketMapper.updateById(update);
                ticket.setSlaState(state);
                ticket.setSlaAlerted(false);
            }
            if (state != SLA_NORMAL && !Boolean.TRUE.equals(ticket.getSlaAlerted())) {
                alert(ticket, state, now);
                alerted++;
            }
        }
        return new ScanResult(open.size(), alerted, overdue);
    }

    /** 预警：写流转记录 + 标记已提醒 + 推给处理人（没人认领就推给全租户坐席）。 */
    private void alert(Ticket ticket, int state, LocalDateTime now) {
        boolean overdue = state == SLA_OVERDUE;
        String text = overdue
                ? "工单已超时：应于 " + format(ticket.getResolveDue()) + " 前解决"
                : "工单即将超时：还剩 " + Math.max(0, minutesBetween(now, nextDue(ticket))) + " 分钟";
        appendEvent(ticket.getTenantCode(), ticket.getId(), EVENT_SLA_ALERT, null, "系统",
                null, null, false, text);
        Ticket update = new Ticket();
        update.setId(ticket.getId());
        update.setSlaAlerted(true);
        update.setUpdateTime(now);
        ticketMapper.updateById(update);
        ticket.setSlaAlerted(true);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketNo", ticket.getTicketNo());
        payload.put("title", ticket.getTitle());
        payload.put("priority", ticket.getPriority());
        payload.put("priorityText", priorityText(ticket.getPriority()));
        payload.put("slaState", state);
        payload.put("slaStateText", slaStateText(state));
        payload.put("assigneeId", ticket.getAssigneeId());
        payload.put("text", text);
        notifyClient.notifyTicketAlert(ticket.getTenantCode(), ticket.getAssigneeId(), payload);
        // 同时写进消息中心（右上角铃铛）：长连接只覆盖"当时在线"的人，消息中心是"回头看得到"
        if (ticket.getAssigneeId() != null) {
            notificationService.publishToUser(ticket.getTenantCode(), ticket.getAssigneeId(),
                    NotificationService.TYPE_TICKET,
                    slStateTitle(ticket.getTicketNo(), state), text, "tickets");
        } else {
            notificationService.publishToTenant(ticket.getTenantCode(), NotificationService.TYPE_TICKET,
                    slStateTitle(ticket.getTicketNo(), state), text + "（还没人认领，请尽快处理）", "tickets");
        }
        log.warn("[工单SLA] tenant={} ticketNo={} 状态={} 处理人={} 内容={}",
                ticket.getTenantCode(), ticket.getTicketNo(), slaStateText(state),
                ticket.getAssigneeId() == null ? "（未认领）" : ticket.getAssigneeId(), text);
    }

    private void notifyAssignee(String tenant, long assigneeId, Ticket ticket, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticketNo", ticket.getTicketNo());
        payload.put("title", ticket.getTitle());
        payload.put("priority", ticket.getPriority());
        payload.put("priorityText", priorityText(ticket.getPriority()));
        payload.put("slaState", ticket.getSlaState());
        payload.put("slaStateText", slaStateText(ticket.getSlaState()));
        payload.put("assigneeId", assigneeId);
        payload.put("text", reason + "：[" + priorityText(ticket.getPriority()) + "] " + ticket.getTitle());
        notifyClient.notifyTicketAlert(tenant, assigneeId, payload);
        notificationService.publishToUser(tenant, assigneeId, NotificationService.TYPE_TICKET,
                reason + "：" + ticket.getTicketNo(),
                "[" + priorityText(ticket.getPriority()) + "] " + ticket.getTitle(), "tickets");
    }

    private static String slStateTitle(String ticketNo, int state) {
        return "工单 " + ticketNo + " " + slaStateText(state);
    }

    /**
     * SLA 判定：先看两段时限有没有过，再看"下一个要赶的截止时间"还剩多少。
     *
     * @return {@link #SLA_NORMAL} / {@link #SLA_WARNING} / {@link #SLA_OVERDUE}
     */
    int computeSlaState(Ticket ticket, LocalDateTime now) {
        boolean finished = isFinished(ticket);
        // 已解决/已关闭：SLA 状态归位"正常"，不再挂红标。
        // 注意：**曾经超时过**这件事不会因此消失——超时那一刻已经写了一条 SLA 预警流转记录，
        // 那是留给"SLA 达成率"用的证据；挂在列表上的红标只表示"现在还在超时"。
        if (finished) {
            return SLA_NORMAL;
        }
        // ① 首次响应：客户还在等，而且已经过了截止时间
        if (ticket.getFirstResponseAt() == null && ticket.getFirstResponseDue() != null
                && !now.isBefore(ticket.getFirstResponseDue())) {
            return SLA_OVERDUE;
        }
        // ② 解决：还没解决，而且已经过了截止时间
        if (ticket.getResolveDue() != null && !now.isBefore(ticket.getResolveDue())) {
            return SLA_OVERDUE;
        }
        LocalDateTime next = nextDue(ticket);
        if (next == null) {
            return SLA_NORMAL;
        }
        int window = ticket.getFirstResponseAt() == null
                ? nz(ticket.getSlaFirstMinutes())
                : nz(ticket.getSlaResolveMinutes());
        long warnAt = Math.max(WARNING_FLOOR_MINUTES, Math.round(window * WARNING_RATIO));
        long remain = minutesBetween(now, next);
        return remain <= warnAt ? SLA_WARNING : SLA_NORMAL;
    }

    /** 下一个要赶的截止时间：首次响应没做就是它，否则是解决时限。 */
    LocalDateTime nextDue(Ticket ticket) {
        if (ticket.getFirstResponseAt() == null && ticket.getFirstResponseDue() != null) {
            return ticket.getFirstResponseDue();
        }
        return ticket.getResolveDue();
    }

    boolean isFinished(Ticket ticket) {
        Integer status = ticket.getStatus();
        return status != null && (status == STATUS_RESOLVED || status == STATUS_CLOSED);
    }

    // ------------------------------------------------------------------ 工具

    Ticket requireTicket(String tenant, String ticketNo) {
        if (ticketNo == null || ticketNo.isBlank()) {
            throw new BizException(40001, "缺少工单号");
        }
        Ticket ticket = ticketMapper.selectOne(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getTenantCode, tenant)
                .eq(Ticket::getTicketNo, ticketNo.trim())
                .eq(Ticket::getDeleted, false)
                .last("LIMIT 1"));
        if (ticket == null) {
            throw new BizException(40401, "工单不存在：" + ticketNo);
        }
        return ticket;
    }

    private void requireOpen(Ticket ticket, String action) {
        if (isFinished(ticket)) {
            throw new BizException(40001, "工单" + statusText(ticket.getStatus()) + "，不能再" + action
                    + "；如果还要跟进，请先重新打开");
        }
    }

    /**
     * 平台支持工单只有平台能处理；企业侧只能看进展、补充说明。
     *
     * <p>为什么不让企业改状态：状态是"平台承诺的处理进展"，
     * 企业自己点一下"已解决"就把这条承诺抹掉了，平台侧反而不知道还有没有遗留。</p>
     */
    private void requireInternal(Ticket ticket, String action) {
        if (ticket.getTicketType() != null && ticket.getTicketType() == TICKET_TYPE_PLATFORM) {
            throw new BizException(40302, "这是提交给平台的支持工单，" + action
                    + "由平台处理；你可以在下面补充说明，平台会看到");
        }
    }

    /**
     * 企业侧在**平台支持工单**下补充说明。
     *
     * <p>和 {@link #reply} 的区别：补充说明**不算平台的首次响应**（首次响应是平台对企业承诺的口径），
     * 也不改工单状态——只是把"又发现的新信息"追加到时间线上，平台侧能看到。</p>
     */
    @Transactional
    public TicketDetailVO supplement(LoginUser user, String ticketNo, ReplyBody body) {
        requireOperate(user);
        String tenant = tenantOf(user);
        Ticket ticket = requireTicket(tenant, ticketNo);
        if (ticket.getTicketType() == null || ticket.getTicketType() != TICKET_TYPE_PLATFORM) {
            throw new BizException(40001, "企业内部工单请直接用「回复」");
        }
        requireOpen(ticket, "补充说明");
        String content = body == null || body.content() == null ? "" : body.content().trim();
        if (content.isEmpty()) {
            throw new BizException(40001, "补充内容不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        Ticket update = new Ticket();
        update.setId(ticket.getId());
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(now);
        ticketMapper.updateById(update);
        appendEvent(tenant, ticket.getId(), EVENT_REPLY, user.userId(), user.name(),
                null, null, true, "【企业补充】" + content);
        return detail(user, ticketNo);
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2) {
            throw new BizException(40301, "仅企业成员可使用工单中心");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用工单中心");
        }
        return tenant;
    }

    // ------------------------------------------------------------------ 角色与权限

    /**
     * 当前登录人在工单里能做什么。
     *
     * <p>口径（和第 12 篇的成员权限保持一致）：</p>
     * <ul>
     *   <li>企业管理员 / 客服主管 / 高级客服 / 客服专员 / AI运营 → 建单、认领、转派、回复、流转；</li>
     *   <li>质检专员 / AI运营这类**只读角色** → 只能看列表与详情（他们不需要抢单）；</li>
     *   <li>SLA 规则读写、手动扫超时、强制关闭别人的工单 → 仅**企业管理员**。</li>
     * </ul>
     *
     * <p>降级策略：用户服务问不到角色时，日常作业照常放行（不能让一个内部接口把客服堵死），
     * 但管理类动作保守拒绝——多拦一次能解释清楚，放错一次就是配置被随手改了。</p>
     */
    public AccessVO access(LoginUser user) {
        String tenant = tenantOf(user);
        List<String> roles = userRoleClient.rolesOf(user.userId(), tenant);
        if (roles == null) {
            return new AccessVO(null, "未知（用户服务不可用）", true, false,
                    "暂时读不到你的角色，管理类操作（SLA 规则）已被临时收紧");
        }
        if (roles.isEmpty()) {
            // 老租户没有角色数据：和成员管理一样按企业管理员兼容
            return new AccessVO("ADMIN", "企业管理员", true, true, "该租户还没有角色数据，按企业管理员兼容");
        }
        String primary = primaryRole(roles);
        boolean readOnly = roles.stream().allMatch(READ_ONLY_ROLES::contains);
        boolean admin = roles.stream().anyMatch(ADMIN_ROLES::contains);
        String hint = readOnly
                ? "当前角色是只读角色，只能查看工单（建单 / 处理请找客服同事）"
                : (admin ? "可以管理 SLA 规则与全部工单" : "可以建单与处理工单；SLA 规则由企业管理员维护");
        return new AccessVO(primary, ROLE_NAMES.getOrDefault(primary, primary), !readOnly, admin, hint);
    }

    /** 主角色：多角色时按"管理 > 主管 > 高级客服 > 客服 > 其它"取最靠前的那个用于展示 */
    private String primaryRole(List<String> roles) {
        List<String> order = List.of("ADMIN", "SUPERVISOR", "SENIOR_AGENT", "AGENT", "QUALITY", "AI_OPERATOR");
        for (String candidate : order) {
            if (roles.contains(candidate)) {
                return candidate;
            }
        }
        return roles.get(0);
    }

    /** 处理类动作（建单 / 认领 / 转派 / 回复 / 状态流转）的权限校验 */
    private void requireOperate(LoginUser user) {
        AccessVO access = access(user);
        if (!access.canOperate()) {
            throw new BizException(40302, "当前角色（" + access.roleName() + "）只能查看工单，不能处理；"
                    + "需要建单或跟进请找客服同事");
        }
    }

    /** 管理类动作（SLA 规则 / 手动扫超时）的权限校验 */
    private void requireManage(LoginUser user) {
        AccessVO access = access(user);
        if (!access.canManageSla()) {
            throw new BizException(40302, "SLA 规则只有企业管理员能改（当前角色："
                    + access.roleName() + "）");
        }
    }

    /**
     * 生成工单号：TK + 时间戳 + 后 4 位。
     *
     * <p>同一个租户同一秒最多 10000 条，够用；真撞上了唯一索引会挡住，不会出错号。</p>
     */
    private String nextTicketNo(String tenant) {
        String no = "TK" + LocalDateTime.now().format(NO_TIME) + String.format("%04d", (int) (idGenerator.nextId() % 10000));
        // 唯一索引是租户内唯一，跨租户可以重号；真重复了这里自增一位重试一次
        Long exists = ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getTenantCode, tenant)
                .eq(Ticket::getTicketNo, no));
        if (exists != null && exists > 0) {
            no = no + (char) ('A' + (int) (idGenerator.nextId() % 26));
        }
        return no;
    }

    void appendEvent(String tenant, Long ticketId, int eventType, Long operatorId, String operatorName,
                             Integer fromStatus, Integer toStatus, boolean visibleToCustomer, String content) {
        LocalDateTime now = LocalDateTime.now();
        ticketEventMapper.insert(TicketEvent.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .ticketId(ticketId)
                .eventType(eventType)
                .operatorId(operatorId)
                .operatorName(operatorName)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .visibleToCustomer(visibleToCustomer)
                .content(content == null ? null : content.substring(0, Math.min(512, content.length())))
                .eventTime(now)
                .createTime(now)
                .build());
    }

    /**
     * 会话转单：把"来源会话 + 客户 + 机器人判断 + 最近对话"拼成一段能看懂上下文的描述。
     *
     * <p>这是工单能不能闭环的起点：接单的人第一眼就该知道客户到底遇到了什么，
     * 而不是拿着"客户要退款"四个字再去问一遍。</p>
     */
    private String describe(String tenant, CreateBody body, SessionService.SessionVO session, String sessionNo) {
        String content = body == null ? null : blankToNull(body.content());
        if (session == null) {
            return content;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("【来源会话】").append(sessionNo).append('\n');
        if (session.customerName() != null && !session.customerName().isBlank()) {
            builder.append("【客户】").append(session.customerName()).append('\n');
        }
        if (session.intent() != null && !session.intent().isBlank()) {
            builder.append("【机器人判断】意图：").append(session.intent());
            if (session.emotion() != null && !session.emotion().isBlank()) {
                builder.append("，情绪：").append(session.emotion());
            }
            builder.append('\n');
        }
        if (content != null) {
            builder.append("【坐席补充】").append(content).append('\n');
        }
        try {
            List<SessionService.MessageVO> messages =
                    sessionService.historyByTenant(tenant, sessionNo, null, TRANSCRIPT_LIMIT, true);
            if (!messages.isEmpty()) {
                builder.append("【最近对话】\n");
                for (SessionService.MessageVO message : messages) {
                    if (message.sendTime() != null && message.content() != null) {
                        String summary = Integer.valueOf(SessionService.MSG_TYPE_IMAGE).equals(message.msgType())
                                ? sessionService.imageHistoryText(message.content()) : message.content();
                        builder.append('[').append(format(message.sendTime())).append("] ")
                                .append(speakerText(message.senderType())).append('：')
                                .append(shorten(summary, 120)).append('\n');
                    }
                }
            }
        } catch (Exception e) {
            // 取不到历史不影响建单：描述里至少还有会话号、客户和坐席补充
            log.warn("会话转工单读取聊天记录失败 tenant={} session={} error={}", tenant, sessionNo, e.getMessage());
        }
        return builder.toString().trim();
    }

    private static String speakerText(Integer senderType) {
        if (senderType == null) {
            return "系统";
        }
        return switch (senderType) {
            case 1 -> "客户";
            case 2 -> "客服";
            case 3 -> "机器人";
            default -> "系统";
        };
    }

    private static String shorten(String text, int max) {
        String flat = text.replaceAll("[\\r\\n]+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private String titleOf(CreateBody body, SessionService.SessionVO session) {
        String title = body == null ? null : blankToNull(body.title());
        if (title != null) {
            return title.length() > 128 ? title.substring(0, 128) : title;
        }
        if (session != null && session.lastContent() != null && !session.lastContent().isBlank()) {
            String text = session.lastContent().replaceAll("\\s+", " ").trim();
            String prefix = session.customerName() == null ? "客户咨询" : session.customerName() + " 的咨询";
            return (prefix + "：" + text).substring(0, Math.min(128, prefix.length() + text.length() + 1));
        }
        throw new BizException(40001, "请填写工单主题");
    }

    /** 工单转 VO（平台支持工单列表也要用，所以是包内可见） */
    TicketVO toVO(Ticket ticket, LocalDateTime now) {
        int state = isFinished(ticket) ? SLA_NORMAL : computeSlaState(ticket, now);
        LocalDateTime next = isFinished(ticket) ? null : nextDue(ticket);
        Integer remain = next == null ? null : (int) minutesBetween(now, next);
        return new TicketVO(
                ticket.getTenantCode(),
                ticket.getTicketType() == null ? 1 : ticket.getTicketType(),
                ticketTypeText(ticket.getTicketType()),
                ticket.getTicketNo(),
                ticket.getTitle(),
                ticket.getSourceChannel(),
                sourceChannelText(ticket.getSourceChannel()),
                ticket.getCategory(),
                categoryText(ticket.getCategory()),
                ticket.getPriority(),
                priorityText(ticket.getPriority()),
                ticket.getStatus(),
                statusText(ticket.getStatus()),
                ticket.getSource(),
                sourceText(ticket.getSource()),
                ticket.getSessionNo(),
                ticket.getCustomerName(),
                ticket.getAssigneeId(),
                displayName(ticket.getAssigneeName(), ticket.getAssigneeId()),
                state,
                slaStateText(state),
                remain,
                ticket.getFirstResponseAt() != null,
                format(ticket.getFirstResponseDue()),
                format(ticket.getResolveDue()),
                format(ticket.getCreateTime()),
                ticket.getCreatorName(),
                finishText(ticket));
    }

    EventVO toEventVO(TicketEvent event) {
        return new EventVO(
                String.valueOf(event.getId()),
                event.getEventType(),
                eventTypeText(event.getEventType()),
                event.getOperatorName(),
                event.getContent(),
                statusText(event.getFromStatus()),
                statusText(event.getToStatus()),
                Boolean.TRUE.equals(event.getVisibleToCustomer()),
                format(event.getEventTime()));
    }

    private String finishText(Ticket ticket) {
        if (ticket.getResolvedAt() != null) {
            return "解决于 " + format(ticket.getResolvedAt()) + "，耗时 "
                    + minutesBetween(ticket.getCreateTime(), ticket.getResolvedAt()) + " 分钟";
        }
        return null;
    }

    private static long minutesBetween(LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            return 0;
        }
        return Duration.between(from, to).toMinutes();
    }

    private static String format(LocalDateTime time) {
        // 精确到秒（列表的创建时间、时间线的每条流转、SLA 截止时间都用它）：
        // 工单是要追责的场景，"12:00 创建的还是 12:00:40 创建的"差 40 秒，
        // 而 SLA 判定正是按秒算的——显示到分钟会出现"看着没超时、状态却已超时"的困惑。
        return time == null ? null : time.withNano(0).toString().replace('T', ' ').substring(0, 19);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String displayName(String name, Long id) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return id == null ? null : String.valueOf(id);
    }

    private int normalizePriority(Integer priority) {
        if (priority == null || priority < PRIORITY_LOW || priority > PRIORITY_URGENT) {
            return PRIORITY_NORMAL;
        }
        return priority;
    }

    private int normalizeCategory(Integer category) {
        if (category == null || category < 1 || category > 5) {
            return 5;
        }
        return category;
    }

    private int normalizeSource(Integer source) {
        if (source == null || source < SOURCE_SESSION || source > SOURCE_AGENT) {
            return SOURCE_AGENT;
        }
        return source;
    }

    public static String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case STATUS_PENDING -> "待处理";
            case STATUS_PROCESSING -> "处理中";
            case STATUS_CONFIRMING -> "待客户确认";
            case STATUS_RESOLVED -> "已解决";
            case STATUS_CLOSED -> "已关闭";
            default -> "未知";
        };
    }

    public static String priorityText(Integer priority) {
        if (priority == null) {
            return "";
        }
        return switch (priority) {
            case PRIORITY_LOW -> "低";
            case PRIORITY_NORMAL -> "中";
            case PRIORITY_HIGH -> "高";
            case PRIORITY_URGENT -> "紧急";
            default -> "未知";
        };
    }

    public static String categoryText(Integer category) {
        if (category == null) {
            return "";
        }
        return switch (category) {
            case 1 -> "订单";
            case 2 -> "退款售后";
            case 3 -> "物流";
            case 4 -> "商品";
            default -> "其他";
        };
    }

    public static String sourceText(Integer source) {
        if (source == null) {
            return "";
        }
        return switch (source) {
            case SOURCE_SESSION -> "会话转单";
            case SOURCE_SELF -> "客户自助";
            default -> "坐席新建";
        };
    }

    public static String ticketTypeText(Integer ticketType) {
        if (ticketType == null) {
            return "";
        }
        return ticketType == TICKET_TYPE_PLATFORM ? "平台支持" : "企业内部";
    }

    /** 来源渠道：客户从哪来（和 source"工单怎么来的"是两回事） */
    public static String sourceChannelText(Integer channel) {
        if (channel == null) {
            return "";
        }
        return switch (channel) {
            case 1 -> "在线会话";
            case 2 -> "电话热线";
            case 3 -> "邮件";
            case 4 -> "工单导入";
            default -> "其它";
        };
    }

    /** 来源渠道可选值（前端下拉用，避免两边各写一份） */
    public static Integer normalizeSourceChannel(Integer channel) {
        if (channel == null || channel < 1 || channel > 5) {
            return null;
        }
        return channel;
    }

    public static String slaStateText(Integer state) {
        if (state == null) {
            return "";
        }
        return switch (state) {
            case SLA_WARNING -> "即将超时";
            case SLA_OVERDUE -> "已超时";
            default -> "正常";
        };
    }

    private static String eventTypeText(Integer type) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case EVENT_CREATE -> "创建";
            case EVENT_ASSIGN -> "分派";
            case EVENT_REPLY -> "回复";
            case EVENT_ESCALATE -> "升级";
            case EVENT_CLOSE -> "关闭";
            case EVENT_REOPEN -> "重开";
            case EVENT_STATUS -> "状态变更";
            case EVENT_SLA_ALERT -> "SLA 预警";
            default -> "其它";
        };
    }

    // ------------------------------------------------------------------ 出入参

    public record TicketQuery(
            Integer status,
            Integer priority,
            Integer category,
            Integer slaState,
            Long assigneeId,
            Boolean mineOnly,
            Boolean unassignedOnly,
            String sessionNo,
            String keyword,
            /** 工单类型：1-企业内部（默认）、2-平台支持；为空按 1 处理 */
            Integer ticketType
    ) {
    }

    /** 列表分页结果：total + 当前页数据（前端用它渲染 el-pagination） */
    public record TicketPageVO(long total, int page, int pageSize, List<TicketVO> list) {
    }

    public record TicketVO(
            /** 租户号：平台侧跨租户列表要显示，企业侧顺带回传（前端不用猜） */
            String tenantCode,
            /** 1-企业内部工单、2-平台支持工单 */
            Integer ticketType,
            String ticketTypeText,
            String ticketNo,
            String title,
            /** 来源渠道（1-在线会话、2-电话热线、3-邮件、4-工单导入、5-其它） */
            Integer sourceChannel,
            String sourceChannelText,
            Integer category,
            String categoryText,
            Integer priority,
            String priorityText,
            Integer status,
            String statusText,
            Integer source,
            String sourceText,
            String sessionNo,
            String customerName,
            Long assigneeId,
            String assigneeName,
            Integer slaState,
            String slaStateText,
            /** 距离下一个截止时间还有多少分钟，负数表示已经超了 */
            Integer remainMinutes,
            boolean firstResponded,
            String firstResponseDue,
            String resolveDue,
            String createTime,
            String creatorName,
            String finishText
    ) {
    }

    public record EventVO(
            String id,
            Integer eventType,
            String eventTypeText,
            String operatorName,
            String content,
            String fromStatusText,
            String toStatusText,
            boolean visibleToCustomer,
            String eventTime
    ) {
    }

    public record SlaVO(int firstResponseMinutes, int resolveMinutes) {
    }

    public record TicketDetailVO(TicketVO ticket, String content, List<EventVO> events, SlaVO sla,
                                 String tenantCode) {
    }

    public record OverviewVO(
            int pending,
            int processing,
            int confirming,
            int overdue,
            int warning,
            int mine,
            int resolvedToday
    ) {
    }

    public record ScanResult(int scanned, int alerted, int overdue) {
    }

    /**
     * 当前登录人的工单权限（前端据此显隐按钮，服务端仍会再校验一次）。
     *
     * @param roleCode     主角色编码（如 ADMIN / AGENT / QUALITY）
     * @param roleName     角色中文名，直接显示
     * @param canOperate   能不能建单与处理（只读角色为 false）
     * @param canManageSla 能不能改 SLA 规则 / 手动扫超时（仅企业管理员）
     * @param hint         一句话说明当前角色能干什么，页面标题旁直接展示
     */
    public record AccessVO(String roleCode, String roleName, boolean canOperate, boolean canManageSla,
                           String hint) {
    }

    public record CreateBody(
            String title,
            String content,
            Integer category,
            Integer priority,
            Integer source,
            String sessionNo,
            Long assigneeId,
            String assigneeName,
            /** 客户名称：手建工单时必填（会话转单时自动取会话里的客户） */
            String customerName,
            /** 来源渠道：1-在线会话、2-电话热线、3-邮件、4-工单导入、5-其它 */
            Integer sourceChannel
    ) {
    }

    public record AssignBody(Long toUserId, String toUserName, String remark) {
    }

    public record ReplyBody(String content, Boolean visibleToCustomer) {
    }

    public record StatusBody(Integer status, String remark) {
    }

    public record SlaRuleBody(Integer priority, Integer firstResponseMinutes, Integer resolveMinutes,
                              Boolean isEnabled) {
    }

    public record SlaRuleVO(Integer priority, String priorityText, Integer firstResponseMinutes,
                            Integer resolveMinutes, boolean enabled) {
    }
}
