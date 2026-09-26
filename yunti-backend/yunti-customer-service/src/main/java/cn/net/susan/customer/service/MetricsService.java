package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.customer.internal.UserNameClient;
import cn.net.susan.customer.internal.UserRoleClient;
import cn.net.susan.customer.mapper.AgentDailyMetricMapper;
import cn.net.susan.customer.mapper.SessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据大屏与坐席绩效。
 *
 * <p>两个数据来源，刻意分开：</p>
 * <ol>
 *   <li><b>实时大屏</b>：直接对 session / session_message / agent_status 现算（只看今天，
 *       数据量小），页面每 5 秒轮询——大屏要的是"此刻"，不是"昨天平均"；</li>
 *   <li><b>绩效报表</b>：读 agent_daily_metric（坐席 × 天的预聚合），因为报表要按坐席筛选、
 *       排序、分页、下钻，现算会把 session_message 全表扫一遍。预聚合由定时任务 + 页面按钮重算，
 *       而且**重算是幂等的**（同一天算几次结果都一样）。</li>
 * </ol>
 *
 * <p>所有指标都来自本系统自己产生的数据：会话、消息、会话事件、评价、坐席状态。
 * 没有一笔需要人工维护。</p>
 */
@Service
public class MetricsService {

    private static final Logger log = LoggerFactory.getLogger(MetricsService.class);

    /** 报表默认看最近 7 天 */
    private static final int DEFAULT_RANGE_DAYS = 7;
    private static final int MAX_RANGE_DAYS = 180;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPORT_LIMIT = 500;
    /** 下钻抽屉里的会话明细条数 */
    private static final int DRILL_SESSION_LIMIT = 30;

    /** 排序字段白名单：前端传什么都拼不进 SQL */
    private static final Map<String, String> ORDER_BY = Map.of(
            "sessionCount", "session_count",
            "messageCount", "message_count",
            "firstResponse", "first_response_seconds",
            "csat", "csat_score",
            "transfer", "transfer_count",
            "closeCount", "close_count");

    /** 只有管理员与主管能看经营数据 */
    private static final Set<String> REPORT_ROLES = Set.of("ADMIN", "SUPERVISOR");
    private static final Map<String, String> ROLE_NAMES = Map.of(
            "ADMIN", "企业管理员", "SUPERVISOR", "客服主管", "SENIOR_AGENT", "高级客服",
            "AGENT", "客服专员", "QUALITY", "质检专员", "AI_OPERATOR", "AI运营");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");

    private final AgentDailyMetricMapper metricMapper;
    private final SessionMapper sessionMapper;
    private final UserNameClient userNameClient;
    private final UserRoleClient userRoleClient;

    public MetricsService(AgentDailyMetricMapper metricMapper,
                          SessionMapper sessionMapper,
                          UserNameClient userNameClient,
                          UserRoleClient userRoleClient) {
        this.metricMapper = metricMapper;
        this.sessionMapper = sessionMapper;
        this.userNameClient = userNameClient;
        this.userRoleClient = userRoleClient;
    }

    // ------------------------------------------------------------------ 实时大屏

