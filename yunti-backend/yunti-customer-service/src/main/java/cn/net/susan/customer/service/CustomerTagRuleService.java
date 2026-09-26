package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.Customer;
import cn.net.susan.customer.entity.CustomerTag;
import cn.net.susan.customer.entity.CustomerTagDef;
import cn.net.susan.customer.mapper.CustomerMapper;
import cn.net.susan.customer.mapper.CustomerTagDefMapper;
import cn.net.susan.customer.mapper.CustomerTagMapper;
import cn.net.susan.customer.mapper.SessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标签自动化：让"规则标签"真的自己跑起来。
 *
 * <p>上一篇里 `customer_tag_def` 只有一句文字说明（rule_hint），标签到底怎么命中全靠人脑记；
 * 这一篇把它做成了可执行的规则：**指标 + 比较符 + 阈值 + 统计窗口**。</p>
 *
 * <p>指标分两类，语义要分清（这也是最容易做错的地方）：</p>
 * <ul>
 *   <li><b>客户档案指标</b>（满意度、会员等级、风险等级、累计会话、累计工单、距最近活跃天数）——
 *       取 customer 表上的当前值，**全周期**，窗口字段对它没意义；</li>
 *   <li><b>会话行为指标</b>（退款类会话、投诉类会话、夜间咨询）——按**统计窗口**在 session 表上现算，
 *       因为"近 30 天投诉两次"和"三年前投诉过两次"是两种客户：不设窗口，标签打上去就永远摘不掉。</li>
 * </ul>
 *
 * <p>命中会打标，<b>不再命中会自动摘掉</b>（只摘规则自动打的，source=2；人手工打的标签一根汗毛都不动）。
 * 触发方式有三种：会话结束 / 建单 / 订单变更时即时算一次、每 10 分钟的定时扫、页面上手动重算。</p>
 */
@Service
public class CustomerTagRuleService {

    private static final Logger log = LoggerFactory.getLogger(CustomerTagRuleService.class);

    /** 规则来源码：规则自动 */
    private static final int SOURCE_RULE = 2;
    /** 规则引擎在动态里的署名 */
    private static final String RULE_OPERATOR = "规则引擎";

    /** 定时扫描：一次最多看多少个"最近活跃"的客户（够覆盖真实流量，又不至于把库压住） */
    private static final int SCHEDULE_BATCH = 200;
    /** 手动全量重算的上限 */
    private static final int FULL_BATCH = 2000;

    /** 指标 → 中文名（前端与动态文案都用它） */
    private static final Map<String, String> METRIC_NAMES = Map.ofEntries(
            Map.entry("CSAT", "满意度"),
            Map.entry("LEVEL", "会员等级"),
            Map.entry("RISK_LEVEL", "风险等级"),
            Map.entry("SESSIONS", "累计会话数"),
            Map.entry("TICKETS", "累计工单数"),
            Map.entry("REFUND_SESSIONS", "退款类会话数"),
            Map.entry("COMPLAINT_SESSIONS", "投诉类会话数"),
            Map.entry("NIGHT_SESSIONS", "夜间咨询次数"),
            Map.entry("ACTIVE_DAYS", "距最近活跃天数"));

    private static final Map<String, String> OP_NAMES = Map.of(
            "GT", ">", "GTE", "≥", "LT", "<", "LTE", "≤", "EQ", "=");

    /** 会话行为类指标：需要在 session 表上按窗口现算 */
    private static final Set<String> SESSION_METRICS =
            Set.of("REFUND_SESSIONS", "COMPLAINT_SESSIONS", "NIGHT_SESSIONS");

    private final CustomerMapper customerMapper;
    private final CustomerTagMapper customerTagMapper;
    private final CustomerTagDefMapper tagDefMapper;
    private final SessionMapper sessionMapper;
    private final CustomerDynamics dynamics;
    private final SnowflakeIdGenerator idGenerator;

