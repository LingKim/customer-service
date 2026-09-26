package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.Customer;
import cn.net.susan.customer.entity.CustomerEvent;
import cn.net.susan.customer.entity.CustomerTag;
import cn.net.susan.customer.entity.CustomerTagDef;
import cn.net.susan.customer.entity.Session;
import cn.net.susan.customer.entity.Ticket;
import cn.net.susan.customer.internal.UserRoleClient;
import cn.net.susan.customer.mapper.CustomerEventMapper;
import cn.net.susan.customer.mapper.CustomerMapper;
import cn.net.susan.customer.mapper.CustomerTagDefMapper;
import cn.net.susan.customer.mapper.CustomerTagMapper;
import cn.net.susan.customer.mapper.SessionMapper;
import cn.net.susan.customer.mapper.TicketMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * 客户 360：客户画像 + 标签体系 + 会话轨迹。
 *
 * <p>三件事各自解决一个真实问题：</p>
 *
 * <ol>
 *   <li><b>画像</b>：坐席接起会话之前先看"他是谁"——会员等级、客户类型、历史会话数、转人工次数、
 *       工单数、满意度、风险等级、标签。这些数字散在 customer / session / ticket 三张表里，
 *       接会话的时候没人有空翻三个页面；</li>
 *   <li><b>标签体系</b>：标签必须"先定义、再打标"。直接往关联表里写标签名的做法，
 *       三个月后会得到"高价值 / 高价值客户 / 大客户"三个标签各挂十几个人，
 *       所以拆成<b>标签定义</b>（分组、颜色、手工/规则、启用）与<b>打标记录</b>；</li>
 *   <li><b>会话轨迹</b>：把这个人历史上每一次会话按时间排开——哪次机器人接待、哪次转人工、
 *       当时的意图与情绪、有没有转成工单、最后满意不满意。接手前扫一眼就知道上次为什么没解决。</li>
 * </ol>
 *
 * <p>权限口径与工单中心一致：只读角色（质检专员 / AI运营）能看不能改；
 * 标签体系（新建 / 改 / 停用 / 删除）只有企业管理员能动——标签是公司级分类标准，
 * 谁都能加一个，一个月后就没人说得清哪个是准的。</p>
 */
@Service
public class Customer360Service {

    private static final Logger log = LoggerFactory.getLogger(Customer360Service.class);

    /* ---------------- 会员等级（价值分层：跟"个人/企业"无关） ---------------- */
    private static final Map<Integer, String> LEVEL_NAMES = Map.of(
            1, "普通会员", 2, "银卡会员", 3, "金卡会员", 4, "铂金会员", 5, "钻石会员");

    /* ---------------- 客户类型（个人 / 企业） ---------------- */
    public static final int TYPE_PERSONAL = 1;
    public static final int TYPE_ENTERPRISE = 2;
    private static final Map<Integer, String> CUSTOMER_TYPE_NAMES = Map.of(
            1, "个人客户", 2, "企业客户");

    /* ---------------- 风险等级 ---------------- */
    public static final int RISK_NORMAL = 1;
    public static final int RISK_WATCH = 2;
    public static final int RISK_DANGER = 3;
    private static final Map<Integer, String> RISK_NAMES = Map.of(1, "正常", 2, "关注", 3, "风险");

    /* ---------------- 标签来源 ---------------- */
    public static final int TAG_SOURCE_MANUAL = 1;
    public static final int TAG_SOURCE_RULE = 2;
    public static final int TAG_SOURCE_IMPORT = 3;
    private static final Map<Integer, String> TAG_SOURCE_NAMES = Map.of(
            1, "手工打标", 2, "规则自动", 3, "批量导入");

    /* ---------------- 客户动态类型（常量与写入口都在 CustomerDynamics，避免各处各写一套） ---------------- */
    public static final int EVENT_CREATE = CustomerDynamics.CREATE;
    public static final int EVENT_TAG_ADD = CustomerDynamics.TAG_ADD;
    public static final int EVENT_TAG_REMOVE = CustomerDynamics.TAG_REMOVE;
    public static final int EVENT_LEVEL = CustomerDynamics.LEVEL;
    public static final int EVENT_REMARK = CustomerDynamics.REMARK;
    public static final int EVENT_RISK = CustomerDynamics.RISK;

    /** 会话状态：1-排队中、2-机器人接待、3-人工接待、4-已结束 */
    private static final Map<Integer, String> SESSION_STATUS_NAMES = Map.of(
            1, "排队中", 2, "机器人接待", 3, "人工接待", 4, "已结束");

    /** 标签颜色与分组白名单：只放行这几档，运营随手填个颜色也不会把页面搞花 */
    private static final Set<String> TAG_COLORS = Set.of("blue", "green", "orange", "red", "purple", "gray");
    private static final Set<String> TAG_GROUPS = Set.of("价值", "服务", "风险", "偏好", "来源", "其它");

    /** 只读角色：能看客户 360，但不能打标、不能改档案 */
    private static final Set<String> READ_ONLY_ROLES = Set.of("QUALITY", "AI_OPERATOR");
    private static final Set<String> ADMIN_ROLES = Set.of("ADMIN");
    /** 能看客户完整手机号的角色：管理员 + 客服主管（每次查看都写一条客户动态） */
    private static final Set<String> SUPERVISOR_ROLES = Set.of("ADMIN", "SUPERVISOR");
    private static final Map<String, String> ROLE_NAMES = Map.of(
            "ADMIN", "企业管理员",
            "SUPERVISOR", "客服主管",
            "SENIOR_AGENT", "高级客服",
            "AGENT", "客服专员",
            "QUALITY", "质检专员",
            "AI_OPERATOR", "AI运营");

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int EXPORT_LIMIT = 5000;
    /** 会话轨迹最多取 100 条：够看出问题脉络，再多就不是"一眼扫"了 */
    private static final int TRACE_LIMIT = 100;
    private static final int EVENT_LIMIT = 50;
    private static final int TICKET_LIMIT = 20;

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 导入建档时的客户编号：与线上访客编号同形状（V + 26 位随机串，不可枚举） */
    private static final int CUSTOMER_NO_LENGTH = 26;
    private static final String CUSTOMER_NO_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final java.util.Random RANDOM = new java.util.Random();

    private final CustomerMapper customerMapper;
    private final CustomerTagMapper customerTagMapper;
    private final CustomerTagDefMapper tagDefMapper;
    private final CustomerEventMapper customerEventMapper;
    /** 客户动态的唯一写入口：16 类事件的类型常量与落库都在这里面 */
    private final CustomerDynamics dynamics;
    private final SessionMapper sessionMapper;
    private final TicketMapper ticketMapper;
    /** 标签自动化：客户属性变了就重算一次规则标签 */
    private final CustomerTagRuleService tagRuleService;
    private final UserRoleClient userRoleClient;
    private final SnowflakeIdGenerator idGenerator;

    public Customer360Service(CustomerMapper customerMapper,
                              CustomerTagMapper customerTagMapper,
                              CustomerTagDefMapper tagDefMapper,
                              CustomerEventMapper customerEventMapper,
                              CustomerDynamics dynamics,
                              SessionMapper sessionMapper,
                              TicketMapper ticketMapper,
                              CustomerTagRuleService tagRuleService,
                              UserRoleClient userRoleClient,
                              SnowflakeIdGenerator idGenerator) {
        this.customerMapper = customerMapper;
        this.customerTagMapper = customerTagMapper;
        this.tagDefMapper = tagDefMapper;
        this.customerEventMapper = customerEventMapper;
        this.dynamics = dynamics;
        this.sessionMapper = sessionMapper;
        this.ticketMapper = ticketMapper;
        this.tagRuleService = tagRuleService;
        this.userRoleClient = userRoleClient;
        this.idGenerator = idGenerator;
    }

    // ------------------------------------------------------------------ 列表与概览

    /**
     * 客户列表。
     *
     * <p>默认按"最近活跃"倒序：客服打开客户 360 是想看"刚刚在跟我说话的是谁"，
     * 而不是"三年前注册的老客户"。要按价值看，用排序切换。</p>
     */
    public CustomerPageVO list(LoginUser user, CustomerQuery query, Integer page, Integer pageSize) {
        String tenant = tenantOf(user);
        int current = page == null || page < 1 ? 1 : page;
        int size = pageSize == null || pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        return queryPage(tenant, query, current, size);
    }

    /** 客户经营概览：列表页顶部那一排数字 */
    public OverviewVO overview(LoginUser user) {
        String tenant = tenantOf(user);
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> row = sessionMapper.selectCustomerOverview(
                tenant, now.withDayOfMonth(1).toLocalDate().atStartOfDay(), now.minusDays(7));
        if (row == null) {
            return new OverviewVO(0, 0, 0, 0, 0, 0);
        }
        return new OverviewVO(
                intValue(row.get("total")),
                intValue(row.get("month_new")),
                intValue(row.get("active_7d")),
                intValue(row.get("risk_count")),
                intValue(row.get("vip_count")),
                intValue(row.get("tagged_count")));
    }

    private CustomerPageVO queryPage(String tenant, CustomerQuery query, int current, int size) {
        CustomerQuery q = query == null ? CustomerQuery.empty() : query;
        LambdaQueryWrapper<Customer> counter = conditions(tenant, q);
        Long total = customerMapper.selectCount(counter);
        long totalCount = total == null ? 0 : total;
        LambdaQueryWrapper<Customer> wrapper = conditions(tenant, q);
        // 排序片段与分页都只含常量与已校验的整数，不会有注入面
        wrapper.last(orderBy(q.sort()) + " LIMIT " + size + " OFFSET " + (long) (current - 1) * size);
        List<Customer> rows = customerMapper.selectList(wrapper);

        List<Long> ids = rows.stream().map(Customer::getId).toList();
        Map<Long, List<TagVO>> tags = tagsOf(tenant, ids);
        Map<Long, Map<String, Object>> stats = sessionStatsOf(tenant, ids);
        Map<Long, Map<String, Object>> latest = latestSessionsOf(tenant, ids);

        List<CustomerVO> items = new ArrayList<>(rows.size());
        for (Customer row : rows) {
            items.add(toVO(row, tags.getOrDefault(row.getId(), List.of()),
                    stats.get(row.getId()), latest.get(row.getId())));
        }
        return new CustomerPageVO(totalCount, current, size, items);
    }