    /**
     * 大屏实时指标：在线坐席、排队与进行中会话、今日会话/消息/评价、平均首响、
     * 机器人接待占比、渠道与意图分布、今日坐席排行、近 14 天趋势。
     */
    public RealtimeVO realtime(LoginUser user) {
        String tenant = tenantOf(user);
        requireReport(user, tenant);
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        Map<String, Object> live = sessionMapper.selectRealtimeOverview(tenant, dayStart, now);
        Map<String, Object> today = sessionMapper.selectTodayMetrics(tenant, dayStart, now);
        List<Map<String, Object>> hourly = sessionMapper.selectHourlyTrend(tenant, dayStart, now);
        List<Map<String, Object>> channels = sessionMapper.selectChannelDistribution(tenant, dayStart, now);
        List<Map<String, Object>> intents = sessionMapper.selectIntentDistribution(tenant, dayStart, now);
        List<Map<String, Object>> emotions = sessionMapper.selectEmotionDistribution(tenant, dayStart, now);
        List<Map<String, Object>> topAgents = sessionMapper.selectTodayAgentRanking(tenant, dayStart, now, 8);
        // 坐席实时状态与实时动态：大屏首页的两块"活"数据
        List<Map<String, Object>> agentLive = sessionMapper.selectAgentLive(tenant, dayStart, now, 12);
        List<Map<String, Object>> activities = recentActivity(tenant, dayStart, now);
        List<Map<String, Object>> trend = metricMapper.selectTenantTrend(
                tenant, LocalDate.now().minusDays(13), LocalDate.now());

        Map<String, String> names = namesOf(agentIds(topAgents));
        List<AgentRankVO> ranking = new ArrayList<>();
        for (Map<String, Object> row : topAgents) {
            Long agentId = longValue(row.get("agent_id"));
            ranking.add(new AgentRankVO(agentId == null ? null : String.valueOf(agentId),
                    displayName(names, agentId), intValue(row.get("session_count")),
                    intValue(row.get("message_count")), intValue(row.get("avg_response_seconds")),
                    decimalValue(row.get("csat_score"))));
        }

        // 坐席名字要跨服务取，这里把两批 id 合并成一次调用，别一个坐席打一次用户中心
        List<String> liveIds = agentIds(agentLive);
        Map<String, String> liveNames = namesOf(liveIds);
        List<AgentLiveVO> liveAgents = new ArrayList<>();
        for (Map<String, Object> row : agentLive) {
            Long agentId = longValue(row.get("agent_id"));
            int status = intValue(row.get("status"));
            boolean connected = Boolean.TRUE.equals(row.get("is_connected"));
            liveAgents.add(new AgentLiveVO(
                    agentId == null ? null : String.valueOf(agentId),
                    displayName(liveNames, agentId),
                    status,
                    agentStatusText(status, connected),
                    connected,
                    intValue(row.get("active_count")),
                    intValue(row.get("session_count")),
                    decimalValue(row.get("csat_score"))));
        }

        return new RealtimeVO(
                intValue(live.get("online_agents")),
                intValue(live.get("busy_agents")),
                intValue(live.get("total_agents")),
                intValue(live.get("queuing_sessions")),
                intValue(live.get("active_sessions")),
                intValue(live.get("bot_sessions")),
                intValue(today.get("session_today")),
                intValue(today.get("message_today")),
                intValue(today.get("customer_message_today")),
                intValue(today.get("csat_count_today")),
                intValue(today.get("csat_good_today")),
                decimalValue(today.get("csat_score_today")),
                intValue(today.get("first_response_seconds")),
                intValue(today.get("bot_session_today")),
                percent(intValue(today.get("bot_session_today")), intValue(today.get("session_today"))),
                hourly.stream().map(row -> new HourPointVO(
                        intValue(row.get("hour")), intValue(row.get("cnt")))).toList(),
                channels.stream().map(row -> new NameValueVO(
                        stringValue(row.get("name")), intValue(row.get("cnt")))).toList(),
                intents.stream().map(row -> new NameValueVO(
                        stringValue(row.get("name")), intValue(row.get("cnt")))).toList(),
                emotions.stream().map(row -> new NameValueVO(
                        stringValue(row.get("name")), intValue(row.get("cnt")))).toList(),
                ranking,
                liveAgents,
                trend.stream().map(row -> new TrendPointVO(
                        dayLabel(row.get("stat_date")), intValue(row.get("session_count")),
                        intValue(row.get("bot_count")), intValue(row.get("message_count")),
                        intValue(row.get("csat_count")), intValue(row.get("good_csat_count")),
                        decimalValue(row.get("csat_score")),
                        intValue(row.get("first_response_seconds")))).toList(),
                activities.stream().map(row -> new ActivityVO(
                        stringValue(row.get("kind")), stringValue(row.get("event_text")),
                        timeValue(row.get("event_time")))).toList(),
                now);
    }

    /**
     * 实时动态：数据源是业务表，取不到就返回空列表。
     *
     * <p>首页不该因为一块"锦上添花"的卡片整体打不开——所以这里自己吞异常，
     * 其余指标照常返回。</p>
     */
    private List<Map<String, Object>> recentActivity(String tenant, LocalDateTime dayStart, LocalDateTime now) {
        try {
            return sessionMapper.selectRecentActivity(tenant, dayStart, now, 8);
        } catch (Exception e) {
            log.warn("实时动态加载失败，已降级为空 tenant={} error={}", tenant, e.getMessage());
            return List.of();
        }
    }