    public CustomerTagRuleService(CustomerMapper customerMapper,
                                  CustomerTagMapper customerTagMapper,
                                  CustomerTagDefMapper tagDefMapper,
                                  SessionMapper sessionMapper,
                                  CustomerDynamics dynamics,
                                  SnowflakeIdGenerator idGenerator) {
        this.customerMapper = customerMapper;
        this.customerTagMapper = customerTagMapper;
        this.tagDefMapper = tagDefMapper;
        this.sessionMapper = sessionMapper;
        this.dynamics = dynamics;
        this.idGenerator = idGenerator;
    }

    // ------------------------------------------------------------------ 对外：重算

    /**
     * 单个客户重算（页面按钮 / 会话结束 / 建单 / 订单变更后即时触发）。
     *
     * @param operatorName 手动触发时的操作人（定时与事件触发的传 null → 记成"规则引擎"）
     */
    @Transactional
    public ApplyResult recalculateForCustomer(String tenantCode, Long customerId, String operatorName) {
        Customer customer = customerMapper.selectById(customerId);
        if (customer == null || Boolean.TRUE.equals(customer.getDeleted())
                || !tenantCode.equals(customer.getTenantCode())) {
            return new ApplyResult(0, 0, 0);
        }
        List<CustomerTagDef> rules = runnableRules(tenantCode);
        if (rules.isEmpty()) {
            return new ApplyResult(0, 0, 0);
        }
        Set<Long> hit = evaluate(rules, tenantCode, customer);
        return apply(tenantCode, customer, rules, hit, operatorName);
    }

    /**
     * 手动全量重算（标签体系页的「重算全部客户」，仅企业管理员）。
     *
     * <p>为什么要有它：改了规则的阈值之后，存量客户不会自己回到新口径，
     * 得有人按一次"全量重算"——这也是运营最容易忘记的一步。</p>
     */
    @Transactional
    public BatchResult recalculateTenant(LoginUser user, String tenantCode) {
        List<CustomerTagDef> rules = runnableRules(tenantCode);
        if (rules.isEmpty()) {
            throw new BizException(40001, "还没有配置可执行的规则标签（指标 + 比较符 + 阈值），先去标签体系里配一个");
        }
        List<Customer> targets = customerMapper.selectList(Wrappers.<Customer>lambdaQuery()
                .eq(Customer::getTenantCode, tenantCode)
                .eq(Customer::getDeleted, false)
                .orderByDesc(Customer::getLastActive)
                .last("LIMIT " + FULL_BATCH));
        int scanned = 0;
        int tagged = 0;
        int untagged = 0;
        for (Customer customer : targets) {
            scanned++;
            Set<Long> hit = evaluate(rules, tenantCode, customer);
            ApplyResult result = apply(tenantCode, customer, rules, hit, user == null ? null : user.name());
            tagged += result.tagged();
            untagged += result.untagged();
        }
        log.info("规则标签全量重算 tenant={} 扫描={} 打标={} 去标={} operator={}",
                tenantCode, scanned, tagged, untagged, user == null ? "SYSTEM" : user.name());
        return new BatchResult(scanned, tagged, untagged);
    }