    /** 列表查询条件（数总数与取分页各用一份，互不干扰）。 */
    private LambdaQueryWrapper<Customer> conditions(String tenant, CustomerQuery q) {
        var wrapper = Wrappers.<Customer>lambdaQuery()
                .eq(Customer::getTenantCode, tenant)
                .eq(Customer::getDeleted, false);
        if (q.level() != null) {
            wrapper.eq(Customer::getLevel, q.level());
        }
        if (q.riskLevel() != null) {
            wrapper.eq(Customer::getRiskLevel, q.riskLevel());
        }
        if (q.customerType() != null) {
            wrapper.eq(Customer::getCustomerType, q.customerType());
        }
        if (q.channel() != null && !q.channel().isBlank()) {
            wrapper.eq(Customer::getChannel, q.channel().trim());
        }
        if (q.tagId() != null) {
            List<Long> customerIds = customerTagMapper.selectCustomerIdsByTag(tenant, q.tagId());
            if (customerIds.isEmpty()) {
                // 这个标签一个客户都没打：给个永假条件，别把空的 IN () 拼进 SQL
                wrapper.eq(Customer::getId, -1L);
            } else {
                wrapper.in(Customer::getId, customerIds);
            }
        }
        if (q.activeDays() != null && q.activeDays() > 0) {
            wrapper.ge(Customer::getLastActive, LocalDateTime.now().minusDays(q.activeDays()));
        }
        if (Boolean.TRUE.equals(q.hasTicket())) {
            wrapper.gt(Customer::getTicketCount, 0);
        }
        if (q.keyword() != null && !q.keyword().isBlank()) {
            String keyword = q.keyword().trim();
            wrapper.and(w -> w.like(Customer::getName, keyword)
                    .or().like(Customer::getCustomerNo, keyword)
                    .or().like(Customer::getPhone, keyword)
                    .or().like(Customer::getRemark, keyword));
        }
        return wrapper;
    }

    /** 排序：只认几个白名单值，前端传什么都拼不进 SQL */
    private String orderBy(String sort) {
        if ("sessions".equals(sort)) {
            return "ORDER BY session_count DESC, last_active DESC NULLS LAST";
        }
        if ("csat".equals(sort)) {
            return "ORDER BY csat DESC NULLS LAST, last_active DESC NULLS LAST";
        }
        if ("level".equals(sort)) {
            return "ORDER BY level DESC, last_active DESC NULLS LAST";
        }
        return "ORDER BY last_active DESC NULLS LAST, id DESC";
    }

    /** 一批客户身上的标签（一次查完，不逐个客户查） */
    private Map<Long, List<TagVO>> tagsOf(String tenant, List<Long> customerIds) {
        Map<Long, List<TagVO>> result = new HashMap<>();
        if (customerIds.isEmpty()) {
            return result;
        }
        for (Map<String, Object> row : customerTagMapper.selectTagsOfCustomers(tenant, customerIds)) {
            Long customerId = longValue(row.get("customer_id"));
            result.computeIfAbsent(customerId, key -> new ArrayList<>()).add(new TagVO(
                    stringValue(row.get("tag_id")),
                    stringValue(row.get("tag_name")),
                    stringValue(row.get("tag_group")),
                    stringValue(row.get("color")),
                    intValue(row.get("tag_type")),
                    intValue(row.get("source")),
                    TAG_SOURCE_NAMES.getOrDefault(intValue(row.get("source")), "手工打标"),
                    stringValue(row.get("operator_name")),
                    timeValue(row.get("create_time"))));
        }
        return result;
    }

    private Map<Long, Map<String, Object>> sessionStatsOf(String tenant, List<Long> customerIds) {
        Map<Long, Map<String, Object>> result = new HashMap<>();
        if (customerIds.isEmpty()) {
            return result;
        }
        for (Map<String, Object> row : sessionMapper.selectCustomerSessionStats(tenant, customerIds)) {
            result.put(longValue(row.get("customer_id")), row);
        }
        return result;
    }

    private Map<Long, Map<String, Object>> latestSessionsOf(String tenant, List<Long> customerIds) {
        Map<Long, Map<String, Object>> result = new HashMap<>();
        if (customerIds.isEmpty()) {
            return result;
        }
        for (Map<String, Object> row : sessionMapper.selectCustomerLatestSessions(tenant, customerIds)) {
            result.put(longValue(row.get("customer_id")), row);
        }
        return result;
    }

    // ------------------------------------------------------------------ 画像与轨迹

    /**
     * 客户 360 画像（详情抽屉的主体）。
     *
     * <p>一次把"这个人是谁"给全：档案 + 标签 + 会话/工单/满意度统计 + 最近一次会话的意图情绪 +
     * 最近订单与关联工单。前端只发一次请求就能把抽屉铺满，切 tab 不用再转圈。</p>
     */
    public CustomerDetailVO detail(LoginUser user, String customerNo) {
        String tenant = tenantOf(user);
        Customer customer = requireCustomer(tenant, customerNo);
        return detailOf(tenant, customer);
    }

    private CustomerDetailVO detailOf(String tenant, Customer customer) {
        List<TagVO> tags = tagsOf(tenant, List.of(customer.getId())).getOrDefault(customer.getId(), List.of());
        Map<String, Object> stats = sessionStatsOf(tenant, List.of(customer.getId())).get(customer.getId());
        Map<String, Object> latest = latestSessionsOf(tenant, List.of(customer.getId())).get(customer.getId());
        return new CustomerDetailVO(
                toVO(customer, tags, stats, latest),
                ticketsOf(tenant, customer.getId()));
    }

    /**
     * 会话轨迹：这个客户的全部会话（倒序），带渠道、坐席、消息数、是否转工单。
     *
     * <p>数据全部来自 session / channel / session_message / ticket 四张表，
     * 不额外落一份"轨迹表"——轨迹本来就是这些事实的投影，存两份必然会不一致。</p>
     */
    public List<SessionTraceVO> sessions(LoginUser user, String customerNo) {
        String tenant = tenantOf(user);
        Customer customer = requireCustomer(tenant, customerNo);
        List<SessionTraceVO> result = new ArrayList<>();
        for (Map<String, Object> row : sessionMapper.selectSessionsOfCustomer(
                tenant, customer.getId(), TRACE_LIMIT)) {
            result.add(new SessionTraceVO(
                    stringValue(row.get("session_no")),
                    intValue(row.get("status")),
                    SESSION_STATUS_NAMES.getOrDefault(intValue(row.get("status")), "未知"),
                    stringValue(row.get("channel_name")),
                    stringValue(row.get("source")),
                    stringValue(row.get("agent_id")),
                    stringValue(row.get("intent")),
                    stringValue(row.get("emotion")),
                    stringValue(row.get("bot_transfer_reason")),
                    intValue(row.get("msg_count")),
                    intValue(row.get("csat_score")),
                    stringValue(row.get("ticket_no")),
                    stringValue(row.get("ticket_status")) == null ? null : intValue(row.get("ticket_status")),
                    timeValue(row.get("start_time")),
                    timeValue(row.get("end_time")),
                    durationMinutes(row.get("start_time"), row.get("end_time"))));
        }
        return result;
    }

    /** 客户动态：谁在什么时候给他打了标 / 调了等级 / 写了备注 */
    public List<EventVO> events(LoginUser user, String customerNo) {
        String tenant = tenantOf(user);
        Customer customer = requireCustomer(tenant, customerNo);
        List<CustomerEvent> rows = customerEventMapper.selectList(Wrappers.<CustomerEvent>lambdaQuery()
                .eq(CustomerEvent::getTenantCode, tenant)
                .eq(CustomerEvent::getCustomerId, customer.getId())
                .orderByDesc(CustomerEvent::getEventTime)
                .orderByDesc(CustomerEvent::getId)
                .last("LIMIT " + EVENT_LIMIT));
        List<EventVO> result = new ArrayList<>(rows.size());
        for (CustomerEvent row : rows) {
            result.add(new EventVO(
                    String.valueOf(row.getId()),
                    row.getEventType(),
                    eventTypeName(row.getEventType()),
                    row.getEventTitle(),
                    row.getEventContent(),
                    row.getOperatorName() == null ? "系统" : row.getOperatorName(),
                    row.getEventTime()));
        }
        return result;
    }