    /** 状态文案：长连接断了就是离线（status 只区分在线 / 忙碌 / 小休） */
    private static String agentStatusText(int status, boolean connected) {
        if (!connected) {
            return "离线";
        }
        return switch (status) {
            case 1 -> "在线";
            case 2 -> "忙碌";
            case 3 -> "小休";
            default -> "离线";
        };
    }

    // ------------------------------------------------------------------ 坐席绩效

    /**
     * 坐席绩效报表（分页）。
     *
     * <p>时间范围默认最近 7 天、最多 180 天；排序走白名单；每行是"这个坐席在这段时间的合计 / 均值"。</p>
     */
    public AgentReportVO agentReport(LoginUser user, ReportQuery query, Integer page, Integer pageSize) {
        String tenant = tenantOf(user);
        requireReport(user, tenant);
        ReportQuery q = normalize(query);
        int current = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String orderBy = ORDER_BY.getOrDefault(q.sort() == null ? "" : q.sort(), "session_count");

        long total = metricMapper.countAgentReport(tenant, q.from(), q.to(), q.agentId());
        List<Map<String, Object>> rows = metricMapper.selectAgentReport(
                tenant, q.from(), q.to(), q.agentId(), orderBy, size, (current - 1) * size);
        Map<String, String> names = namesOf(agentIds(rows));
        List<AgentReportRowVO> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long agentId = longValue(row.get("agent_id"));
            items.add(new AgentReportRowVO(
                    agentId == null || agentId == 0 ? null : String.valueOf(agentId),
                    displayName(names, agentId),
                    intValue(row.get("active_days")),
                    intValue(row.get("session_count")),
                    intValue(row.get("human_session_count")),
                    intValue(row.get("message_count")),
                    intValue(row.get("customer_message_count")),
                    intValue(row.get("first_response_seconds")),
                    intValue(row.get("avg_response_seconds")),
                    intValue(row.get("avg_session_seconds")),
                    intValue(row.get("transfer_count")),
                    intValue(row.get("csat_count")),
                    decimalValue(row.get("csat_score")),
                    intValue(row.get("good_csat_count")),
                    intValue(row.get("close_count")),
                    sessionPerDay(intValue(row.get("session_count")), intValue(row.get("active_days")))));
        }
        return new AgentReportVO(total, current, size, q.from().toString(), q.to().toString(), items);
    }

    /**
     * 单个坐席下钻：按天趋势 + 最近的会话明细。
     *
     * <p>明细里带会话号、客户名、消息数、满意度——点进某条会话就能回在线客服 / 质检中心看上下文，
     * 这就是"报表下钻"要的效果：从数字直接回到产生数字的那条会话。</p>
     */
    public AgentDrillVO agentDetail(LoginUser user, String agentId, ReportQuery query) {
        String tenant = tenantOf(user);
        requireReport(user, tenant);
        ReportQuery q = normalize(query);
        Long id = agentId == null || agentId.isBlank() || "0".equals(agentId) ? null : Long.parseLong(agentId);
        List<Map<String, Object>> trend = metricMapper.selectAgentTrend(tenant, id, q.from(), q.to());
        List<Map<String, Object>> sessions = sessionMapper.selectAgentSessionsForReport(
                tenant, id, q.from().atStartOfDay(), q.to().plusDays(1).atStartOfDay(), DRILL_SESSION_LIMIT);
        Map<String, String> names = id == null ? Map.of() : userNameClient.namesOf(List.of(String.valueOf(id)));

        List<DayPointVO> points = new ArrayList<>();
        for (Map<String, Object> row : trend) {
            points.add(new DayPointVO(dayLabel(row.get("stat_date")), intValue(row.get("session_count")),
                    intValue(row.get("message_count")), decimalValue(row.get("csat_score")),
                    intValue(row.get("first_response_seconds"))));
        }
        List<DrillSessionVO> list = new ArrayList<>();
        for (Map<String, Object> row : sessions) {
            list.add(new DrillSessionVO(
                    stringValue(row.get("session_no")),
                    stringValue(row.get("customer_name")),
                    stringValue(row.get("source")),
                    intValue(row.get("status")),
                    intValue(row.get("msg_count")),
                    intValue(row.get("csat_score")),
                    stringValue(row.get("intent")),
                    stringValue(row.get("emotion")),
                    timeValue(row.get("start_time")),
                    timeValue(row.get("end_time")),
                    durationMinutes(row.get("start_time"), row.get("end_time"))));
        }
        return new AgentDrillVO(id == null ? null : String.valueOf(id),
                displayName(names, id), q.from().toString(), q.to().toString(), points, list);
    }

    /** 导出绩效 CSV（按当前筛选，最多 500 行）。带 BOM，Excel 打开不乱码。 */
    public String exportCsv(LoginUser user, ReportQuery query) {
        String tenant = tenantOf(user);
        requireReport(user, tenant);
        ReportQuery q = normalize(query);
        AgentReportVO report = agentReport(user, q, 1, EXPORT_LIMIT);
        StringBuilder csv = new StringBuilder("\ufeff");
        csv.append("坐席,活跃天数,接待会话,人工接待,坐席消息,客户消息,首次响应(秒),平均响应(秒),")
                .append("平均会话时长(秒),转接次数,评价数,满意度,好评数,结束会话,日均接待\n");
        for (AgentReportRowVO row : report.list()) {
            csv.append(csv(row.agentName())).append(',')
                    .append(row.activeDays()).append(',')
                    .append(row.sessionCount()).append(',')
                    .append(row.humanSessionCount()).append(',')
                    .append(row.messageCount()).append(',')
                    .append(row.customerMessageCount()).append(',')
                    .append(row.firstResponseSeconds()).append(',')
                    .append(row.avgResponseSeconds()).append(',')
                    .append(row.avgSessionSeconds()).append(',')
                    .append(row.transferCount()).append(',')
                    .append(row.csatCount()).append(',')
                    .append(row.csatScore() == null ? "" : row.csatScore()).append(',')
                    .append(row.goodCsatCount()).append(',')
                    .append(row.closeCount()).append(',')
                    .append(row.sessionPerDay()).append('\n');
        }
        return csv.toString();
    }

    private static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value.replace("\"", "\"\"");
        return text.contains(",") || text.contains("\"") || text.contains("\n")
                ? "\"" + text + "\"" : text;
    }

    // ------------------------------------------------------------------ 重算

    /** 重算某一天的坐席绩效（幂等）。页面上的「重算」按钮与定时任务都走它。 */
    @Transactional
    public int rebuild(LoginUser user, String day) {
        String tenant = tenantOf(user);
        requireReport(user, tenant);
        LocalDate date = day == null || day.isBlank() ? LocalDate.now() : LocalDate.parse(day);
        int rows = rebuildDay(tenant, date);
        log.info("坐席绩效重算 tenant={} date={} 行数={} operator={}", tenant, date, rows, user.name());
        return rows;
    }

    private int rebuildDay(String tenant, LocalDate date) {
        return metricMapper.rebuildDaily(tenant, date, date.atStartOfDay(), date.plusDays(1).atStartOfDay());
    }

    /**
     * 定时重算：每 5 分钟把"今天"和"昨天"各算一遍。
     *
     * <p>今天要算是因为白天的数字一直在变；昨天要算是因为跨零点之后，
     * 昨晚未结束的会话变成了已结束，会话时长要补上。</p>
     */
    @Scheduled(initialDelay = 60_000, fixedDelay = 300_000)
    public void scheduledRebuild() {
        for (String tenant : sessionMapper.selectTenantsForMetrics()) {
            for (LocalDate date : List.of(LocalDate.now(), LocalDate.now().minusDays(1))) {
                try {
                    rebuildDay(tenant, date);
                } catch (Exception e) {
                    log.warn("坐席绩效定时重算失败 tenant={} date={} error={}", tenant, date, e.getMessage());
                }
            }
        }
    }

    /**
     * 启动时把近 14 天补齐。
     *
     * <p>为什么要补：报表读的是预聚合表，如果只靠"每 5 分钟算今天和昨天"，
     * 重启之后历史日期就是空的（新库、或者刚跑完演示数据时尤其明显）。
     * 14 天 × 每个租户一次 upsert，成本很低，换来"打开页面就有数据"。</p>
     */
    @org.springframework.context.event.EventListener(
            org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void warmUpHistory() {
        try {
            List<String> tenants = sessionMapper.selectTenantsForMetrics();
            for (String tenant : tenants) {
                for (int i = 0; i < 14; i++) {
                    rebuildDay(tenant, LocalDate.now().minusDays(i));
                }
            }
            if (!tenants.isEmpty()) {
                log.info("坐席绩效历史回填完成：{}", String.join("、", tenants));
            }
        } catch (Exception e) {
            log.warn("坐席绩效历史回填失败（不影响启动）：{}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 当前登录人能不能看数据报表（前端据此显隐菜单）。
     *
     * <p>为什么单独给一个接口：菜单要在进页面之前就知道该不该显示。
     * 「成员管理」的条件是"仅企业管理员"，而经营数据是"管理员 + 客服主管"能看——
     * 两者口径不一样，不能复用同一个开关。</p>
     */
    public AccessVO access(LoginUser user) {
        String tenant = tenantOf(user);
        List<String> roles = userRoleClient.rolesOf(user.userId(), tenant);
        if (roles == null) {
            return new AccessVO(false, null, "未知（用户服务不可用）", "暂时读不到你的角色，数据报表已临时收紧");
        }
        if (roles.isEmpty()) {
            return new AccessVO(true, "ADMIN", "企业管理员", "该租户还没有角色数据，按企业管理员兼容");
        }
        List<String> clean = roles.stream().filter(java.util.Objects::nonNull).toList();
        String primary = clean.stream().filter(REPORT_ROLES::contains).findFirst().orElse(clean.get(0));
        boolean canView = clean.stream().anyMatch(REPORT_ROLES::contains);
        return new AccessVO(canView, primary, ROLE_NAMES.getOrDefault(primary, primary),
                canView ? "可以查看实时大屏与坐席绩效" : "数据报表只有企业管理员 / 客服主管能看");
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2) {
            throw new BizException(40301, "仅企业成员可查看数据报表");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再查看数据报表");
        }
        return tenant;
    }

    /**
     * 报表权限：**只有企业管理员与客服主管**能看经营数据。
     *
     * <p>和客户 360 的"只读角色能看不能改"不同：经营数据是敏感信息（坐席之间的横向对比、
     * 客户量与满意度），一线客服看同事业绩只会制造内耗，所以这里直接拒绝。
     * 问不到角色时保守拒绝——宁可让管理员确认一下，也不要把数据漏出去。</p>
     */
    private void requireReport(LoginUser user, String tenant) {
        List<String> roles = userRoleClient.rolesOf(user.userId(), tenant);
        if (roles == null) {
            throw new BizException(40302, "暂时读不到你的角色，数据报表已临时收紧；请稍后重试或联系管理员");
        }
        if (roles.isEmpty()) {
            return;
        }
        List<String> clean = roles.stream().filter(java.util.Objects::nonNull).toList();
        if (clean.stream().noneMatch(REPORT_ROLES::contains)) {
            throw new BizException(40302, "数据报表只有企业管理员 / 客服主管能看（当前角色："
                    + ROLE_NAMES.getOrDefault(clean.isEmpty() ? "" : clean.get(0), "未知") + "）");
        }
    }

    private ReportQuery normalize(ReportQuery query) {
        LocalDate to = query != null && query.to() != null ? query.to() : LocalDate.now();
        LocalDate from = query != null && query.from() != null
                ? query.from() : to.minusDays(DEFAULT_RANGE_DAYS - 1L);
        if (from.isAfter(to)) {
            throw new BizException(40001, "开始日期不能晚于结束日期");
        }
        if (Duration.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()).toDays() > MAX_RANGE_DAYS) {
            throw new BizException(40001, "一次最多查询 " + MAX_RANGE_DAYS + " 天，请缩小范围");
        }
        return new ReportQuery(from, to,
                query == null ? null : query.agentId(),
                query == null ? null : query.sort());
    }

    private Map<String, String> namesOf(List<String> ids) {
        return ids.isEmpty() ? Map.of() : userNameClient.namesOf(ids);
    }

    private List<String> agentIds(List<Map<String, Object>> rows) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long id = longValue(row.get("agent_id"));
            if (id != null && id != 0) {
                ids.add(String.valueOf(id));
            }
        }
        return ids;
    }

    private static String displayName(Map<String, String> names, Long agentId) {
        if (agentId == null || agentId == 0) {
            return "未分配 / 机器人接待";
        }
        String name = names.get(String.valueOf(agentId));
        return name == null || name.isBlank() ? "坐席 " + agentId : name;
    }

    private static String dayLabel(Object value) {
        LocalDate date = dateValue(value);
        return date == null ? "" : date.format(DAY);
    }

    private static int percent(int part, int total) {
        if (total <= 0) {
            return 0;
        }
        return BigDecimal.valueOf(part * 100.0 / total).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static BigDecimal sessionPerDay(int sessions, int days) {
        if (days <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(sessions).divide(BigDecimal.valueOf(days), 1, RoundingMode.HALF_UP);
    }

    private static Integer durationMinutes(Object start, Object end) {
        LocalDateTime from = timeValue(start);
        if (from == null) {
            return null;
        }
        LocalDateTime to = timeValue(end);
        if (to == null) {
            to = LocalDateTime.now();
        }
        return (int) Math.max(0, Duration.between(from, to).toMinutes());
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimalValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        try {
            return new BigDecimal(String.valueOf(value).trim()).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate dateValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value).substring(0, 10));
    }

    private static LocalDateTime timeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
        }
        return null;
    }

    // ------------------------------------------------------------------ 出入参

    /** 报表筛选条件 */
    public record ReportQuery(LocalDate from, LocalDate to, Long agentId, String sort) {
    }

    /** 数据报表权限 */
    public record AccessVO(boolean canView, String roleCode, String roleName, String hint) {
    }

    /** 实时大屏 */
    public record RealtimeVO(int onlineAgents, int busyAgents, int totalAgents, int queuingSessions,
                             int activeSessions, int botSessions, int sessionToday, int messageToday,
                             int customerMessageToday, int csatCountToday, int csatGoodToday,
                             BigDecimal csatScoreToday, int firstResponseSeconds, int botSessionToday,
                             int botRatio, List<HourPointVO> hourly, List<NameValueVO> channels,
                             List<NameValueVO> intents, List<NameValueVO> emotions,
                             List<AgentRankVO> topAgents, List<AgentLiveVO> agentLive,
                             List<TrendPointVO> trend, List<ActivityVO> activities,
                             LocalDateTime serverTime) {
    }

    public record HourPointVO(int hour, int count) {
    }

    public record NameValueVO(String name, int count) {
    }

    /**
     * 租户级按天趋势（大屏首页）。
     *
     * <p>比坐席下钻的 {@link DayPointVO} 多两列：机器人接待量与好评条数——
     * 首页的"会话趋势 / 满意度趋势"两张图靠它们画。</p>
     */
    public record TrendPointVO(String day, int sessionCount, int botCount, int messageCount,
                               int csatCount, int goodCsatCount, BigDecimal csatScore,
                               int firstResponseSeconds) {
    }

    /** 坐席实时状态（大屏「坐席实时状态」卡片） */
    public record AgentLiveVO(String agentId, String agentName, int status, String statusText,
                              boolean connected, int activeCount, int sessionCount,
                              BigDecimal csatScore) {
    }

    /** 实时动态一条（kind：session / transfer / csat / ticket / alert） */
    public record ActivityVO(String kind, String text, LocalDateTime eventTime) {
    }

    public record DayPointVO(String day, int sessionCount, int messageCount,
                             BigDecimal csatScore, int firstResponseSeconds) {
    }

    public record AgentRankVO(String agentId, String agentName, int sessionCount,
                              int messageCount, int avgResponseSeconds, BigDecimal csatScore) {
    }

    /** 报表一行 */
    public record AgentReportRowVO(String agentId, String agentName, int activeDays, int sessionCount,
                                   int humanSessionCount, int messageCount, int customerMessageCount,
                                   int firstResponseSeconds, int avgResponseSeconds, int avgSessionSeconds,
                                   int transferCount, int csatCount, BigDecimal csatScore,
                                   int goodCsatCount, int closeCount, BigDecimal sessionPerDay) {
    }

    /** 报表分页 */
    public record AgentReportVO(long total, int page, int pageSize, String from, String to,
                                List<AgentReportRowVO> list) {
    }

    /** 下钻：按天趋势 + 会话明细 */
    public record AgentDrillVO(String agentId, String agentName, String from, String to,
                               List<DayPointVO> trend, List<DrillSessionVO> sessions) {
    }

    public record DrillSessionVO(String sessionNo, String customerName, String source, int status,
                                 int msgCount, int csatScore, String intent, String emotion,
                                 LocalDateTime startTime, LocalDateTime endTime, Integer durationMinutes) {
    }
}