    /**
     * 每 10 分钟扫一遍：只扫"配了规则标签的租户"里最近活跃的一批客户。
     *
     * <p>为什么扫"最近活跃"而不是全量：规则标签几乎全是近期行为，
     * 三个月没来过的客户不会因为今天的对话改变标签；全量扫反而把库压住。</p>
     */
    @Scheduled(initialDelay = 120_000, fixedDelay = 600_000)
    public void scheduledScan() {
        List<String> tenants;
        try {
            tenants = tagDefMapper.selectTenantsWithRunnableRules();
        } catch (Exception e) {
            log.warn("规则标签定时扫描跳过：读不到租户（{}）", e.getMessage());
            return;
        }
        for (String tenant : tenants) {
            List<CustomerTagDef> rules = runnableRules(tenant);
            if (rules.isEmpty()) {
                continue;
            }
            List<Customer> targets = customerMapper.selectList(Wrappers.<Customer>lambdaQuery()
                    .eq(Customer::getTenantCode, tenant)
                    .eq(Customer::getDeleted, false)
                    .orderByDesc(Customer::getLastActive)
                    .last("LIMIT " + SCHEDULE_BATCH));
            int changed = 0;
            for (Customer customer : targets) {
                try {
                    Set<Long> hit = evaluate(rules, tenant, customer);
                    ApplyResult result = apply(tenant, customer, rules, hit, null);
                    changed += result.tagged() + result.untagged();
                } catch (Exception e) {
                    log.warn("规则标签重算失败 tenant={} customerId={} error={}",
                            tenant, customer.getId(), e.getMessage());
                }
            }
            if (changed > 0) {
                log.info("规则标签定时扫描 tenant={} 扫描={} 标签变化={}", tenant, targets.size(), changed);
            }
        }
    }

    // ------------------------------------------------------------------ 求值

    /** 这个租户所有"能跑"的规则标签（启用 + 未删 + 指标/比较符/阈值齐全） */
    public List<CustomerTagDef> runnableRules(String tenantCode) {
        return tagDefMapper.selectList(Wrappers.<CustomerTagDef>lambdaQuery()
                .eq(CustomerTagDef::getTenantCode, tenantCode)
                .eq(CustomerTagDef::getDeleted, false)
                .eq(CustomerTagDef::getIsEnabled, true)
                .eq(CustomerTagDef::getTagType, 2)
                .isNotNull(CustomerTagDef::getRuleMetric)
                .isNotNull(CustomerTagDef::getRuleOp)
                .isNotNull(CustomerTagDef::getRuleValue)
                .orderByAsc(CustomerTagDef::getSortNo));
    }

    /**
     * 求值：哪些规则标签命中这个客户。
     *
     * <p>会话类指标按"同窗口合并成一次查询"算：一个客户可能配了三条近 30 天的会话规则，
     * 没必要查三遍。</p>
     */
    public Set<Long> evaluate(List<CustomerTagDef> rules, String tenantCode, Customer customer) {
        Set<Long> hit = new HashSet<>();
        if (rules == null || rules.isEmpty()) {
            return hit;
        }
        Map<Integer, Map<String, Object>> sessionCache = new HashMap<>();
        for (CustomerTagDef rule : rules) {
            String metric = rule.getRuleMetric();
            BigDecimal value;
            // metric 为 null 的是手工标签（不参与求值）；Set.of 是不可变集合，contains(null) 会抛 NPE
            if (metric != null && SESSION_METRICS.contains(metric)) {
                int window = rule.getRuleWindowDays() == null ? 0 : rule.getRuleWindowDays();
                Map<String, Object> row = sessionCache.computeIfAbsent(window, key ->
                        sessionMapper.selectCustomerRuleMetrics(tenantCode, customer.getId(), key));
                value = metricValue(row, metric);
            } else {
                value = profileMetric(customer, metric);
            }
            if (value != null && compare(value, rule.getRuleOp(), rule.getRuleValue())) {
                hit.add(rule.getId());
            }
        }
        return hit;
    }

    /** 档案类指标（全周期，取 customer 表当前值） */
    private BigDecimal profileMetric(Customer customer, String metric) {
        if (metric == null) {
            return null;
        }
        return switch (metric) {
            case "CSAT" -> customer.getCsat();
            case "LEVEL" -> decimal(customer.getLevel());
            case "RISK_LEVEL" -> decimal(customer.getRiskLevel());
            case "SESSIONS" -> decimal(customer.getSessionCount());
            case "TICKETS" -> decimal(customer.getTicketCount());
            case "ACTIVE_DAYS" -> activeDays(customer.getLastActive());
            default -> null;
        };
    }

