package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.QaReview;
import cn.net.susan.customer.entity.QaRule;
import cn.net.susan.customer.entity.QaTask;
import cn.net.susan.customer.mapper.QaReviewMapper;
import cn.net.susan.customer.mapper.QaRuleMapper;
import cn.net.susan.customer.mapper.QaTaskMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 质检中心：规则配置、任务列表、AI 全量扫描、人工复核。
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    private static final DateTimeFormatter TASK_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 默认规则：名称、类型码、类型文案、检查内容 */
    private static final RuleSeed[] DEFAULT_RULES = {
            new RuleSeed("敏感词与禁语", 1, "敏感词", "排查“妈的、废物、随便你”等不礼貌用语"),
            new RuleSeed("服务承诺规范", 2, "承诺规范", "承诺必须包含明确时间与可兑现口径"),
            new RuleSeed("必答项完整", 3, "必答项", "订单号、地址、发票抬头等关键信息必须核实"),
            new RuleSeed("情绪安抚", 4, "情绪识别", "客户表达不满时应先致歉再安抚，禁止机械回复"),
    };

    private final QaRuleMapper qaRuleMapper;
    private final QaTaskMapper qaTaskMapper;
    private final QaReviewMapper qaReviewMapper;
    private final SnowflakeIdGenerator idGenerator;

    public QaService(
            QaRuleMapper qaRuleMapper,
            QaTaskMapper qaTaskMapper,
            QaReviewMapper qaReviewMapper,
            SnowflakeIdGenerator idGenerator
    ) {
        this.qaRuleMapper = qaRuleMapper;
        this.qaTaskMapper = qaTaskMapper;
        this.qaReviewMapper = qaReviewMapper;
        this.idGenerator = idGenerator;
    }

    /**
     * 看板概览。
     */
    public OverviewVO overview(LoginUser user) {
        String tenant = tenantOf(user);
        List<QaTask> tasks = qaTaskMapper.selectList(
                Wrappers.<QaTask>lambdaQuery()
                        .eq(QaTask::getTenantCode, tenant)
                        .eq(QaTask::getDeleted, false));
        int total = tasks.size();
        int pending = countStatus(tasks, 1);
        int passed = countStatus(tasks, 2);
        int rejected = countStatus(tasks, 3);
        double avgAi = average(tasks.stream().map(QaTask::getAiScore).toList());
        double avgReview = average(tasks.stream()
                .filter(t -> t.getReviewScore() != null)
                .map(QaTask::getReviewScore).toList());
        int riskLow = countRisk(tasks, 1);
        int riskMid = countRisk(tasks, 2);
        int riskHigh = countRisk(tasks, 3);
        double passRate = total == 0 ? 0 : round2(passed * 100.0 / total);
        return new OverviewVO(total, pending, passed, rejected, round2(avgAi), round2(avgReview),
                passRate, riskLow, riskMid, riskHigh);
    }

    /**
     * 任务列表。
     */
    public List<TaskVO> tasks(LoginUser user, Integer status, String keyword) {
        String tenant = tenantOf(user);
        List<QaTask> rows = qaTaskMapper.selectList(
                Wrappers.<QaTask>lambdaQuery()
                        .eq(QaTask::getTenantCode, tenant)
                        .eq(QaTask::getDeleted, false)
                        .eq(status != null, QaTask::getStatus, status)
                        .orderByDesc(QaTask::getCreateTime));
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        return rows.stream()
                .filter(t -> kw.isBlank() || matches(t, kw))
                .map(this::toTaskVO)
                .toList();
    }

    /**
     * 任务详情（含规则评分明细）。
     */
    public TaskDetailVO detail(LoginUser user, String taskNo) {
        String tenant = tenantOf(user);
        QaTask task = requireTask(tenant, taskNo);
        AiResult ai = parseAi(task);
        List<RuleResultVO> results = new ArrayList<>();
        List<QaRule> rules = enabledRules(tenant);
        for (QaRule rule : rules) {
            boolean fail = ai.rules().contains(rule.getRuleName());
            results.add(new RuleResultVO(
                    rule.getRuleName(),
                    typeText(rule.getRuleType()),
                    !fail,
                    fail ? "AI 标记命中：关注 " + rule.getRuleContent() : "未命中该规则风险项"
            ));
        }
        return new TaskDetailVO(
                String.valueOf(task.getId()),
                task.getTaskNo(),
                ai.sessionName(),
                ai.agentName(),
                number(task.getAiScore()),
                number(task.getReviewScore()),
                task.getRiskLevel(),
                riskText(task.getRiskLevel()),
                task.getStatus(),
                statusText(task.getStatus()),
                task.getCreateTime() == null ? null : task.getCreateTime().toString(),
                task.getReviewTime() == null ? null : task.getReviewTime().toString(),
                ai.comment(),
                ai.rules(),
                results,
                task.getReviewerId() == null ? null : String.valueOf(task.getReviewerId())
        );
    }

    /**
     * 显式生成演示会话任务。真实会话接入前，这些任务不可作为正式质检结论。
     */
    @Transactional
    public ScanResult scan(LoginUser user) {
        String tenant = tenantOf(user);
        ensureRules(tenant, user.userId());
        List<QaTask> created = new ArrayList<>();
        SampleSpec[] pool = SAMPLE_POOL;
        for (int i = 0; i < 3; i++) {
            SampleSpec spec = pool[RANDOM.nextInt(pool.length)];
            QaTask task = buildSampleTask(tenant, spec);
            qaTaskMapper.insert(task);
            created.add(task);
        }
        log.info("演示质检任务生成完成 tenant={}, created={}", tenant, created.size());
        return new ScanResult(created.size(), created.stream().map(QaTask::getTaskNo).toList());
    }

    /**
     * 新增单个质检：针对某个会话手动创建一条待复核任务。
     */
    @Transactional
    public TaskVO createManual(
            LoginUser user,
            String sessionName,
            String agentName,
            Double aiScore,
            Integer riskLevel,
            String comment,
            List<String> ruleNames
    ) {
        String tenant = tenantOf(user);
        ensureRules(tenant, user.userId());
        if (sessionName == null || sessionName.isBlank()) {
            throw new BizException(40001, "请填写质检会话名称");
        }
        if (agentName == null || agentName.isBlank()) {
            throw new BizException(40001, "请填写接待客服");
        }
        int risk = riskLevel == null ? 1 : riskLevel;
        if (risk < 1 || risk > 3) {
            throw new BizException(40001, "风险级别不正确");
        }
        double score = aiScore == null ? 80 : Math.max(0, Math.min(100, aiScore));
        List<String> validRules = rules(user).stream()
                .map(QaService.RuleVO::ruleName)
                .filter(name -> ruleNames != null && ruleNames.contains(name))
                .toList();

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("sessionName", sessionName.trim());
        ai.put("agentName", agentName.trim());
        ai.put("comment", comment == null || comment.isBlank() ? "人工发起单个质检，请复核人结合会话内容给出结论。" : comment.trim());
        ai.put("rules", validRules);

        QaTask task = QaTask.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .taskNo(generateTaskNo(tenant))
                .sessionId(idGenerator.nextId())
                .agentId(null)
                .aiScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP))
                .aiResult(writeJson(ai))
                .riskLevel(risk)
                .status(1)
                .creator(String.valueOf(user.userId()))
                .createTime(now)
                .updateTime(now)
                .deleted(false)
                .build();
        qaTaskMapper.insert(task);
        log.info("人工新增单个质检 tenant={}, taskNo={}, creator={}", tenant, task.getTaskNo(), user.userId());
        return toTaskVO(task);
    }

    /**
     * 人工复核：1-通过、2-驳回、3-重检。
     */
    @Transactional
    public TaskVO review(LoginUser user, String taskNo, int action, Double score, String comment) {
        String tenant = tenantOf(user);
        QaTask task = requireTask(tenant, taskNo);
        LocalDateTime now = LocalDateTime.now();
        if (action == 1 || action == 2) {
            if (score == null || score < 0 || score > 100) {
                throw new BizException(40001, "复核评分必须在 0~100 之间");
            }
            if (action == 2 && (comment == null || comment.isBlank())) {
                throw new BizException(40001, "驳回时必须填写复核意见");
            }
            task.setStatus(action == 1 ? 2 : 3);
            task.setReviewScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
            task.setReviewerId(user.userId());
            task.setReviewTime(now);
        } else if (action == 3) {
            task.setStatus(1);
            task.setReviewScore(null);
            task.setReviewerId(null);
            task.setReviewTime(null);
        } else {
            throw new BizException(40001, "复核动作不支持");
        }
        task.setEditor(String.valueOf(user.userId()));
        task.setUpdateTime(now);
        qaTaskMapper.updateById(task);

        qaReviewMapper.insert(QaReview.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .taskId(task.getId())
                .reviewerId(user.userId())
                .action(action)
                .comment(comment == null || comment.isBlank() ? null : comment.trim())
                .reviewTime(now)
                .build());
        return toTaskVO(task);
    }

    /**
     * 批量复核：多条任务一次性通过/驳回/重检。
     */
    @Transactional
    public BatchReviewResult batchReview(
            LoginUser user,
            List<String> taskNos,
            int action,
            Double score,
            String comment
    ) {
        if (taskNos == null || taskNos.isEmpty()) {
            throw new BizException(40001, "请选择至少一条质检任务");
        }
        if (taskNos.size() > 200) {
            throw new BizException(40001, "单次批量质检最多选择 200 条任务");
        }
        for (String taskNo : taskNos) {
            review(user, taskNo, action, score, comment);
        }
        return new BatchReviewResult(taskNos.size(), List.copyOf(taskNos));
    }

    /**
     * 演示批量初检：使用确定性模拟评分，真实模型接入后再替换。
     */
    @Transactional
    public BatchAiResult batchAiCheck(LoginUser user, List<String> taskNos) {
        String tenant = tenantOf(user);
        if (taskNos == null || taskNos.isEmpty()) {
            throw new BizException(40001, "请选择至少一条质检任务");
        }
        if (taskNos.size() > 200) {
            throw new BizException(40001, "单次批量 AI 质检最多选择 200 条任务");
        }
        List<QaRule> enabled = enabledRules(tenant);
        LocalDateTime now = LocalDateTime.now();
        for (String taskNo : taskNos) {
            QaTask task = requireTask(tenant, taskNo);
            AiResult old = parseAi(task);
            int seed = Math.floorMod(task.getTaskNo().hashCode(), 997);
            double score = 72 + seed % 26;
            int risk = score >= 88 ? 1 : score >= 78 ? 2 : 3;
            List<String> hitRules = new ArrayList<>();
            if (seed % 3 == 0 && !enabled.isEmpty()) {
                hitRules.add(enabled.get(0).getRuleName());
            }
            if (seed % 5 == 0 && enabled.size() > 1) {
                hitRules.add(enabled.get(1).getRuleName());
            }
            Map<String, Object> ai = new LinkedHashMap<>();
            ai.put("sessionName", old.sessionName());
            ai.put("agentName", old.agentName());
            ai.put("comment", seed % 3 == 0
                    ? "演示初检：模拟命中部分规则，请结合会话复核。"
                    : "演示初检：模拟服务评分，请人工复核确认。");
            ai.put("rules", hitRules);
            task.setAiScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
            task.setAiResult(writeJson(ai));
            task.setRiskLevel(risk);
            task.setStatus(1);
            task.setReviewScore(null);
            task.setReviewerId(null);
            task.setReviewTime(null);
            task.setEditor(String.valueOf(user.userId()));
            task.setUpdateTime(now);
            qaTaskMapper.updateById(task);
        }
        return new BatchAiResult(taskNos.size(), List.copyOf(taskNos));
    }

    /**
     * 规则列表。
     */
    public List<RuleVO> rules(LoginUser user) {
        String tenant = tenantOf(user);
        ensureRules(tenant, user.userId());
        return qaRuleMapper.selectList(
                        Wrappers.<QaRule>lambdaQuery()
                                .eq(QaRule::getTenantCode, tenant)
                                .eq(QaRule::getDeleted, false)
                                .orderByAsc(QaRule::getRuleType)
                                .orderByDesc(QaRule::getCreateTime))
                .stream().map(this::toRuleVO).toList();
    }

    /**
     * 新建/编辑规则。
     */
    @Transactional
    public RuleVO saveRule(LoginUser user, String id, String ruleName, int ruleType,
                           String ruleContent, int weight, boolean enabled) {
        String tenant = tenantOf(user);
        if (ruleType < 1 || ruleType > 4) {
            throw new BizException(40001, "规则类型不正确");
        }
        weight = Math.max(0, Math.min(100, weight));
        LocalDateTime now = LocalDateTime.now();
        QaRule rule;
        if (id == null || id.isBlank()) {
            long exists = qaRuleMapper.selectCount(
                    Wrappers.<QaRule>lambdaQuery()
                            .eq(QaRule::getTenantCode, tenant)
                            .eq(QaRule::getRuleName, ruleName.trim())
                            .eq(QaRule::getDeleted, false));
            if (exists > 0) {
                throw new BizException(40002, "已存在同名质检规则，请修改名称后重试");
            }
            QaRule created = QaRule.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .ruleName(ruleName.trim())
                    .ruleType(ruleType)
                    .ruleContent(ruleContent)
                    .weight(weight)
                    .isEnabled(enabled)
                    .creator(String.valueOf(user.userId()))
                    .deleted(false)
                    .build();
            try {
                qaRuleMapper.insert(created);
                rule = created;
            } catch (DuplicateKeyException e) {
                throw new BizException(40002, "已存在同名质检规则，请修改名称后重试");
            }
        } else {
            rule = qaRuleMapper.selectById(Long.parseLong(id));
            if (rule == null || !tenant.equals(rule.getTenantCode())
                    || Boolean.TRUE.equals(rule.getDeleted())) {
                throw new BizException(40401, "规则不存在");
            }
            rule.setRuleName(ruleName.trim());
            rule.setRuleType(ruleType);
            rule.setRuleContent(ruleContent);
            rule.setWeight(weight);
            rule.setIsEnabled(enabled);
            rule.setEditor(String.valueOf(user.userId()));
            rule.setUpdateTime(now);
            qaRuleMapper.updateById(rule);
        }
        return toRuleVO(rule);
    }

    /**
     * 删除规则（逻辑删除）。
     */
    @Transactional
    public void deleteRule(LoginUser user, String id) {
        String tenant = tenantOf(user);
        QaRule rule = qaRuleMapper.selectById(Long.parseLong(id));
        if (rule == null || !tenant.equals(rule.getTenantCode())
                || Boolean.TRUE.equals(rule.getDeleted())) {
            throw new BizException(40401, "规则不存在");
        }
        rule.setDeleted(true);
        rule.setEditor(String.valueOf(user.userId()));
        rule.setUpdateTime(LocalDateTime.now());
        qaRuleMapper.updateById(rule);
    }

    private void ensureRules(String tenant, long userId) {
        long count = qaRuleMapper.selectCount(
                Wrappers.<QaRule>lambdaQuery()
                        .eq(QaRule::getTenantCode, tenant)
                        .eq(QaRule::getDeleted, false));
        if (count > 0) {
            return;
        }
        for (RuleSeed seed : DEFAULT_RULES) {
            qaRuleMapper.insertIgnore(QaRule.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .ruleName(seed.name())
                    .ruleType(seed.type())
                    .ruleContent(seed.content())
                    .weight(25)
                    .isEnabled(true)
                    .creator(String.valueOf(userId))
                    .deleted(false)
                    .build());
        }
    }

    private QaTask buildSampleTask(String tenant, SampleSpec spec) {
        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("sessionName", spec.sessionName());
        ai.put("agentName", spec.agentName());
        ai.put("comment", spec.comment());
        ai.put("rules", spec.ruleNames());
        return QaTask.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .taskNo(generateTaskNo(tenant))
                .sessionId(idGenerator.nextId())
                .agentId(null)
                .aiScore(BigDecimal.valueOf(spec.aiScore()).setScale(2, RoundingMode.HALF_UP))
                .aiResult(writeJson(ai))
                .riskLevel(spec.riskLevel())
                .status(1)
                .creator("AI_QA")
                .createTime(now)
                .updateTime(now)
                .deleted(false)
                .build();
    }

    private String generateTaskNo(String tenant) {
        for (int i = 0; i < 10; i++) {
            String no = "QC" + TASK_TIME.format(LocalDateTime.now()) + randomCode(3);
            long count = qaTaskMapper.selectCount(
                    Wrappers.<QaTask>lambdaQuery()
                            .eq(QaTask::getTenantCode, tenant)
                            .eq(QaTask::getTaskNo, no));
            if (count == 0) {
                return no;
            }
        }
        throw new BizException(50001, "质检任务编号生成失败");
    }

    private QaTask requireTask(String tenant, String taskNo) {
        QaTask task = qaTaskMapper.selectOne(
                Wrappers.<QaTask>lambdaQuery()
                        .eq(QaTask::getTenantCode, tenant)
                        .eq(QaTask::getTaskNo, taskNo)
                        .eq(QaTask::getDeleted, false)
                        .last("LIMIT 1"));
        if (task == null) {
            throw new BizException(40401, "质检任务不存在");
        }
        return task;
    }

    private TaskVO toTaskVO(QaTask task) {
        AiResult ai = parseAi(task);
        return new TaskVO(
                String.valueOf(task.getId()),
                task.getTaskNo(),
                ai.sessionName(),
                ai.agentName(),
                number(task.getAiScore()),
                number(task.getReviewScore()),
                task.getRiskLevel(),
                riskText(task.getRiskLevel()),
                task.getStatus(),
                statusText(task.getStatus()),
                task.getCreateTime() == null ? null : task.getCreateTime().toString().replace('T', ' '),
                task.getReviewTime() == null ? null : task.getReviewTime().toString().replace('T', ' '),
                ai.comment(),
                ai.rules()
        );
    }

    private RuleVO toRuleVO(QaRule rule) {
        return new RuleVO(
                String.valueOf(rule.getId()),
                rule.getRuleName(),
                rule.getRuleType(),
                typeText(rule.getRuleType()),
                rule.getRuleContent(),
                rule.getWeight(),
                Boolean.TRUE.equals(rule.getIsEnabled()),
                rule.getUpdateTime() == null ? null : rule.getUpdateTime().toString().replace('T', ' ')
        );
    }

    private List<QaRule> enabledRules(String tenant) {
        return qaRuleMapper.selectList(
                Wrappers.<QaRule>lambdaQuery()
                        .eq(QaRule::getTenantCode, tenant)
                        .eq(QaRule::getDeleted, false)
                        .eq(QaRule::getIsEnabled, true)
                        .orderByAsc(QaRule::getRuleType));
    }

    private AiResult parseAi(QaTask task) {
        if (task.getAiResult() == null || task.getAiResult().isBlank()) {
            return new AiResult("会话-" + task.getId(), "客服", "AI 未返回明细", List.of());
        }
        try {
            Map<String, Object> map = JSON.readValue(task.getAiResult(),
                    new TypeReference<Map<String, Object>>() {
                    });
            @SuppressWarnings("unchecked")
            List<String> rules = (List<String>) map.getOrDefault("rules", List.of());
            return new AiResult(
                    string(map.get("sessionName"), "会话"),
                    string(map.get("agentName"), "客服"),
                    string(map.get("comment"), ""),
                    rules == null ? List.of() : rules
            );
        } catch (Exception e) {
            return new AiResult("会话-" + task.getId(), "客服", "AI 结果解析失败", List.of());
        }
    }

    private boolean matches(QaTask task, String kw) {
        AiResult ai = parseAi(task);
        String hay = (task.getTaskNo() + " " + ai.sessionName() + " " + ai.agentName()).toLowerCase();
        return hay.contains(kw);
    }

    private String writeJson(Map<String, Object> map) {
        try {
            String json = JSON.writeValueAsString(map);
            return json;
        } catch (Exception e) {
            return "{}";
        }
    }

    private int countStatus(List<QaTask> tasks, int status) {
        return (int) tasks.stream().filter(t -> t.getStatus() != null && t.getStatus() == status).count();
    }

    private int countRisk(List<QaTask> tasks, int risk) {
        return (int) tasks.stream().filter(t -> t.getRiskLevel() != null && t.getRiskLevel() == risk).count();
    }

    private double average(List<BigDecimal> values) {
        if (values == null || values.isEmpty()) {
            return 0;
        }
        return values.stream()
                .filter(v -> v != null)
                .mapToDouble(BigDecimal::doubleValue)
                .average().orElse(0);
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private Double number(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private String string(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String typeText(int type) {
        return switch (type) {
            case 1 -> "敏感词";
            case 2 -> "承诺规范";
            case 3 -> "必答项";
            case 4 -> "情绪识别";
            default -> "其他";
        };
    }

    private String riskText(int risk) {
        return switch (risk) {
            case 1 -> "低风险";
            case 2 -> "中风险";
            case 3 -> "高风险";
            default -> "未知";
        };
    }

    private String statusText(int status) {
        return switch (status) {
            case 1 -> "待复核";
            case 2 -> "已通过";
            case 3 -> "已驳回";
            default -> "未知";
        };
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2) {
            throw new BizException(40301, "仅企业成员可使用质检中心");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用质检中心");
        }
        return tenant;
    }

    private String randomCode(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RANDOM.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private record RuleSeed(String name, int type, String typeText, String content) {
    }

    private record SampleSpec(
            String sessionName,
            String agentName,
            double aiScore,
            int riskLevel,
            List<String> ruleNames,
            String comment
    ) {
    }

    private record AiResult(String sessionName, String agentName, String comment, List<String> rules) {
    }

    private static final SampleSpec[] SAMPLE_POOL = {
            new SampleSpec("C-2077 · 赵一诺", "陈思远", 92, 1, List.of(),
                    "整体服务专业，应答准确，建议保持并主动邀评。"),
            new SampleSpec("C-2078 · 王浩宇", "郑凯文", 78, 2, List.of("服务承诺规范"),
                    "承诺较含糊，未给出明确时间节点，建议补充可兑现时限。"),
            new SampleSpec("C-2079 · 苏明轩", "吴佳怡", 68, 3, List.of("敏感词与禁语", "情绪安抚"),
                    "出现不耐烦表述，客户不满时未先安抚，需重点改进。"),
            new SampleSpec("C-2080 · 张梦琪", "林晓彤", 96, 1, List.of(),
                    "话术符合最新政策，情绪管理与升级机制均规范。"),
            new SampleSpec("C-2081 · 罗晓峰", "周子航", 84, 2, List.of("必答项完整"),
                    "未主动核实订单号，已补充确认，建议强化关键信息核验习惯。"),
            new SampleSpec("C-2082 · 陈博文", "孙雨桐", 88, 1, List.of("服务承诺规范"),
                    "承诺了补偿但未明确到账时间，需按规范补充时限。"),
            new SampleSpec("C-2083 · 刘雅静", "郑凯文", 90, 1, List.of(),
                    "处理路径清晰，主动同步进度，服务体验良好。"),
            new SampleSpec("C-2084 · 马天宇", "陈思远", 75, 2, List.of("情绪安抚", "必答项完整"),
                    "客户情绪激动时回应偏机械，需加强共情话术。"),
            new SampleSpec("C-2085 · 高雯", "吴佳怡", 81, 2, List.of("敏感词与禁语"),
                    "个别表述口语化较随意，建议使用标准化话术。"),
            new SampleSpec("C-2086 · 沈倩", "周子航", 71, 3, List.of("服务承诺规范", "情绪安抚"),
                    "两次承诺时间不一致，且未安抚客户，需重点复盘。"),
    };

    /**
     * 看板概览。
     */
    public record OverviewVO(
            int total,
            int pending,
            int passed,
            int rejected,
            double avgAiScore,
            double avgReviewScore,
            double passRate,
            int riskLow,
            int riskMid,
            int riskHigh
    ) {
    }

    /**
     * 规则视图。
     */
    public record RuleVO(
            String id,
            String ruleName,
            int ruleType,
            String ruleTypeText,
            String ruleContent,
            int weight,
            boolean enabled,
            String updateTime
    ) {
    }

    /**
     * 任务视图。
     */
    public record TaskVO(
            String id,
            String taskNo,
            String sessionName,
            String agentName,
            Double aiScore,
            Double reviewScore,
            int riskLevel,
            String riskText,
            int status,
            String statusText,
            String createTime,
            String reviewTime,
            String aiComment,
            List<String> ruleNames
    ) {
    }

    /**
     * 任务详情。
     */
    public record TaskDetailVO(
            String id,
            String taskNo,
            String sessionName,
            String agentName,
            Double aiScore,
            Double reviewScore,
            int riskLevel,
            String riskText,
            int status,
            String statusText,
            String createTime,
            String reviewTime,
            String aiComment,
            List<String> ruleNames,
            List<RuleResultVO> ruleResults,
            String reviewerId
    ) {
    }

    /**
     * 规则评分明细。
     */
    public record RuleResultVO(String name, String type, boolean pass, String reason) {
    }

    /**
     * 全量扫描结果。
     */
    public record ScanResult(int created, List<String> taskNos) {
    }

    /**
     * 批量复核结果。
     */
    public record BatchReviewResult(int processed, List<String> taskNos) {
    }

    /**
     * 批量 AI 质检结果。
     */
    public record BatchAiResult(int processed, List<String> taskNos) {
    }
}