    private List<TicketBriefVO> ticketsOf(String tenant, Long customerId) {
        List<Ticket> rows = ticketMapper.selectList(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getTenantCode, tenant)
                .eq(Ticket::getCustomerId, customerId)
                .eq(Ticket::getDeleted, false)
                .orderByDesc(Ticket::getCreateTime)
                .last("LIMIT " + TICKET_LIMIT));
        List<TicketBriefVO> result = new ArrayList<>(rows.size());
        for (Ticket row : rows) {
            result.add(new TicketBriefVO(
                    row.getTicketNo(),
                    row.getTitle(),
                    TicketService.statusText(row.getStatus()),
                    row.getPriority(),
                    TicketService.priorityText(row.getPriority()),
                    row.getAssigneeName(),
                    row.getSessionNo(),
                    row.getCreateTime()));
        }
        return result;
    }

    /**
     * 导出 CSV（按当前筛选，最多 5000 条）。
     *
     * <p>带 UTF-8 BOM，Excel 打开中文不乱码——这是导出功能最常见的"看起来能用其实不能用"。</p>
     */
    public String exportCsv(LoginUser user, CustomerQuery query) {
        String tenant = tenantOf(user);
        CustomerPageVO page = queryPage(tenant, query, 1, EXPORT_LIMIT);
        StringBuilder csv = new StringBuilder("\ufeff");
        csv.append("客户编号,客户姓名,手机号,会员等级,客户类型,风险等级,来源渠道,累计会话,累计工单,"
                + "满意度,标签,最近意图,最近情绪,最近活跃,备注\n");
        for (CustomerVO item : page.list()) {
            csv.append(csv(item.customerNo())).append(',')
                    .append(csv(item.name())).append(',')
                    .append(csv(item.phone())).append(',')
                    .append(csv(item.levelText())).append(',')
                    .append(csv(item.customerTypeText())).append(',')
                    .append(csv(item.riskText())).append(',')
                    .append(csv(item.channel())).append(',')
                    .append(item.sessionCount() == null ? 0 : item.sessionCount()).append(',')
                    .append(item.ticketCount() == null ? 0 : item.ticketCount()).append(',')
                    .append(item.csat() == null ? "" : item.csat()).append(',')
                    .append(csv(String.join(" / ", item.tags().stream().map(TagVO::tagName).toList()))).append(',')
                    .append(csv(item.lastIntent())).append(',')
                    .append(csv(item.lastEmotion())).append(',')
                    .append(csv(item.lastActive() == null ? "" : item.lastActive().toString())).append(',')
                    .append(csv(item.remark())).append('\n');
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

    // ------------------------------------------------------------------ 打标 / 去标 / 改档案

    /**
     * 给客户打标签。
     *
     * <p>三道校验：标签必须存在、必须启用、不能是只读角色在操作。
     * 重复打标不会报错——唯一索引挡着，代码里查一次已存在就直接返回当前画像
     * （重复点两下按钮不该弹红字）。</p>
     */
    @Transactional
    public CustomerDetailVO addTag(LoginUser user, String customerNo, Long tagId, String remark) {
        String tenant = tenantOf(user);
        requireOperate(user);
        Customer customer = requireCustomer(tenant, customerNo);
        CustomerTagDef def = tagDefMapper.selectById(tagId);
        if (def == null || Boolean.TRUE.equals(def.getDeleted()) || !tenant.equals(def.getTenantCode())) {
            throw new BizException(40401, "标签不存在");
        }
        if (!Boolean.TRUE.equals(def.getIsEnabled())) {
            throw new BizException(40001, "标签「" + def.getTagName() + "」已停用，不能继续打标");
        }
        Long exists = customerTagMapper.selectCount(Wrappers.<CustomerTag>lambdaQuery()
                .eq(CustomerTag::getTenantCode, tenant)
                .eq(CustomerTag::getCustomerId, customer.getId())
                .eq(CustomerTag::getTagId, tagId)
                .eq(CustomerTag::getDeleted, false));
        if (exists != null && exists > 0) {
            return detailOf(tenant, customer);
        }
        LocalDateTime now = LocalDateTime.now();
        customerTagMapper.insert(CustomerTag.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .customerId(customer.getId())
                .tagName(def.getTagName())
                .tagId(def.getId())
                .tagGroup(def.getTagGroup())
                .source(TAG_SOURCE_MANUAL)
                .operatorId(user.userId())
                .operatorName(user.name())
                .createTime(now)
                .updateTime(now)
                .deleted(false)
                .build());
        appendEvent(tenant, customer.getId(), EVENT_TAG_ADD,
                "打上标签：" + def.getTagName(),
                remark == null || remark.isBlank() ? null : remark.trim(),
                4, def.getTagCode(), user);
        log.info("客户打标 tenant={} customerNo={} tag={} operator={}",
                tenant, customerNo, def.getTagName(), user.name());
        return detailOf(tenant, customer);
    }

    /**
     * 摘掉客户身上的标签。
     *
     * <p>软删：坐席后天翻账时还要看到"这个标签是什么时候被谁摘掉的"，
     * 硬删掉就只剩一句"客户没有这个标签"，说不清是没打过还是被摘了。</p>
     */
    @Transactional
    public CustomerDetailVO removeTag(LoginUser user, String customerNo, Long tagId) {
        String tenant = tenantOf(user);
        requireOperate(user);
        Customer customer = requireCustomer(tenant, customerNo);
        CustomerTag tag = customerTagMapper.selectOne(Wrappers.<CustomerTag>lambdaQuery()
                .eq(CustomerTag::getTenantCode, tenant)
                .eq(CustomerTag::getCustomerId, customer.getId())
                .eq(CustomerTag::getTagId, tagId)
                .eq(CustomerTag::getDeleted, false)
                .last("LIMIT 1"));
        if (tag == null) {
            return detailOf(tenant, customer);
        }
        CustomerTag update = new CustomerTag();
        update.setId(tag.getId());
        update.setDeleted(true);
        update.setUpdateTime(LocalDateTime.now());
        customerTagMapper.updateById(update);
        CustomerTagDef def = tagDefMapper.selectById(tagId);
        String code = def == null ? null : def.getTagCode();
        appendEvent(tenant, customer.getId(), EVENT_TAG_REMOVE,
                "摘掉标签：" + tag.getTagName(), null, 4, code, user);
        log.info("客户去标 tenant={} customerNo={} tag={} operator={}",
                tenant, customerNo, tag.getTagName(), user.name());
        return detailOf(tenant, customer);
    }

    /**
     * 改客户档案：姓名 / 手机号 / 会员等级 / 风险等级 / 备注。
     *
     * <p>只改传了值的字段（null = 不动），这样前端"只改备注"不会顺手把等级清空。
     * 每一次真实变化都写一条客户动态——等级和风险等级是会影响别人怎么接待这个客户的，
     * 必须留痕。</p>
     */
    @Transactional
    public CustomerDetailVO updateProfile(LoginUser user, String customerNo, ProfileBody body) {
        String tenant = tenantOf(user);
        requireOperate(user);
        Customer customer = requireCustomer(tenant, customerNo);
        Customer update = new Customer();
        update.setId(customer.getId());
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(LocalDateTime.now());
        boolean changed = false;

        if (body.name() != null && !body.name().isBlank() && !body.name().trim().equals(customer.getName())) {
            update.setName(body.name().trim());
            appendEvent(tenant, customer.getId(), EVENT_REMARK,
                    "姓名更新：" + customer.getName() + " → " + body.name().trim(), null, null, null, user);
            changed = true;
        }
        if (body.phone() != null && !body.phone().isBlank() && !body.phone().trim().equals(customer.getPhone())) {
            update.setPhone(body.phone().trim());
            appendEvent(tenant, customer.getId(), EVENT_REMARK,
                    "联系方式更新", maskPhone(customer.getPhone()) + " → " + maskPhone(body.phone().trim()),
                    null, null, user);
            changed = true;
        }
        if (body.level() != null && !body.level().equals(customer.getLevel())) {
            if (body.level() < 1 || body.level() > 5) {
                throw new BizException(40001, "会员等级只能是 1~5");
            }
            update.setLevel(body.level());
            appendEvent(tenant, customer.getId(), EVENT_LEVEL,
                    "会员等级调整",
                    levelName(customer.getLevel()) + " → " + levelName(body.level()),
                    null, null, user);
            changed = true;
        }
        if (body.riskLevel() != null && !body.riskLevel().equals(customer.getRiskLevel())) {
            if (body.riskLevel() < RISK_NORMAL || body.riskLevel() > RISK_DANGER) {
                throw new BizException(40001, "风险等级只能是 1~3");
            }
            update.setRiskLevel(body.riskLevel());
            appendEvent(tenant, customer.getId(), EVENT_RISK,
                    "风险等级调整",
                    riskName(customer.getRiskLevel()) + " → " + riskName(body.riskLevel()),
                    null, null, user);
            changed = true;
        }
        if (body.customerType() != null && !body.customerType().equals(customer.getCustomerType())) {
            if (body.customerType() != TYPE_PERSONAL && body.customerType() != TYPE_ENTERPRISE) {
                throw new BizException(40001, "客户类型只能是 1-个人客户 / 2-企业客户");
            }
            update.setCustomerType(body.customerType());
            appendEvent(tenant, customer.getId(), EVENT_REMARK,
                    "客户类型调整",
                    customerTypeName(customer.getCustomerType()) + " → " + customerTypeName(body.customerType()),
                    null, null, user);
            changed = true;
        }
        String remark = trim255(body.remark());
        if (body.remark() != null && !java.util.Objects.equals(remark, customer.getRemark())) {
            update.setRemark(remark);
            appendEvent(tenant, customer.getId(), EVENT_REMARK,
                    "备注更新", remark, null, null, user);
            changed = true;
        }
        if (!changed) {
            return detailOf(tenant, customer);
        }
        customerMapper.updateById(update);
        return detailOf(tenant, requireCustomer(tenant, customerNo));
    }

    // ------------------------------------------------------------------ 标签体系

    // ------------------------------------------------------------------ 敏感信息与合规

    /**
     * 查看完整手机号（字段级权限 + 审计）。
     *
     * <p>为什么不做成"有权限就直接显示"：客户手机号属于个人敏感信息，**看一眼就该留痕**。
     * 所以列表与画像上永远只给脱敏号（138****8888），要点"查看完整"才会返回完整号码，
     * 同时往客户动态里写一条「敏感信息查阅」——谁在什么时候看了谁的手机号，查得到。</p>
     */
    @Transactional
    public PhoneVO revealPhone(LoginUser user, String customerNo) {
        String tenant = tenantOf(user);
        requireSupervisor(user);
        Customer customer = requireCustomer(tenant, customerNo);
        dynamics.record(tenant, customer.getId(), CustomerDynamics.PII_VIEW,
                "查看完整手机号", "操作人：" + user.name() + "（该操作已留痕）",
                null, null, user.userId(), user.name());
        log.info("查看客户完整手机号 tenant={} customerNo={} operator={}", tenant, customerNo, user.name());
        return new PhoneVO(customer.getPhone(), maskPhone(customer.getPhone()));
    }

    /**
     * 客户合并：把重复建档的客户并到目标客户上。
     *
     * <p>访客每次清缓存再进线都会新建一个客户档案，同一个人出现两条是常态。
     * 合并要做四件事：<b>迁关系</b>（会话 / 工单 / 订单 / 动态）、<b>并标签</b>（重复的不再挂两次）、
     * <b>标记来源</b>（源客户软删 + 记下并到谁了，能回溯）、<b>留痕</b>（目标客户动态里写一条）。</p>
     *
     * <p>不做"物理删除源客户"：他的会话、工单都还指着这个 ID，删了轨迹就断了。</p>
     */
    @Transactional
    public MergeResult merge(LoginUser user, String sourceNo, String targetNo) {
        String tenant = tenantOf(user);
        requireManage(user);
        Customer source = requireCustomer(tenant, sourceNo);
        Customer target = requireCustomer(tenant, targetNo);
        if (source.getId().equals(target.getId())) {
            throw new BizException(40001, "不能把客户合并到自己身上");
        }
        int sessions = moveSessions(tenant, source.getId(), target.getId());
        int tickets = moveTickets(tenant, source, target);
        int tags = moveTags(tenant, source.getId(), target.getId());
        // 动态跟着人走：合并后看目标客户的动态，能看到源客户身上发生过什么
        customerEventMapper.update(null, Wrappers.<CustomerEvent>lambdaUpdate()
                .eq(CustomerEvent::getTenantCode, tenant)
                .eq(CustomerEvent::getCustomerId, source.getId())
                .set(CustomerEvent::getCustomerId, target.getId()));

        LocalDateTime now = LocalDateTime.now();
        Customer updateSource = new Customer();
        updateSource.setId(source.getId());
        updateSource.setDeleted(true);
        updateSource.setMergedInto(target.getCustomerNo());
        updateSource.setEditor(String.valueOf(user.userId()));
        updateSource.setUpdateTime(now);
        customerMapper.updateById(updateSource);

        tagRuleService.recalculateForCustomer(tenant, target.getId(), user.name());
        dynamics.record(tenant, target.getId(), CustomerDynamics.MERGE,
                "合并客户：" + source.getCustomerNo(),
                "迁入 " + sessions + " 条会话 / " + tickets + " 张工单 / " + tags
                        + " 个标签（源客户：" + source.getName() + "）",
                null, source.getCustomerNo(), user.userId(), user.name());
        log.info("客户合并 tenant={} {} → {} 会话={} 工单={} 标签={} operator={}",
                tenant, sourceNo, targetNo, sessions, tickets, tags, user.name());
        return new MergeResult(source.getCustomerNo(), target.getCustomerNo(),
                sessions, tickets, tags);
    }

    private int moveSessions(String tenant, Long fromId, Long toId) {
        return sessionMapper.update(null, Wrappers.<Session>lambdaUpdate()
                .eq(Session::getTenantCode, tenant)
                .eq(Session::getCustomerId, fromId)
                .set(Session::getCustomerId, toId));
    }

    private int moveTickets(String tenant, Customer source, Customer target) {
        return ticketMapper.update(null, Wrappers.<Ticket>lambdaUpdate()
                .eq(Ticket::getTenantCode, tenant)
                .eq(Ticket::getCustomerId, source.getId())
                .set(Ticket::getCustomerId, target.getId())
                .set(Ticket::getCustomerName, target.getName()));
    }

    /**
     * 并标签：目标客户已经有的标签，源客户那条直接软删（不然部分唯一索引会挡）；
     * 剩下的整体迁过去。
     */
    private int moveTags(String tenant, Long fromId, Long toId) {
        List<CustomerTag> sourceTags = customerTagMapper.selectList(Wrappers.<CustomerTag>lambdaQuery()
                .eq(CustomerTag::getTenantCode, tenant)
                .eq(CustomerTag::getCustomerId, fromId)
                .eq(CustomerTag::getDeleted, false));
        if (sourceTags.isEmpty()) {
            return 0;
        }
        List<CustomerTag> targetTags = customerTagMapper.selectList(Wrappers.<CustomerTag>lambdaQuery()
                .eq(CustomerTag::getTenantCode, tenant)
                .eq(CustomerTag::getCustomerId, toId)
                .eq(CustomerTag::getDeleted, false));
        Set<Long> targetTagIds = new HashSet<>();
        for (CustomerTag tag : targetTags) {
            if (tag.getTagId() != null) {
                targetTagIds.add(tag.getTagId());
            }
        }
        int moved = 0;
        for (CustomerTag tag : sourceTags) {
            if (tag.getTagId() != null && targetTagIds.contains(tag.getTagId())) {
                CustomerTag drop = new CustomerTag();
                drop.setId(tag.getId());
                drop.setDeleted(true);
                drop.setUpdateTime(LocalDateTime.now());
                customerTagMapper.updateById(drop);
                continue;
            }
            CustomerTag move = new CustomerTag();
            move.setId(tag.getId());
            move.setCustomerId(toId);
            move.setUpdateTime(LocalDateTime.now());
            customerTagMapper.updateById(move);
            moved++;
        }
        return moved;
    }

    /**
     * 客户匿名化（合规上的"删除权"）。
     *
     * <p>抹掉能定位到人的三项：姓名、手机号、备注；**保留**会话、工单、订单与统计。
     * 为什么不做物理删除：这些记录是企业自己的经营数据（"这个月多少客户投诉"），
     * 删了报表就断了；合规要求的是"不能通过这些记录认出这个人"，抹掉 PII 就满足了。</p>
     */
    @Transactional
    public CustomerDetailVO anonymize(LoginUser user, String customerNo) {
        String tenant = tenantOf(user);
        requireManage(user);
        Customer customer = requireCustomer(tenant, customerNo);
        LocalDateTime now = LocalDateTime.now();
        Customer update = new Customer();
        update.setId(customer.getId());
        update.setName("已匿名客户");
        update.setPhone(null);
        update.setRemark(null);
        update.setAnonymizedAt(now);
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(now);
        // 手机号要显式置空：MyBatis-Plus 默认忽略 null 字段，这里用 UpdateWrapper 才能真的写 NULL
        customerMapper.update(null, Wrappers.<Customer>lambdaUpdate()
                .eq(Customer::getTenantCode, tenant)
                .eq(Customer::getId, customer.getId())
                .set(Customer::getName, "已匿名客户")
                .set(Customer::getPhone, null)
                .set(Customer::getRemark, null)
                .set(Customer::getAnonymizedAt, now)
                .set(Customer::getEditor, String.valueOf(user.userId()))
                .set(Customer::getUpdateTime, now));
        dynamics.record(tenant, customer.getId(), CustomerDynamics.ANONYMIZE,
                "客户匿名化", "姓名 / 手机号 / 备注已抹除，会话与统计保留",
                null, null, user.userId(), user.name());
        log.info("客户匿名化 tenant={} customerNo={} operator={}", tenant, customerNo, user.name());
        return detailOf(tenant, requireCustomer(tenant, customerNo));
    }

    /**
     * 客户删除（软删 + 抹 PII）。
     *
     * <p>和匿名化的区别：删除后**列表里不再出现**（运营视角就是"删掉了"），
     * 但会话与工单仍然挂着这个客户 ID（轨迹不断链）。真要"物理清除"属于 DBA 动作，
     * 业务系统里给一个按钮就能物理删库，迟早出事。</p>
     */
    @Transactional
    public void deleteCustomer(LoginUser user, String customerNo) {
        String tenant = tenantOf(user);
        requireManage(user);
        Customer customer = requireCustomer(tenant, customerNo);
        LocalDateTime now = LocalDateTime.now();
        customerMapper.update(null, Wrappers.<Customer>lambdaUpdate()
                .eq(Customer::getTenantCode, tenant)
                .eq(Customer::getId, customer.getId())
                .set(Customer::getDeleted, true)
                .set(Customer::getName, "已删除客户")
                .set(Customer::getPhone, null)
                .set(Customer::getRemark, null)
                .set(Customer::getAnonymizedAt, now)
                .set(Customer::getEditor, String.valueOf(user.userId()))
                .set(Customer::getUpdateTime, now));
        dynamics.record(tenant, customer.getId(), CustomerDynamics.DELETE,
                "客户删除", "客户已从列表移除（会话与工单仍保留，便于追溯）",
                null, null, user.userId(), user.name());
        log.info("客户删除 tenant={} customerNo={} operator={}", tenant, customerNo, user.name());
    }

    /**
     * 批量打标（客户列表勾选一批人打同一个标签）。
     *
     * <p>逐个客户复用单条打标的校验与留痕，但**只查一次标签定义**；
     * 已经有这个标签的直接跳过（幂等），不报错。</p>
     */
    @Transactional
    public BatchTagResult batchAddTag(LoginUser user, BatchTagBody body) {
        String tenant = tenantOf(user);
        requireOperate(user);
        if (body.tagId() == null) {
            throw new BizException(40001, "先选一个标签");
        }
        if (body.customerNos() == null || body.customerNos().isEmpty()) {
            throw new BizException(40001, "先勾选要打标的客户");
        }
        CustomerTagDef def = tagDefMapper.selectById(body.tagId());
        if (def == null || Boolean.TRUE.equals(def.getDeleted()) || !tenant.equals(def.getTenantCode())) {
            throw new BizException(40401, "标签不存在");
        }
        if (!Boolean.TRUE.equals(def.getIsEnabled())) {
            throw new BizException(40001, "标签「" + def.getTagName() + "」已停用，不能继续打标");
        }
        List<Customer> targets = customerMapper.selectList(Wrappers.<Customer>lambdaQuery()
                .eq(Customer::getTenantCode, tenant)
                .eq(Customer::getDeleted, false)
                .in(Customer::getCustomerNo, body.customerNos()));
        Set<String> found = new HashSet<>();
        int tagged = 0;
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Customer customer : targets) {
            found.add(customer.getCustomerNo());
            Long exists = customerTagMapper.selectCount(Wrappers.<CustomerTag>lambdaQuery()
                    .eq(CustomerTag::getTenantCode, tenant)
                    .eq(CustomerTag::getCustomerId, customer.getId())
                    .eq(CustomerTag::getTagId, def.getId())
                    .eq(CustomerTag::getDeleted, false));
            if (exists != null && exists > 0) {
                skipped++;
                continue;
            }
            customerTagMapper.insert(CustomerTag.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .customerId(customer.getId())
                    .tagName(def.getTagName())
                    .tagId(def.getId())
                    .tagGroup(def.getTagGroup())
                    .source(TAG_SOURCE_MANUAL)
                    .operatorId(user.userId())
                    .operatorName(user.name())
                    .createTime(now)
                    .updateTime(now)
                    .deleted(false)
                    .build());
            dynamics.record(tenant, customer.getId(), CustomerDynamics.TAG_ADD,
                    "打上标签：" + def.getTagName(),
                    body.remark() == null || body.remark().isBlank()
                            ? "批量打标" : "批量打标：" + body.remark().trim(),
                    CustomerDynamics.REF_TAG, def.getTagCode(), user.userId(), user.name());
            tagged++;
        }
        List<String> missing = body.customerNos().stream().filter(no -> !found.contains(no)).toList();
        log.info("批量打标 tenant={} tag={} 请求={} 成功={} 跳过={} 不存在={} operator={}",
                tenant, def.getTagName(), body.customerNos().size(), tagged, skipped, missing.size(), user.name());
        return new BatchTagResult(body.customerNos().size(), tagged, skipped, missing);
    }

    /**
     * 客户导入（CSV，UTF-8）。
     *
     * <p>真实业务里客户数据大多来自别的系统，一个一个建不现实。约定三件事：</p>
     * <ol>
     *   <li><b>匹配顺序</b>：手机号 → 都没有就新建。手机号是"自然人"最稳的键
     *       （同一部手机换个渠道还是他），我们自己的客户编号只有系统里有；</li>
     *   <li><b>逐行容错</b>：某一行格式不对只跳过这一行，并在结果里报"第几行、为什么"，
     *       不能因为第 37 行少了个引号就把前 36 行一起回滚（运营会疯）；</li>
     *   <li><b>标签按名字匹配</b>：标签体系里没有的名字直接跳过并提示，不会偷偷建新标签，
     *       否则导一次数据就能把标签体系搞出十几个同义标签。</li>
     * </ol>
     *
     * <p>表头（第一行）固定六列：<code>姓名,手机号,会员等级,标签,备注,客户类型</code>（后五项可缺省）。
     * 会员等级写中文（普通会员 / 银卡会员 / 金卡会员 / 铂金会员 / 企业客户）或数字 1~5 都认；
     * 标签用 <code>|</code> 或 <code>;</code> 分隔。</p>
     */
    @Transactional
    public ImportResult importCsv(LoginUser user, org.springframework.web.multipart.MultipartFile file) {
        String tenant = tenantOf(user);
        requireOperate(user);
        List<String> lines;
        try {
            String raw = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
            lines = new ArrayList<>(List.of(raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")));
        } catch (Exception e) {
            throw new BizException(40001, "读不到文件内容：" + e.getMessage());
        }
        if (lines.isEmpty() || lines.get(0).isBlank()) {
            throw new BizException(40001, "文件是空的，第一行要是表头：姓名,手机号,会员等级,标签,备注");
        }
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (!lines.get(i).isBlank()) {
                rows.add(parseCsvLine(lines.get(i)));
            }
        }
        if (rows.isEmpty()) {
            throw new BizException(40001, "表头下面没有数据行");
        }
        if (rows.size() > 2000) {
            throw new BizException(40001, "一次最多导入 2000 行，请拆成多个文件");
        }
        Map<String, CustomerTagDef> tagByName = new HashMap<>();
        for (CustomerTagDef def : tagDefMapper.selectList(Wrappers.<CustomerTagDef>lambdaQuery()
                .eq(CustomerTagDef::getTenantCode, tenant)
                .eq(CustomerTagDef::getDeleted, false))) {
            tagByName.put(def.getTagName(), def);
        }
        Map<String, Customer> byPhone = new HashMap<>();
        for (Customer existing : customerMapper.selectList(Wrappers.<Customer>lambdaQuery()
                .eq(Customer::getTenantCode, tenant)
                .eq(Customer::getDeleted, false)
                .isNotNull(Customer::getPhone))) {
            byPhone.put(existing.getPhone(), existing);
        }
        LocalDateTime now = LocalDateTime.now();
        int created = 0;
        int updated = 0;
        int tagged = 0;
        List<ImportFailure> failures = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            int lineNo = index + 2;   // 第 1 行是表头
            String name = cell(row, 0);
            String phone = cell(row, 1);
            String levelText = cell(row, 2);
            String tagText = cell(row, 3);
            String remark = cell(row, 4);
            String typeText = cell(row, 5);
            if (name == null || name.isBlank()) {
                failures.add(new ImportFailure(lineNo, "姓名不能为空"));
                continue;
            }
            Integer level = parseLevel(levelText);
            if (levelText != null && !levelText.isBlank() && level == null) {
                failures.add(new ImportFailure(lineNo, "会员等级认不出来：" + levelText
                        + "（写 1~5 或普通会员/银卡会员/金卡会员/铂金会员/钻石会员）"));
                continue;
            }
            Integer customerType = parseCustomerType(typeText);
            if (typeText != null && !typeText.isBlank() && customerType == null) {
                failures.add(new ImportFailure(lineNo, "客户类型认不出来：" + typeText
                        + "（写 个人 / 企业，或 1 / 2）"));
                continue;
            }
            Customer customer = phone == null || phone.isBlank() ? null : byPhone.get(phone.trim());
            if (customer == null) {
                String no = nextCustomerNo(tenant);
                Customer fresh = Customer.builder()
                        .id(idGenerator.nextId())
                        .tenantCode(tenant)
                        .customerNo(no)
                        .name(name.trim())
                        .phone(phone == null || phone.isBlank() ? null : phone.trim())
                        .level(level == null ? 1 : level)
                        .customerType(customerType == null ? TYPE_PERSONAL : customerType)
                        .channel("批量导入")
                        .ordersCount(0)
                        .totalValue(BigDecimal.ZERO)
                        .points(0)
                        .remark(trim255(remark))
                        .sessionCount(0)
                        .ticketCount(0)
                        .riskLevel(RISK_NORMAL)
                        .createTime(now)
                        .updateTime(now)
                        .creator(String.valueOf(user.userId()))
                        .deleted(false)
                        .build();
                customerMapper.insert(fresh);
                customer = fresh;
                if (fresh.getPhone() != null) {
                    byPhone.put(fresh.getPhone(), fresh);
                }
                created++;
                dynamics.record(tenant, customer.getId(), CustomerDynamics.CREATE,
                        "客户建档", "来源：批量导入（" + user.name() + "）", null, null,
                        user.userId(), user.name());
            } else {
                Customer patch = new Customer();
                patch.setId(customer.getId());
                if (level != null) {
                    patch.setLevel(level);
                }
                if (customerType != null) {
                    patch.setCustomerType(customerType);
                }
                if (remark != null && !remark.isBlank()) {
                    patch.setRemark(trim255(remark));
                }
                patch.setEditor(String.valueOf(user.userId()));
                patch.setUpdateTime(now);
                customerMapper.updateById(patch);
                updated++;
                dynamics.record(tenant, customer.getId(), CustomerDynamics.REMARK,
                        "批量导入更新", "来自 CSV 第 " + lineNo + " 行", null, null,
                        user.userId(), user.name());
            }
            if (tagText != null && !tagText.isBlank()) {
                for (String rawTag : tagText.split("[|;；]")) {
                    String tagName = rawTag.trim();
                    if (tagName.isEmpty()) {
                        continue;
                    }
                    CustomerTagDef def = tagByName.get(tagName);
                    if (def == null) {
                        failures.add(new ImportFailure(lineNo, "标签体系里没有「" + tagName + "」，已跳过该标签"));
                        continue;
                    }
                    Long exists = customerTagMapper.selectCount(Wrappers.<CustomerTag>lambdaQuery()
                            .eq(CustomerTag::getTenantCode, tenant)
                            .eq(CustomerTag::getCustomerId, customer.getId())
                            .eq(CustomerTag::getTagId, def.getId())
                            .eq(CustomerTag::getDeleted, false));
                    if (exists != null && exists > 0) {
                        continue;
                    }
                    customerTagMapper.insert(CustomerTag.builder()
                            .id(idGenerator.nextId())
                            .tenantCode(tenant)
                            .customerId(customer.getId())
                            .tagName(def.getTagName())
                            .tagId(def.getId())
                            .tagGroup(def.getTagGroup())
                            .source(TAG_SOURCE_IMPORT)
                            .operatorId(user.userId())
                            .operatorName(user.name())
                            .createTime(now)
                            .updateTime(now)
                            .deleted(false)
                            .build());
                    tagged++;
                }
            }
            tagRuleService.recalculateForCustomer(tenant, customer.getId(), user.name());
        }
        log.info("客户导入 tenant={} 行数={} 新建={} 更新={} 打标={} 问题={} operator={}",
                tenant, rows.size(), created, updated, tagged, failures.size(), user.name());
        return new ImportResult(rows.size(), created, updated, tagged, failures);
    }

    /** CSV 一行拆列：支持双引号包裹、引号里带逗号与转义引号（""） */
    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private static String cell(List<String> row, int index) {
        if (index >= row.size()) {
            return null;
        }
        String value = row.get(index);
        return value == null ? null : value.trim();
    }

    /** 会员等级：认数字 1~5，也认中文名（运营从别处导出来的表大多是中文） */
    private static Integer parseLevel(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        try {
            int level = Integer.parseInt(value);
            return level >= 1 && level <= 5 ? level : null;
        } catch (NumberFormatException ignored) {
            // 数字认不出来就往下走中文名
        }
        for (Map.Entry<Integer, String> entry : LEVEL_NAMES.entrySet()) {
            if (entry.getValue().equals(value)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 客户类型：认 个人 / 企业，也认 1 / 2 与"个人客户 / 企业客户" */
    private static Integer parseCustomerType(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        if ("1".equals(value) || "个人".equals(value) || "个人客户".equals(value)) {
            return TYPE_PERSONAL;
        }
        if ("2".equals(value) || "企业".equals(value) || "企业客户".equals(value)) {
            return TYPE_ENTERPRISE;
        }
        return null;
    }

    /**
     * 生成导入客户的编号：V + 26 位随机串。
     *
     * <p>和线上访客编号**形状一致**（不可枚举），但来源不同：访客是安全随机，
     * 这里是批量导入，撞了就重试——反正有唯一索引兜底。</p>
     */
    private String nextCustomerNo(String tenant) {
        for (int attempt = 0; attempt < 8; attempt++) {
            StringBuilder sb = new StringBuilder(27);
            sb.append('V');
            for (int i = 0; i < CUSTOMER_NO_LENGTH; i++) {
                sb.append(CUSTOMER_NO_ALPHABET.charAt(RANDOM.nextInt(CUSTOMER_NO_ALPHABET.length())));
            }
            String no = sb.toString();
            Long exists = customerMapper.selectCount(Wrappers.<Customer>lambdaQuery()
                    .eq(Customer::getTenantCode, tenant)
                    .eq(Customer::getCustomerNo, no));
            if (exists == null || exists == 0) {
                return no;
            }
        }
        throw new BizException(50001, "客户编号生成失败，请重试");
    }

    /**
     * 标签体系：按分组排开，带每个标签当前打了多少客户。
     *
     * <p>任何企业成员都能看（坐席要知道有哪些标签可打），但只有管理员能改。</p>
     */
    public List<TagDefVO> tagDefs(LoginUser user) {
        String tenant = tenantOf(user);
        List<TagDefVO> result = new ArrayList<>();
        for (Map<String, Object> row : tagDefMapper.selectTagUsage(tenant)) {
            result.add(new TagDefVO(
                    String.valueOf(longValue(row.get("id"))),
                    stringValue(row.get("tag_code")),
                    stringValue(row.get("tag_name")),
                    stringValue(row.get("tag_group")),
                    stringValue(row.get("color")),
                    intValue(row.get("tag_type")),
                    tagTypeName(intValue(row.get("tag_type"))),
                    stringValue(row.get("rule_hint")),
                    stringValue(row.get("rule_metric")),
                    CustomerTagRuleService.metricName(stringValue(row.get("rule_metric"))),
                    stringValue(row.get("rule_op")),
                    decimalValue(row.get("rule_value")),
                    intValue(row.get("rule_window_days")),
                    ruleTextOf(row),
                    stringValue(row.get("description")),
                    intValue(row.get("sort_no")),
                    Boolean.TRUE.equals(boolValue(row.get("is_enabled"))),
                    intValue(row.get("customer_count")),
                    timeValue(row.get("create_time"))));
        }
        return result;
    }

    /** 从查询行里拼出规则的人话描述（没配指标就返回 null） */
    private static String ruleTextOf(Map<String, Object> row) {
        if (stringValue(row.get("rule_metric")) == null || stringValue(row.get("rule_op")) == null) {
            return null;
        }
        CustomerTagDef def = new CustomerTagDef();
        def.setRuleMetric(stringValue(row.get("rule_metric")));
        def.setRuleOp(stringValue(row.get("rule_op")));
        def.setRuleValue(decimalValue(row.get("rule_value")));
        def.setRuleWindowDays(intValue(row.get("rule_window_days")));
        return CustomerTagRuleService.ruleText(def);
    }

    /** 新建标签定义（仅企业管理员） */
    @Transactional
    public TagDefVO createTag(LoginUser user, TagDefBody body) {
        String tenant = tenantOf(user);
        requireManage(user);
        String name = requireText(body.tagName(), "标签名不能为空", 32);
        String group = normalizeGroup(body.tagGroup());
        Long exists = tagDefMapper.selectCount(Wrappers.<CustomerTagDef>lambdaQuery()
                .eq(CustomerTagDef::getTenantCode, tenant)
                .eq(CustomerTagDef::getTagName, name)
                .eq(CustomerTagDef::getDeleted, false));
        if (exists != null && exists > 0) {
            throw new BizException(40001, "标签「" + name + "」已经存在了");
        }
        LocalDateTime now = LocalDateTime.now();
        String code = body.tagCode() == null || body.tagCode().isBlank()
                ? nextTagCode(tenant) : requireText(body.tagCode(), "标签编码不合法", 32);
        CustomerTagDef def = CustomerTagDef.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .tagCode(code)
                .tagName(name)
                .tagGroup(group)
                .color(normalizeColor(body.color()))
                .tagType(body.tagType() == null ? 1 : (body.tagType() == 2 ? 2 : 1))
                .ruleHint(trim255(body.ruleHint()))
                .ruleMetric(blankToNull(body.ruleMetric()))
                .ruleOp(normalizeOp(body.ruleOp()))
                .ruleValue(body.ruleValue())
                .ruleWindowDays(body.ruleWindowDays() == null ? 0
                        : Math.max(0, Math.min(3650, body.ruleWindowDays())))
                .description(trim255(body.description()))
                .sortNo(body.sortNo() == null ? 100 : body.sortNo())
                .isEnabled(body.enabled() == null || body.enabled())
                .createTime(now)
                .updateTime(now)
                .creator(String.valueOf(user.userId()))
                .deleted(false)
                .build();
        tagDefMapper.insert(def);
        log.info("新建客户标签 tenant={} code={} name={} group={} operator={}",
                tenant, code, name, group, user.name());
        return toTagDefVO(def, 0);
    }

    /** 改标签定义：名字 / 分组 / 颜色 / 类型 / 口径 / 排序 / 启用 */
    @Transactional
    public TagDefVO updateTag(LoginUser user, Long id, TagDefBody body) {
        String tenant = tenantOf(user);
        requireManage(user);
        CustomerTagDef def = requireTagDef(tenant, id);
        CustomerTagDef update = new CustomerTagDef();
        update.setId(def.getId());
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(LocalDateTime.now());
        String oldName = def.getTagName();
        if (body.tagName() != null && !body.tagName().isBlank() && !body.tagName().trim().equals(oldName)) {
            String name = requireText(body.tagName(), "标签名不能为空", 32);
            Long exists = tagDefMapper.selectCount(Wrappers.<CustomerTagDef>lambdaQuery()
                    .eq(CustomerTagDef::getTenantCode, tenant)
                    .eq(CustomerTagDef::getTagName, name)
                    .eq(CustomerTagDef::getDeleted, false)
                    .ne(CustomerTagDef::getId, def.getId()));
            if (exists != null && exists > 0) {
                throw new BizException(40001, "标签「" + name + "」已经存在了");
            }
            update.setTagName(name);
            // 关联表里存了标签名的冗余副本，改名要一起同步，否则客户列表上显示的还是老名字
            CustomerTag rename = new CustomerTag();
            rename.setTagName(name);
            customerTagMapper.update(rename, Wrappers.<CustomerTag>lambdaUpdate()
                    .eq(CustomerTag::getTenantCode, tenant)
                    .eq(CustomerTag::getTagId, def.getId())
                    .eq(CustomerTag::getDeleted, false));
        }
        if (body.tagGroup() != null && !body.tagGroup().isBlank()) {
            update.setTagGroup(normalizeGroup(body.tagGroup()));
        }
        if (body.color() != null && !body.color().isBlank()) {
            update.setColor(normalizeColor(body.color()));
        }
        if (body.tagType() != null) {
            update.setTagType(body.tagType() == 2 ? 2 : 1);
        }
        if (body.ruleHint() != null) {
            update.setRuleHint(trim255(body.ruleHint()));
        }
        if (body.ruleMetric() != null) {
            update.setRuleMetric(blankToNull(body.ruleMetric()));
        }
        if (body.ruleOp() != null) {
            update.setRuleOp(normalizeOp(body.ruleOp()));
        }
        if (body.ruleValue() != null) {
            update.setRuleValue(body.ruleValue());
        }
        if (body.ruleWindowDays() != null) {
            update.setRuleWindowDays(Math.max(0, Math.min(3650, body.ruleWindowDays())));
        }
        if (body.description() != null) {
            update.setDescription(trim255(body.description()));
        }
        if (body.sortNo() != null) {
            update.setSortNo(body.sortNo());
        }
        if (body.enabled() != null) {
            update.setIsEnabled(body.enabled());
        }
        tagDefMapper.updateById(update);
        CustomerTagDef latest = tagDefMapper.selectById(def.getId());
        log.info("修改客户标签 tenant={} id={} {} → {} operator={}",
                tenant, id, oldName, latest.getTagName(), user.name());
        return toTagDefVO(latest, tagCountOf(tenant, id));
    }

    /** 启用 / 停用标签：停用后不能再打标，已经打上的标签仍然展示 */
    @Transactional
    public TagDefVO toggleTag(LoginUser user, Long id, boolean enabled) {
        String tenant = tenantOf(user);
        requireManage(user);
        CustomerTagDef def = requireTagDef(tenant, id);
        CustomerTagDef update = new CustomerTagDef();
        update.setId(def.getId());
        update.setIsEnabled(enabled);
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(LocalDateTime.now());
        tagDefMapper.updateById(update);
        log.info("{}客户标签 tenant={} id={} name={} operator={}",
                enabled ? "启用" : "停用", tenant, id, def.getTagName(), user.name());
        return toTagDefVO(tagDefMapper.selectById(def.getId()), tagCountOf(tenant, id));
    }

    /**
     * 删除标签定义（软删）。
     *
     * <p>还有人打着这个标签时**拒绝删除**，而不是连打标记录一起清掉：
     * "高价值客户"这种标签一旦悄悄从 30 个人身上消失，运营的报表口径就断了。
     * 要删先把客户身上的标签摘掉，或者直接停用（停用不影响存量展示）。</p>
     */
    @Transactional
    public void deleteTag(LoginUser user, Long id) {
        String tenant = tenantOf(user);
        requireManage(user);
        CustomerTagDef def = requireTagDef(tenant, id);
        int used = tagCountOf(tenant, id);
        if (used > 0) {
            throw new BizException(40001, "还有 " + used + " 个客户打着「" + def.getTagName()
                    + "」，先把标签摘掉，或者改成停用");
        }
        CustomerTagDef update = new CustomerTagDef();
        update.setId(def.getId());
        update.setDeleted(true);
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(LocalDateTime.now());
        tagDefMapper.updateById(update);
        log.info("删除客户标签 tenant={} id={} name={} operator={}",
                tenant, id, def.getTagName(), user.name());
    }

    // ------------------------------------------------------------------ 权限

    /**
     * 当前登录人在客户 360 里能做什么。
     *
     * <p>口径与工单中心一致：</p>
     * <ul>
     *   <li>企业管理员 / 客服主管 / 高级客服 / 客服专员 → 打标、去标、改档案；</li>
     *   <li>质检专员 / AI运营这类只读角色 → 只能看（他们不需要给客户打标）；</li>
     *   <li>标签体系（新建 / 修改 / 停用 / 删除）→ 仅企业管理员。</li>
     * </ul>
     *
     * <p>降级策略：用户服务问不到角色时，日常作业照常放行，管理类动作保守拒绝。</p>
     */
    public AccessVO access(LoginUser user) {
        String tenant = tenantOf(user);
        List<String> roles = userRoleClient.rolesOf(user.userId(), tenant);
        if (roles == null) {
            return new AccessVO(null, "未知（用户服务不可用）", true, false, false,
                    "暂时读不到你的角色，标签体系维护已被临时收紧");
        }
        if (roles.isEmpty()) {
            return new AccessVO("ADMIN", "企业管理员", true, true, true,
                    "该租户还没有角色数据，按企业管理员兼容");
        }
        // 角色集合是一个"不可变 Set"（Set.of），contains(null) 会抛 NPE——先把 null 滤掉，
        // 免得用户服务哪天返回一个 null 角色码，把整个客户 360 打成 500
        List<String> clean = roles.stream().filter(java.util.Objects::nonNull).toList();
        if (clean.isEmpty()) {
            return new AccessVO(null, "未知（角色数据无效）", true, false, false,
                    "角色数据暂不可用，管理类操作已临时收紧");
        }
        String primary = primaryRole(clean);
        boolean readOnly = clean.stream().allMatch(READ_ONLY_ROLES::contains);
        boolean admin = clean.stream().anyMatch(ADMIN_ROLES::contains);
        boolean supervisor = clean.stream().anyMatch(SUPERVISOR_ROLES::contains);
        String hint = readOnly
                ? "当前角色是只读角色，只能查看客户资料（打标 / 改档案请找客服同事）"
                : (admin ? "可以维护标签体系、合并与删除客户，也能看完整手机号"
                        : (supervisor ? "可以打标与维护客户档案，也能看完整手机号（会留痕）"
                                : "可以打标与维护客户档案；标签体系与删除由企业管理员维护"));
        return new AccessVO(primary, ROLE_NAMES.getOrDefault(primary, primary), !readOnly, admin,
                supervisor || admin, hint);
    }

    private String primaryRole(List<String> roles) {
        for (String candidate : List.of("ADMIN", "SUPERVISOR", "SENIOR_AGENT", "AGENT", "QUALITY", "AI_OPERATOR")) {
            if (roles.contains(candidate)) {
                return candidate;
            }
        }
        return roles.get(0);
    }

    /** 处理类动作（打标 / 去标 / 改档案）的权限校验 */
    private void requireOperate(LoginUser user) {
        AccessVO access = access(user);
        if (!access.canOperate()) {
            throw new BizException(40302, "当前角色（" + access.roleName() + "）只能查看客户资料，不能修改；"
                    + "需要打标或改档案请找客服同事");
        }
    }

    /** 管理类动作（标签体系维护）的权限校验 */
    private void requireManage(LoginUser user) {
        AccessVO access = access(user);
        if (!access.canManageTag()) {
            throw new BizException(40302, "标签体系只有企业管理员能维护（当前角色：" + access.roleName() + "）");
        }
    }

    /**
     * 敏感字段（完整手机号）的查看权限：企业管理员 + 客服主管。
     *
     * <p>为什么不含一线客服：手机号是个人敏感信息，一线坐席接待时并不需要完整号码
     * （回访、寄件这些场景才需要）；要看得找主管，而且**看一眼就会在客户动态里留痕**。</p>
     */
    private void requireSupervisor(LoginUser user) {
        AccessVO access = access(user);
        if (!access.canSeePhone()) {
            throw new BizException(40302, "完整手机号只有企业管理员 / 客服主管能看（当前角色："
                    + access.roleName() + "），且每次查看都会留下记录");
        }
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2) {
            throw new BizException(40301, "仅企业成员可使用客户 360");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用客户 360");
        }
        return tenant;
    }

    // ------------------------------------------------------------------ 组装与工具

    private Customer requireCustomer(String tenant, String customerNo) {
        if (customerNo == null || customerNo.isBlank()) {
            throw new BizException(40001, "缺少客户编号");
        }
        Customer customer = customerMapper.selectOne(Wrappers.<Customer>lambdaQuery()
                .eq(Customer::getTenantCode, tenant)
                .eq(Customer::getCustomerNo, customerNo.trim())
                .eq(Customer::getDeleted, false)
                .last("LIMIT 1"));
        if (customer == null) {
            throw new BizException(40401, "客户不存在");
        }
        return customer;
    }

    private CustomerTagDef requireTagDef(String tenant, Long id) {
        CustomerTagDef def = id == null ? null : tagDefMapper.selectById(id);
        if (def == null || Boolean.TRUE.equals(def.getDeleted()) || !tenant.equals(def.getTenantCode())) {
            throw new BizException(40401, "标签不存在");
        }
        return def;
    }

    private int tagCountOf(String tenant, Long tagId) {
        Long count = customerTagMapper.selectCount(Wrappers.<CustomerTag>lambdaQuery()
                .eq(CustomerTag::getTenantCode, tenant)
                .eq(CustomerTag::getTagId, tagId)
                .eq(CustomerTag::getDeleted, false));
        return count == null ? 0 : count.intValue();
    }

    private TagDefVO toTagDefVO(CustomerTagDef def, int customerCount) {
        return new TagDefVO(
                String.valueOf(def.getId()),
                def.getTagCode(),
                def.getTagName(),
                def.getTagGroup(),
                def.getColor(),
                def.getTagType(),
                tagTypeName(def.getTagType()),
                def.getRuleHint(),
                def.getRuleMetric(),
                CustomerTagRuleService.metricName(def.getRuleMetric()),
                def.getRuleOp(),
                def.getRuleValue(),
                def.getRuleWindowDays(),
                def.getRuleMetric() == null ? null : CustomerTagRuleService.ruleText(def),
                def.getDescription(),
                def.getSortNo(),
                Boolean.TRUE.equals(def.getIsEnabled()),
                customerCount,
                def.getCreateTime());
    }

    private CustomerVO toVO(Customer row, List<TagVO> tags,
                            Map<String, Object> stats, Map<String, Object> latest) {
        Integer sessionCount = row.getSessionCount();
        Integer humanCount = null;
        LocalDateTime lastSessionAt = row.getLastSessionAt();
        if (stats != null) {
            sessionCount = intValue(stats.get("session_count"));
            humanCount = intValue(stats.get("human_count"));
            lastSessionAt = timeValue(stats.get("last_session_at"));
        }
        String lastIntent = latest == null ? null : stringValue(latest.get("intent"));
        String lastEmotion = latest == null ? null : stringValue(latest.get("emotion"));
        String lastSessionNo = latest == null ? null : stringValue(latest.get("session_no"));
        return new CustomerVO(
                row.getCustomerNo(),
                row.getName(),
                maskPhone(row.getPhone()),
                row.getLevel(),
                levelName(row.getLevel()),
                row.getCustomerType(),
                customerTypeName(row.getCustomerType()),
                row.getRiskLevel(),
                riskName(row.getRiskLevel()),
                row.getChannel(),
                sessionCount,
                humanCount,
                row.getTicketCount(),
                row.getCsat(),
                sentimentName(row.getSentiment()),
                lastIntent,
                lastEmotion,
                lastSessionNo,
                lastSessionAt,
                row.getLastActive(),
                row.getRemark(),
                tags,
                row.getAnonymizedAt(),
                row.getMergedInto(),
                row.getCreateTime());
    }

    /** 写一条客户动态（打标、改等级这类"人对客户做的事"，全都要留痕） */
    private void appendEvent(String tenant, Long customerId, int eventType, String title,
                             String content, Integer refType, String refNo, LoginUser user) {
        dynamics.record(tenant, customerId, eventType, title, content, refType, refNo,
                user == null ? null : user.userId(), user == null ? null : user.name());
    }

    private String nextTagCode(String tenant) {
        return "TAG" + LocalDateTime.now().format(NO_TIME) + String.format("%02d", idGenerator.nextId() % 100);
    }

    private String normalizeGroup(String group) {
        if (group == null || group.isBlank()) {
            return "其它";
        }
        String value = group.trim();
        if (!TAG_GROUPS.contains(value)) {
            throw new BizException(40001, "标签分组只能是：" + String.join(" / ", TAG_GROUPS));
        }
        return value;
    }

    private String normalizeColor(String color) {
        if (color == null || color.isBlank()) {
            return "blue";
        }
        String value = color.trim().toLowerCase();
        if (!TAG_COLORS.contains(value)) {
            throw new BizException(40001, "标签颜色只能是：" + String.join(" / ", TAG_COLORS));
        }
        return value;
    }

    /**
     * 规则比较符白名单。
     *
     * <p>空字符串要落成 null：`rule_op` 上有一条"必须属于 GT/GTE/LT/LTE/EQ"的 CHECK，
     * 空串会被它挡住——手工标签根本不带比较符，这里必须给 null。</p>
     */
    private String normalizeOp(String op) {
        String value = blankToNull(op);
        if (value == null) {
            return null;
        }
        String upper = value.toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("GT", "GTE", "LT", "LTE", "EQ").contains(upper)) {
            throw new BizException(40001, "比较符只能是 GT / GTE / LT / LTE / EQ（大于 / 大于等于 / 小于 / 小于等于 / 等于）");
        }
        return upper;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 规则指标下拉（前端规则编辑器用） */
    public List<MetricOptionVO> metricOptions() {
        List<MetricOptionVO> options = new ArrayList<>();
        CustomerTagRuleService.metricNames()
                .forEach((value, label) -> options.add(new MetricOptionVO(value, label)));
        options.sort(java.util.Comparator.comparing(MetricOptionVO::label));
        return options;
    }

    /** 手动重算某个客户的规则标签 */
    public CustomerTagRuleService.ApplyResult recalculateTags(LoginUser user, String customerNo) {
        String tenant = tenantOf(user);
        requireOperate(user);
        Customer customer = requireCustomer(tenant, customerNo);
        return tagRuleService.recalculateForCustomer(tenant, customer.getId(), user.name());
    }

    /** 手动全量重算（仅企业管理员） */
    public CustomerTagRuleService.BatchResult recalculateAllTags(LoginUser user) {
        String tenant = tenantOf(user);
        requireManage(user);
        return tagRuleService.recalculateTenant(user, tenant);
    }

    private String requireText(String value, String message, int max) {
        if (value == null || value.isBlank()) {
            throw new BizException(40001, message);
        }
        String text = value.trim();
        if (text.length() > max) {
            throw new BizException(40001, "内容太长了，最多 " + max + " 个字符");
        }
        return text;
    }

    private static String trim255(String value) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return null;
        }
        return text.length() > 255 ? text.substring(0, 255) : text;
    }

    /** 手机号脱敏：坐席要看得到"是不是同一个号"，但不该把完整号码摆在列表上 */
    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static String levelName(Integer level) {
        return level == null ? "普通会员" : LEVEL_NAMES.getOrDefault(level, "普通会员");
    }

    private static String customerTypeName(Integer type) {
        return type == null ? "个人客户" : CUSTOMER_TYPE_NAMES.getOrDefault(type, "个人客户");
    }

    private static String riskName(Integer risk) {
        return risk == null ? "正常" : RISK_NAMES.getOrDefault(risk, "正常");
    }

    private static String tagTypeName(Integer type) {
        return type != null && type == 2 ? "规则自动" : "手工打标";
    }

    private static String sentimentName(Integer sentiment) {
        if (sentiment == null) {
            return null;
        }
        return switch (sentiment) {
            case 1 -> "正面";
            case 2 -> "负面";
            default -> "中性";
        };
    }

    private static String orderStatusName(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case 1 -> "待付款";
            case 2 -> "已付款";
            case 3 -> "已发货";
            case 4 -> "已完成";
            case 5 -> "已退款";
            default -> "未知";
        };
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

    private static Boolean boolValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static BigDecimal decimalValue(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
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

    /** 会话时长（分钟）：没结束的会话算到"现在"，这样正在进行的会话也有时长 */
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

    private static String eventTypeName(Integer type) {
        if (type == null) {
            return "动态";
        }
        return switch (type) {
            case CustomerDynamics.CREATE -> "建档";
            case CustomerDynamics.TAG_ADD -> "打标";
            case CustomerDynamics.TAG_REMOVE -> "去标";
            case CustomerDynamics.LEVEL -> "等级调整";
            case CustomerDynamics.REMARK -> "备注更新";
            case CustomerDynamics.RISK -> "风险标记";
            case CustomerDynamics.SESSION_START -> "发起会话";
            case CustomerDynamics.SESSION_END -> "会话结束";
            case CustomerDynamics.HUMAN_TRANSFER -> "转人工";
            case CustomerDynamics.TICKET_CREATE -> "转工单";
            case CustomerDynamics.CSAT -> "满意度评价";
            case CustomerDynamics.PII_VIEW -> "敏感信息查阅";
            case CustomerDynamics.ORDER -> "订单变更";
            case CustomerDynamics.MERGE -> "客户合并";
            case CustomerDynamics.ANONYMIZE -> "客户匿名化";
            case CustomerDynamics.DELETE -> "客户删除";
            default -> "动态";
        };
    }

    // ------------------------------------------------------------------ 出入参

    /** 客户列表查询条件 */
    public record CustomerQuery(
            String keyword,
            Integer level,
            Integer riskLevel,
            /** 客户类型：1-个人、2-企业（不传=全部） */
            Integer customerType,
            Long tagId,
            String channel,
            Integer activeDays,
            Boolean hasTicket,
            String sort) {

        public static CustomerQuery empty() {
            return new CustomerQuery(null, null, null, null, null, null, null, null, null);
        }
    }

    /** 列表行（同时也是导出的一行） */
    public record CustomerVO(
            String customerNo,
            String name,
            String phone,
            Integer level,
            String levelText,
            Integer customerType,
            String customerTypeText,
            Integer riskLevel,
            String riskText,
            String channel,
            Integer sessionCount,
            Integer humanCount,
            Integer ticketCount,
            BigDecimal csat,
            String sentimentText,
            String lastIntent,
            String lastEmotion,
            String lastSessionNo,
            LocalDateTime lastSessionAt,
            LocalDateTime lastActive,
            String remark,
            List<TagVO> tags,
            /** 匿名化时间：非空表示这个人已经被抹掉 PII */
            LocalDateTime anonymizedAt,
            /** 被合并到哪个客户编号：非空表示这条是重复档案 */
            String mergedInto,
            LocalDateTime createTime) {
    }

    /** 客户身上的一个标签 */
    public record TagVO(
            String tagId,
            String tagName,
            String tagGroup,
            String color,
            Integer tagType,
            Integer source,
            String sourceText,
            String operatorName,
            LocalDateTime createTime) {
    }

    /** 会话轨迹的一行 */
    public record SessionTraceVO(
            String sessionNo,
            Integer status,
            String statusText,
            String channelName,
            String source,
            String agentId,
            String intent,
            String emotion,
            String botTransferReason,
            Integer msgCount,
            Integer csatScore,
            String ticketNo,
            Integer ticketStatus,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Integer durationMinutes) {
    }

    /** 客户动态的一行 */
    public record EventVO(
            String id,
            Integer eventType,
            String eventTypeText,
            String title,
            String content,
            String operatorName,
            LocalDateTime eventTime) {
    }

    /** 关联工单（画像里的"之前提过什么"） */
    public record TicketBriefVO(
            String ticketNo,
            String title,
            String statusText,
            Integer priority,
            String priorityText,
            String assigneeName,
            String sessionNo,
            LocalDateTime createTime) {
    }

    /** 标签体系的一行（含使用量） */
    public record TagDefVO(
            String id,
            String tagCode,
            String tagName,
            String tagGroup,
            String color,
            Integer tagType,
            String tagTypeText,
            String ruleHint,
            String ruleMetric,
            String ruleMetricText,
            String ruleOp,
            BigDecimal ruleValue,
            Integer ruleWindowDays,
            /** 规则的人话描述：累计会话数 ≥ 5（全周期） */
            String ruleText,
            String description,
            Integer sortNo,
            Boolean enabled,
            Integer customerCount,
            LocalDateTime createTime) {
    }

    /** 列表分页 */
    public record CustomerPageVO(long total, int page, int pageSize, List<CustomerVO> list) {
    }

    /** 画像：客户 + 关联工单（本来还有"最近订单"，因为订单属于交易系统、客服侧拿不到可靠数据，已移除） */
    public record CustomerDetailVO(CustomerVO customer, List<TicketBriefVO> tickets) {
    }

    /** 客户经营概览 */
    public record OverviewVO(int total, int monthNew, int active7d, int riskCount,
                             int vipCount, int taggedCount) {
    }

    /** 当前登录人的客户 360 权限 */
    public record AccessVO(String roleCode, String roleName, boolean canOperate, boolean canManageTag,
                           boolean canSeePhone, String hint) {
    }

    /** 客户合并结果 */
    public record MergeResult(String sourceCustomerNo, String targetCustomerNo,
                              int sessions, int tickets, int tags) {
    }

    /** 完整手机号（查看会留痕，所以返回的是"这次看到的那串号码"） */
    public record PhoneVO(String phone, String masked) {
    }

    /** 批量打标结果 */
    public record BatchTagResult(int requested, int tagged, int skipped, List<String> missing) {
    }

    /** 导入结果：逐行容错，所以失败明细要带行号 */
    public record ImportResult(int total, int created, int updated, int tagged, List<ImportFailure> failures) {
    }

    /** 导入失败明细 */
    public record ImportFailure(int line, String reason) {
    }

    /** 批量打标入参 */
    public record BatchTagBody(List<String> customerNos, Long tagId, String remark) {
    }

    /** 打标入参 */
    public record TagAssignBody(Long tagId, String remark) {
    }

    /** 改档案入参（null = 不改这一项） */
    public record ProfileBody(String name, String phone, Integer level, Integer customerType,
                              Integer riskLevel, String remark) {
    }

    /**
     * 标签定义入参（新建 / 修改共用）。
     *
     * <p>规则标签要同时给"写给人看的说明"（ruleHint）和"给引擎跑的四件套"
     * （ruleMetric / ruleOp / ruleValue / ruleWindowDays）：前者是文档，后者才是逻辑。</p>
     */
    public record TagDefBody(String tagCode, String tagName, String tagGroup, String color,
                             Integer tagType, String ruleHint, String ruleMetric, String ruleOp,
                             BigDecimal ruleValue, Integer ruleWindowDays, String description,
                             Integer sortNo, Boolean enabled) {
    }

    /** 规则标签清单（前端规则编辑器与"重算"结果展示用） */
    public record TagRuleVO(String tagCode, String tagName, String metric, String metricText,
                            String op, String opText, BigDecimal value, int windowDays, String text) {
    }

    /** 指标下拉项 */
    public record MetricOptionVO(String value, String label) {
    }
}