    /** 会话类指标（窗口内现算） */
    private BigDecimal metricValue(Map<String, Object> row, String metric) {
        if (row == null) {
            return BigDecimal.ZERO;
        }
        return switch (metric) {
            case "REFUND_SESSIONS" -> decimal(intOf(row.get("refund_sessions")));
            case "COMPLAINT_SESSIONS" -> decimal(intOf(row.get("complaint_sessions")));
            case "NIGHT_SESSIONS" -> decimal(intOf(row.get("night_sessions")));
            default -> BigDecimal.ZERO;
        };
    }

    /** 距最近活跃天数：没活跃过就是"永远之前"，用一个大数表示（这样 LT/GT 都能算） */
    private BigDecimal activeDays(LocalDateTime lastActive) {
        if (lastActive == null) {
            return BigDecimal.valueOf(9999);
        }
        long days = Duration.between(lastActive, LocalDateTime.now()).toDays();
        return BigDecimal.valueOf(Math.max(0, days));
    }

    /** 比较：EQ 用"四舍五入到 2 位后相等"，避免 4.80 与 4.8 被判不相等 */
    private boolean compare(BigDecimal value, String op, BigDecimal threshold) {
        int cmp = value.compareTo(threshold);
        return switch (op == null ? "" : op) {
            case "GT" -> cmp > 0;
            case "GTE" -> cmp >= 0;
            case "LT" -> cmp < 0;
            case "LTE" -> cmp <= 0;
            case "EQ" -> value.setScale(2, RoundingMode.HALF_UP)
                    .compareTo(threshold.setScale(2, RoundingMode.HALF_UP)) == 0;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ 落库

    /**
     * 把求值结果落到 customer_tag 上：命中的补上、不再命中的摘掉（只动 source=2 的）。
     */
    @Transactional
    public ApplyResult apply(String tenantCode, Customer customer, List<CustomerTagDef> rules,
                             Set<Long> hit, String operatorName) {
        List<Long> ruleIds = rules.stream().map(CustomerTagDef::getId).toList();
        if (ruleIds.isEmpty()) {
            return new ApplyResult(0, 0, 0);
        }
        // 当前挂在客户身上的自动标签
        List<CustomerTag> current = customerTagMapper.selectList(Wrappers.<CustomerTag>lambdaQuery()
                .eq(CustomerTag::getTenantCode, tenantCode)
                .eq(CustomerTag::getCustomerId, customer.getId())
                .eq(CustomerTag::getDeleted, false)
                .in(CustomerTag::getTagId, ruleIds)
                .eq(CustomerTag::getSource, SOURCE_RULE));
        Map<Long, CustomerTag> currentByTagId = new LinkedHashMap<>();
        for (CustomerTag tag : current) {
            currentByTagId.put(tag.getTagId(), tag);
        }
        LocalDateTime now = LocalDateTime.now();
        String who = operatorName == null || operatorName.isBlank() ? RULE_OPERATOR : "规则引擎（" + operatorName + " 触发）";
        int tagged = 0;
        int untagged = 0;

        for (CustomerTagDef rule : rules) {
            boolean shouldHave = hit.contains(rule.getId());
            CustomerTag existing = currentByTagId.get(rule.getId());
            if (shouldHave && existing == null) {
                customerTagMapper.insert(CustomerTag.builder()
                        .id(idGenerator.nextId())
                        .tenantCode(tenantCode)
                        .customerId(customer.getId())
                        .tagName(rule.getTagName())
                        .tagId(rule.getId())
                        .tagGroup(rule.getTagGroup())
                        .source(SOURCE_RULE)
                        .operatorName(RULE_OPERATOR)
                        .createTime(now)
                        .updateTime(now)
                        .deleted(false)
                        .build());
                tagged++;
                dynamics.record(tenantCode, customer.getId(), CustomerDynamics.TAG_ADD,
                        "打上标签：" + rule.getTagName(),
                        "规则命中：" + ruleText(rule), CustomerDynamics.REF_TAG,
                        rule.getTagCode(), null, who);
            } else if (!shouldHave && existing != null) {
                CustomerTag update = new CustomerTag();
                update.setId(existing.getId());
                update.setDeleted(true);
                update.setUpdateTime(now);
                customerTagMapper.updateById(update);
                untagged++;
                dynamics.record(tenantCode, customer.getId(), CustomerDynamics.TAG_REMOVE,
                        "摘掉标签：" + rule.getTagName(),
                        "不再命中规则：" + ruleText(rule), CustomerDynamics.REF_TAG,
                        rule.getTagCode(), null, who);
            }
        }
        return new ApplyResult(rules.size(), tagged, untagged);
    }

    /** 规则的人话描述：累计会话数 ≥ 5（全周期） / 投诉类会话数 ≥ 2（近 30 天） */
    public static String ruleText(CustomerTagDef rule) {
        if (rule == null || rule.getRuleMetric() == null) {
            // 手工标签没有规则，"人话描述"就是没有——返回 null，别给前端编一句假条件
            return null;
        }
        String metric = metricName(rule.getRuleMetric());
        String op = opName(rule.getRuleOp());
        String value = rule.getRuleValue() == null ? "?" : rule.getRuleValue().stripTrailingZeros().toPlainString();
        int window = rule.getRuleWindowDays() == null ? 0 : rule.getRuleWindowDays();
        String scope = window > 0 && SESSION_METRICS.contains(rule.getRuleMetric())
                ? "（近 " + window + " 天）" : "（全周期）";
        return metric + " " + op + " " + value + scope;
    }

    /** 指标中文名（前端下拉用） */
    public static Map<String, String> metricNames() {
        return METRIC_NAMES;
    }

    /**
     * 指标编码 → 中文名。
     *
     * <p><b>为什么单独包一层</b>：{@link Map#ofEntries} 建出来的是**不可变 Map**，
     * 它连 `getOrDefault(null, ...)` 都会抛 NPE（不可变集合不允许 null key，
     * 一进 `hashCode()` 就炸）。而手工标签的 rule_metric 天然就是 null——
     * 这一层 null 判断是必须的，不是"防御性编程"的装饰。</p>
     */
    public static String metricName(String metric) {
        return metric == null || metric.isBlank() ? null : METRIC_NAMES.getOrDefault(metric, metric);
    }

    /** 比较符编码 → 符号（同样要挡 null，原因见 {@link #metricName(String)}） */
    public static String opName(String op) {
        return op == null || op.isBlank() ? null : OP_NAMES.getOrDefault(op, op);
    }

    private static BigDecimal decimal(Integer value) {
        return value == null ? BigDecimal.ZERO : BigDecimal.valueOf(value);
    }

    private static int intOf(Object value) {
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

    /** 单个客户重算的结果 */
    public record ApplyResult(int ruleCount, int tagged, int untagged) {
    }

    /** 全量重算的结果 */
    public record BatchResult(int scanned, int tagged, int untagged) {
    }

    /** 供前端展示的规则标签定义（指标/条件/阈值/窗口） */
    public record RuleVO(String tagCode, String tagName, String metric, String metricText,
                         String op, String opText, BigDecimal value, int windowDays, String text) {

        public static RuleVO of(CustomerTagDef def) {
            return new RuleVO(def.getTagCode(), def.getTagName(), def.getRuleMetric(),
                    metricName(def.getRuleMetric()),
                    def.getRuleOp(), opName(def.getRuleOp()),
                    def.getRuleValue(), def.getRuleWindowDays() == null ? 0 : def.getRuleWindowDays(),
                    ruleText(def));
        }
    }

    /** 指标清单（前端规则编辑器用）：顺序固定，别让下拉每次顺序都不一样 */
    public List<Map<String, String>> metricOptions() {
        List<Map<String, String>> options = new ArrayList<>();
        for (Map.Entry<String, String> entry : METRIC_NAMES.entrySet()) {
            options.add(Map.of("value", entry.getKey(), "label", entry.getValue()));
        }
        options.sort((a, b) -> a.get("label").compareTo(b.get("label")));
        return options;
    }
}
