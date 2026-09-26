package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.Customer;
import cn.net.susan.customer.entity.QaAlert;
import cn.net.susan.customer.entity.QaReview;
import cn.net.susan.customer.entity.QaRule;
import cn.net.susan.customer.entity.QaTask;
import cn.net.susan.customer.entity.Session;
import cn.net.susan.customer.entity.SessionMessage;
import cn.net.susan.customer.internal.QaAiClient;
import cn.net.susan.customer.mapper.CustomerMapper;
import cn.net.susan.customer.mapper.QaAlertMapper;
import cn.net.susan.customer.mapper.QaReviewMapper;
import cn.net.susan.customer.mapper.QaRuleMapper;
import cn.net.susan.customer.mapper.QaTaskMapper;
import cn.net.susan.customer.mapper.SessionMapper;
import cn.net.susan.customer.mapper.SessionMessageMapper;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 质检中心：规则配置、任务列表、AI 全量扫描、人工复核。
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    private static final DateTimeFormatter TASK_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SCAN_LIMIT = 50;
    private static final int MAX_ALERT_EVIDENCE = 50;
    private static final ExecutorService AI_EVALUATOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "qa-ai-evaluate");
        thread.setDaemon(true);
        return thread;
    });

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 默认规则：名称、类型码、类型文案、检查内容 */
    private static final RuleSeed[] DEFAULT_RULES = {
            new RuleSeed("敏感词与禁语", 1, "敏感词", "排查“妈的、废物、随便你”等不礼貌用语"),
            new RuleSeed("服务承诺规范", 2, "承诺规范", "承诺必须包含明确时间与可兑现口径"),
            new RuleSeed("必答项完整", 3, "必答项", "订单号、地址、发票抬头等关键信息必须核实"),
            new RuleSeed("情绪安抚", 4, "情绪识别", "客户表达不满时应先致歉再安抚，禁止机械回复"),
            new RuleSeed("响应超时", 5, "响应超时", "客户发出消息后长时间没有坐席回复，需要提醒并及时响应"),
    };

    private final QaRuleMapper qaRuleMapper;
    private final QaTaskMapper qaTaskMapper;
    private final QaReviewMapper qaReviewMapper;
    private final QaAlertMapper qaAlertMapper;
    private final SessionMapper sessionMapper;
    private final SessionMessageMapper sessionMessageMapper;
    private final CustomerMapper customerMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final QaAiClient qaAiClient;

    public QaService(
            QaRuleMapper qaRuleMapper,
            QaTaskMapper qaTaskMapper,
            QaReviewMapper qaReviewMapper,
            QaAlertMapper qaAlertMapper,
            SessionMapper sessionMapper,
            SessionMessageMapper sessionMessageMapper,
            CustomerMapper customerMapper,
            SnowflakeIdGenerator idGenerator,
            QaAiClient qaAiClient
    ) {
        this.qaRuleMapper = qaRuleMapper;
        this.qaTaskMapper = qaTaskMapper;
        this.qaReviewMapper = qaReviewMapper;
        this.qaAlertMapper = qaAlertMapper;
        this.sessionMapper = sessionMapper;
        this.sessionMessageMapper = sessionMessageMapper;
        this.customerMapper = customerMapper;
        this.idGenerator = idGenerator;
        this.qaAiClient = qaAiClient;
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
        // 实时预警三个数：总告警、待处理、严重
        Map<String, Object> alertRow = qaAlertMapper.selectAlertStat(tenant);
        int alertTotal = longToInt(alertRow == null ? null : alertRow.get("total"));
        int alertPending = longToInt(alertRow == null ? null : alertRow.get("pending"));
        int alertSevere = longToInt(alertRow == null ? null : alertRow.get("severe"));
        return new OverviewVO(total, pending, passed, rejected, round2(avgAi), round2(avgReview),
                passRate, riskLow, riskMid, riskHigh, alertTotal, alertPending, alertSevere);
    }

    private int longToInt(Object value) {
        if (value == null) {
            return 0;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
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
        List<QaAlert> taskAlerts = task.getSessionId() == null ? List.of()
                : qaAlertMapper.selectList(Wrappers.<QaAlert>lambdaQuery()
                    .eq(QaAlert::getTenantCode, tenant)
                    .eq(QaAlert::getSessionId, task.getSessionId())
                    .eq(QaAlert::getDeleted, false)
                    .orderByDesc(QaAlert::getCreateTime));
        List<Map<String, Object>> alertEvidence = taskAlerts.stream().map(alert -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", String.valueOf(alert.getId()));
            item.put("sessionNo", alert.getSessionNo());
            item.put("ruleName", alert.getRuleName());
            item.put("severity", alert.getSeverity());
            item.put("snippet", alert.getSnippet());
            item.put("status", alert.getStatus());
            item.put("createTime", alert.getCreateTime());
            return item;
        }).toList();
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
                task.getReviewerId() == null ? null : String.valueOf(task.getReviewerId()),
                task.getAiSource(),
                alertEvidence,
                taskAlerts.size(),
                (int) taskAlerts.stream().filter(alert -> alert.getSeverity() != null && alert.getSeverity() >= 3).count()
        );
    }

    /** 补扫已结束且还未质检的真实会话。 */
    public ScanResult scan(LoginUser user) {
        String tenant = tenantOf(user);
        ensureRules(tenant, user.userId());
        List<Session> candidates = sessionMapper.selectClosedSessionsWithoutTask(tenant, SCAN_LIMIT);
        List<String> taskNos = new ArrayList<>();
        for (Session session : candidates) {
            String taskNo = createFromSession(tenant, session, user.userId());
            if (taskNo != null) {
                taskNos.add(taskNo);
            }
        }
        return new ScanResult(candidates.size(), taskNos.size(), taskNos);
    }

    /** 会话结束后建任务；模型初检在后台运行，失败时保留任务供人工复核。 */
    public String createFromSession(String tenantCode, Session session, Long operatorId) {
        if (session == null || session.getId() == null || !tenantCode.equals(session.getTenantCode())) {
            return null;
        }
        if (qaTaskMapper.selectCount(Wrappers.<QaTask>lambdaQuery()
                .eq(QaTask::getTenantCode, tenantCode)
                .eq(QaTask::getSessionId, session.getId())
                .eq(QaTask::getDeleted, false)) > 0) {
            return null;
        }
        List<SessionMessage> messages = sessionMessageMapper.selectList(Wrappers.<SessionMessage>lambdaQuery()
                .eq(SessionMessage::getTenantCode, tenantCode)
                .eq(SessionMessage::getSessionId, session.getId())
                .eq(SessionMessage::getDeleted, false)
                .orderByAsc(SessionMessage::getSeq)
                .last("LIMIT 100"));
        String transcript = messages.stream()
                .filter(message -> message.getContent() != null && !message.getContent().isBlank())
                .map(message -> (Integer.valueOf(1).equals(message.getSenderType()) ? "客户" : "客服")
                        + "：" + transcriptContent(message))
                .reduce((a, b) -> a + "\n" + b).orElse("");
        if (transcript.isBlank()) {
            return null;
        }
        if (transcript.length() > 19000) {
            transcript = transcript.substring(0, 19000);
        }
        Customer customer = session.getCustomerId() == null ? null : customerMapper.selectById(session.getCustomerId());
        String customerName = customer != null && tenantCode.equals(customer.getTenantCode())
                && !Boolean.TRUE.equals(customer.getDeleted()) && customer.getName() != null
                ? customer.getName() : "访客";
        String sessionName = customerName + " · " + session.getSessionNo();
        String agentName = session.getAgentId() == null ? "未接入坐席" : String.valueOf(session.getAgentId());
        List<QaAlert> alertRows = qaAlertMapper.selectAlerts(
                tenantCode, null, null, null, session.getSessionNo(), null, null, null, MAX_ALERT_EVIDENCE);
        List<Map<String, Object>> alerts = alertRows.stream().map(alert -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ruleName", alert.getRuleName());
            item.put("severity", alert.getSeverity());
            item.put("snippet", alert.getSnippet());
            item.put("status", alert.getStatus());
            return item;
        }).toList();
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("sessionName", sessionName);
        ai.put("agentName", agentName);
        ai.put("comment", "待初检");
        ai.put("rules", List.of());
        ai.put("alerts", alerts);
        QaTask task = QaTask.builder()
                .id(idGenerator.nextId()).tenantCode(tenantCode).taskNo(generateTaskNo(tenantCode))
                .sessionId(session.getId()).agentId(session.getAgentId())
                .aiResult(writeJson(ai)).transcript(transcript).aiSource("pending")
                .riskLevel(1).status(1)
                .creator(operatorId == null ? "SYSTEM" : String.valueOf(operatorId))
                .deleted(false).build();
        try {
            qaTaskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            return null;
        }
        AI_EVALUATOR.submit(() -> {
            try {
                QaTask current = requireTask(tenantCode, task.getTaskNo());
                evaluateTask(tenantCode, current, null);
            } catch (Exception e) {
                log.warn("后台质检初检失败 tenant={} taskNo={} error={}",
                        tenantCode, task.getTaskNo(), e.getMessage());
            }
        });
        return task.getTaskNo();
    }

    /**
     * 新增单个质检：针对某个会话手动创建一条待复核任务。
     */
    public TaskVO createManual(
            LoginUser user,
            String sessionName,
            String agentName,
            Double aiScore,
            Integer riskLevel,
            String comment,
            List<String> ruleNames,
            String transcript,
            String authorization
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
        String source = "manual";
        if (transcript != null && !transcript.isBlank()) {
            List<QaAiClient.Rule> specs = enabledRules(tenant).stream()
                    .filter(rule -> validRules.isEmpty() || validRules.contains(rule.getRuleName()))
                    .map(rule -> new QaAiClient.Rule(rule.getRuleName(), rule.getRuleContent()))
                    .toList();
            QaAiClient.Evaluation evaluated = qaAiClient.evaluate(new QaAiClient.Request(
                    tenant, sessionName.trim(), agentName.trim(), transcript.trim(), specs), authorization);
            score = evaluated.aiScore();
            risk = evaluated.riskLevel();
            ai.put("comment", evaluated.comment());
            ai.put("rules", evaluated.rules());
            source = evaluated.source();
        }

        QaTask task = QaTask.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .taskNo(generateTaskNo(tenant))
                .sessionId(idGenerator.nextId())
                .agentId(null)
                .aiScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP))
                .aiResult(writeJson(ai))
                .transcript(transcript == null || transcript.isBlank() ? null : transcript.trim())
                .aiSource(source)
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
     * 对有对话文本的任务执行模型初检；无文本任务不得伪造模型评分。
     */
    public BatchAiResult batchAiCheck(LoginUser user, List<String> taskNos, String authorization) {
        String tenant = tenantOf(user);
        if (taskNos == null || taskNos.isEmpty()) {
            throw new BizException(40001, "请选择至少一条质检任务");
        }
        if (taskNos.size() > 200) {
            throw new BizException(40001, "单次批量 AI 质检最多选择 200 条任务");
        }
        List<QaTask> selected = taskNos.stream().map(taskNo -> requireTask(tenant, taskNo)).toList();
        if (selected.stream().anyMatch(task -> task.getTranscript() == null || task.getTranscript().isBlank())) {
            throw new BizException(40001, "所选任务含无对话文本的演示任务，无法执行真实模型初检");
        }
        for (QaTask task : selected) {
            evaluateTask(tenant, task, authorization);
            qaTaskMapper.update(null, Wrappers.<QaTask>lambdaUpdate()
                    .eq(QaTask::getId, task.getId())
                    .eq(QaTask::getTenantCode, tenant)
                    .eq(QaTask::getDeleted, false)
                    .set(QaTask::getStatus, 1)
                    .set(QaTask::getReviewScore, null)
                    .set(QaTask::getReviewerId, null)
                    .set(QaTask::getReviewTime, null)
                    .set(QaTask::getEditor, String.valueOf(user.userId()))
                    .set(QaTask::getUpdateTime, LocalDateTime.now()));
        }
        return new BatchAiResult(taskNos.size(), List.copyOf(taskNos));
    }

    private void evaluateTask(String tenant, QaTask task, String authorization) {
        if (task.getTranscript() == null || task.getTranscript().isBlank()) {
            return;
        }
        AiResult old = parseAi(task);
        List<QaAiClient.Rule> specs = enabledRules(tenant).stream()
                .map(rule -> new QaAiClient.Rule(rule.getRuleName(), rule.getRuleContent()))
                .toList();
        List<QaAlert> alerts = task.getSessionId() == null ? List.of()
                : qaAlertMapper.selectList(Wrappers.<QaAlert>lambdaQuery()
                    .eq(QaAlert::getTenantCode, tenant)
                    .eq(QaAlert::getSessionId, task.getSessionId())
                    .eq(QaAlert::getDeleted, false)
                    .orderByAsc(QaAlert::getCreateTime));
        StringBuilder evidence = new StringBuilder();
        int riskFloor = 1;
        for (QaAlert alert : alerts) {
            evidence.append("实时预警：").append(alert.getRuleName())
                    .append("；片段：").append(alert.getSnippet() == null ? "" : alert.getSnippet())
                    .append('\n');
            if (alert.getSeverity() != null) {
                riskFloor = Math.max(riskFloor, alert.getSeverity());
            }
        }
        String transcript = evidence + task.getTranscript();
        if (transcript.length() > 20000) {
            transcript = transcript.substring(0, 20000);
        }
        QaAiClient.Evaluation evaluated = qaAiClient.evaluate(new QaAiClient.Request(
                tenant, old.sessionName(), old.agentName(), transcript, specs), authorization);
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("sessionName", old.sessionName());
        ai.put("agentName", old.agentName());
        ai.put("comment", evaluated.comment());
        ai.put("rules", evaluated.rules());
        ai.put("alertCount", alerts.size());
        task.setAiScore(BigDecimal.valueOf(evaluated.aiScore()).setScale(2, RoundingMode.HALF_UP));
        task.setAiResult(writeJson(ai));
        task.setAiSource(evaluated.source());
        task.setRiskLevel(Math.max(evaluated.riskLevel(), riskFloor));
        task.setUpdateTime(LocalDateTime.now());
        qaTaskMapper.updateById(task);
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
                           String ruleContent, int weight, boolean enabled,
                           boolean realtime, String hitKeywords, Integer severity,
                           Integer timeoutSeconds) {
        String tenant = tenantOf(user);
        if (ruleType < 1 || ruleType > 5) {
            throw new BizException(40001, "规则类型不正确");
        }
        if (severity != null && (severity < 1 || severity > 3)) {
            throw new BizException(40001, "告警级别取值 1-提示、2-警告、3-严重");
        }
        if (timeoutSeconds != null && (timeoutSeconds < 10 || timeoutSeconds > 3600)) {
            throw new BizException(40001, "响应超时秒数应在 10~3600 之间");
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
                    .isRealtime(realtime)
                    .hitKeywords(hitKeywords)
                    .severity(severity == null ? 2 : severity)
                    .timeoutSeconds(timeoutSeconds == null ? 60 : timeoutSeconds)
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
            rule.setIsRealtime(realtime);
            rule.setHitKeywords(hitKeywords);
            rule.setSeverity(severity == null ? 2 : severity);
            rule.setTimeoutSeconds(timeoutSeconds == null ? 60 : timeoutSeconds);
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
                .aiSource("demo")
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
                ai.rules(),
                task.getAiSource()
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
                !Boolean.FALSE.equals(rule.getIsRealtime()),
                rule.getHitKeywords(),
                rule.getSeverity() == null ? 2 : rule.getSeverity(),
                rule.getTimeoutSeconds() == null ? 60 : rule.getTimeoutSeconds(),
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
            case 5 -> "响应超时";
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
            int riskHigh,
            int alertTotal,
            int alertPending,
            int alertSevere
    ) {
    }

    /** 将图片消息的识别结论提取为质检文本。 */
    private String transcriptContent(SessionMessage message) {
        if (!Integer.valueOf(SessionService.MSG_TYPE_IMAGE).equals(message.getMsgType())) {
            return message.getContent();
        }
        try {
            Map<String, Object> image = JSON.readValue(message.getContent(), new TypeReference<>() {});
            StringBuilder text = new StringBuilder("[图片]");
            for (String key : List.of("text", "aiSummary", "aiErrorText", "aiOcrText")) {
                Object value = image.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    text.append(' ').append(String.valueOf(value));
                }
            }
            return text.toString();
        } catch (Exception ignored) {
            return "[图片]";
        }
    }

    /** 规则视图。 */
    public record RuleVO(
            String id,
            String ruleName,
            int ruleType,
            String ruleTypeText,
            String ruleContent,
            int weight,
            boolean enabled,
            boolean realtime,
            String hitKeywords,
            int severity,
            int timeoutSeconds,
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
            List<String> ruleNames,
            String aiSource
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
            String reviewerId,
            String aiSource,
            List<Map<String, Object>> alerts,
            int alertCount,
            int severeAlertCount
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
    public record ScanResult(int scanned, int created, List<String> taskNos) {
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
