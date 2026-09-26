package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.QaAlert;
import cn.net.susan.customer.entity.QaRule;
import cn.net.susan.customer.entity.Session;
import cn.net.susan.customer.entity.SessionMessage;
import cn.net.susan.customer.internal.RealtimeNotifyClient;
import cn.net.susan.customer.internal.UserNameClient;
import cn.net.susan.customer.mapper.QaAlertMapper;
import cn.net.susan.customer.mapper.QaRuleMapper;
import cn.net.susan.customer.mapper.SessionMapper;
import cn.net.susan.customer.mapper.SessionMessageMapper;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话实时质检（边聊边检）：消息一落库就判一遍，命中规则当场提醒坐席。
 *
 * <p>只做"毫秒级能判定、且不依赖外部服务"的三类规则：敏感词、绝对化承诺、情绪安抚。
 * "必答项是否完整"要看整段会话，放在会话结束后的批量质检里做。分层还有个好处：
 * AI 服务挂了，实时预警照样工作。</p>
 */
@Service
public class RealtimeQaService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeQaService.class);

    /** 规则类型：1-敏感词、2-承诺规范、4-情绪识别（3-必答项放批量质检） */
    private static final int RULE_KEYWORD = 1;
    private static final int RULE_PROMISE = 2;
    private static final int RULE_EMOTION = 4;
    /** 5-响应超时：不看话术内容、看客户等了多久（由定时扫描触发，不是消息触发） */
    private static final int RULE_TIMEOUT = 5;

    /** 告警级别：1-提示、2-警告、3-严重 */
    public static final int SEVERITY_TIP = 1;
    public static final int SEVERITY_WARN = 2;
    public static final int SEVERITY_SEVERE = 3;

    /** 告警状态：1-待处理、2-已处理 */
    public static final int ALERT_PENDING = 1;
    public static final int ALERT_HANDLED = 2;

    private static final int ALERT_LIST_LIMIT = 200;
    private static final int SNIPPET_LIMIT = 120;
    private static final int CONTEXT_LOOKBACK = 10;
    /** 响应超时扫描：一轮最多处理多少条"客户在等"的会话 */
    private static final int TIMEOUT_SCAN_LIMIT = 200;
    /** 扫描窗口下限：等一下再进候选，别每轮都把刚发出来的消息捞一遍 */
    private static final int TIMEOUT_SCAN_FLOOR_SECONDS = 10;

    private static final String ADVICE_KEYWORD = "接待中出现了违规用语，请立即停用并改用规范话术，必要时向客户致歉。";
    private static final String ADVICE_PROMISE = "出现绝对化承诺，请改成[核实后 N 个工作日内回复]这类可兑现的说法。";
    private static final String ADVICE_EMOTION = "客户情绪已经激动，先致歉安抚，再解释处理方案。";

    /** 敏感词规则没配命中词时，从规则内容的引号里抠词 */
    private static final Pattern QUOTED = Pattern.compile("[\u201c\"']([^\u201d\"']{1,12})[\u201d\"']");

    /** 绝对化承诺：这类话术说出口就很难收回，实时拦下来（只查客服说的话） */
    private static final List<String> PROMISE_PHRASES = List.of(
            "保证一定", "保证肯定", "百分百解决", "百分百可以", "百分百能", "一定能", "肯定能",
            "绝对能", "绝对没问题", "永久免费", "永久有效", "永久保修", "包您满意", "包退包换");

    /**
     * 客户情绪激动的**强信号**：这些词本身就是"我不满、我要闹"，命中就算情绪激动。
     *
     * <p>注意千万别往里塞业务词——早先这个表里有"退款""退钱"，于是客户正常问一句
     * "退款多久能到账？"也被当成"情绪激动"，接下来坐席只要没先说"抱歉"，
     * 哪怕他只回了个"你好"，都会被打上"情绪安抚"的质检命中。这种误报最伤信任：
     * 坐席会开始无视所有预警。</p>
     */
    private static final List<String> ANGER_WORDS = List.of(
            "投诉", "差评", "曝光", "起诉", "举报", "骗子", "垃圾", "恶心", "气死",
            "什么态度", "没人管", "叫你们领导", "太差", "坑人", "气人", "太垃圾", "滚");

    /** 一般不满：单独出现不算激动，得配合强调语气（连续感叹号/问号）才触发 */
    private static final List<String> UNHAPPY_WORDS = List.of(
            "生气", "不满", "怎么回事", "搞什么", "还没", "一直没", "催", "急", "失望");

    /** 强调语气：连续感叹号 / 问号，或者"？？？"这种追问 */
    private static final Pattern EMPHATIC = Pattern.compile("[!！?？]{2,}");

    /** 客服先安抚再说事：命中任意一个就算已经安抚过 */
    private static final List<String> SOOTHING_WORDS = List.of(
            "抱歉", "对不起", "不好意思", "理解您", "给您添麻烦", "让您久等",
            "非常抱歉", "实在抱歉", "马上为您", "立刻为您", "高度重视", "我来帮您");

    private final QaRuleMapper qaRuleMapper;
    private final QaAlertMapper qaAlertMapper;
    private final SessionMapper sessionMapper;
    private final SessionMessageMapper sessionMessageMapper;
    private final RealtimeNotifyClient notifyClient;
    private final UserNameClient userNameClient;
    private final SnowflakeIdGenerator idGenerator;

    public RealtimeQaService(
            QaRuleMapper qaRuleMapper,
            QaAlertMapper qaAlertMapper,
            SessionMapper sessionMapper,
            SessionMessageMapper sessionMessageMapper,
            RealtimeNotifyClient notifyClient,
            UserNameClient userNameClient,
            SnowflakeIdGenerator idGenerator
    ) {
        this.qaRuleMapper = qaRuleMapper;
        this.qaAlertMapper = qaAlertMapper;
        this.sessionMapper = sessionMapper;
        this.sessionMessageMapper = sessionMessageMapper;
        this.notifyClient = notifyClient;
        this.userNameClient = userNameClient;
        this.idGenerator = idGenerator;
    }

    /**
     * 会话实时质检：消息落库后调用。
     *
     * <p>命中的规则逐个落一条告警，并推给这条会话的坐席。同一条消息 + 同一条规则只告一次
     * （消息幂等重发时靠唯一索引兜底）。</p>
     *
     * @param visibleTo 1-客户可见、2-坐席内部备注（备注不是发给客户的，不参与质检）
     */
    @Transactional
    public List<AlertVO> check(String tenantCode, Session session, Long messageId, Long messageSeq,
                               int senderType, String content, Integer visibleTo) {
        if (session == null || content == null || content.isBlank()) {
            return List.of();
        }
        if (visibleTo != null && visibleTo == SessionService.VISIBLE_AGENT_ONLY) {
            return List.of();
        }
        List<QaRule> rules = realtimeRules(tenantCode);
        if (rules.isEmpty()) {
            return List.of();
        }
        boolean fromAgent = senderType == SessionService.SENDER_AGENT;
        // 情绪安抚要结合上下文：先看客户上一条是不是在发火
        SessionMessage previousCustomer = fromAgent
                ? lastCustomerMessage(tenantCode, session.getId(), messageId)
                : null;
        List<AlertVO> created = new ArrayList<>();
        for (QaRule rule : rules) {
            Hit hit = detect(rule, content, fromAgent, previousCustomer);
            if (hit == null) {
                continue;
            }
            // 情绪安抚这条告警说的是**客户那条**有问题（客户情绪激动、坐席没安抚），
            // 所以标记要落在客户的消息上：坐席看到"这条客户消息需要安抚"，
            // 而不是自己回个"你好"被打上"质检命中"（那样坐席只会觉得系统在乱报）。
            boolean onCustomer = Integer.valueOf(RULE_EMOTION).equals(rule.getRuleType())
                    && previousCustomer != null;
            LocalDateTime now = LocalDateTime.now();
            QaAlert alert = QaAlert.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .sessionId(session.getId())
                    .sessionNo(session.getSessionNo())
                    .agentId(session.getAgentId())
                    .messageId(onCustomer ? previousCustomer.getId() : messageId)
                    .messageSeq(onCustomer ? previousCustomer.getSeq() : messageSeq)
                    .ruleId(rule.getId())
                    .ruleName(rule.getRuleName())
                    .ruleType(rule.getRuleType())
                    .severity(hit.severity())
                    .hitKeyword(hit.keyword())
                    .snippet(hit.snippet())
                    .advice(hit.advice())
                    .status(ALERT_PENDING)
                    .createTime(now)
                    .updateTime(now)
                    .creator("REALTIME_QA")
                    .deleted(false)
                    .build();
            if (qaAlertMapper.insertIgnore(alert) == 0) {
                continue;
            }
            AlertVO vo = toVO(alert, Map.of());
            created.add(vo);
            push(tenantCode, session, vo);
            log.info("会话实时质检命中 tenant={} sessionNo={} rule={} severity={} keyword={}",
                    tenantCode, session.getSessionNo(), rule.getRuleName(),
                    hit.severity(), hit.keyword());
        }
        return created;
    }

    /** 告警列表：实时预警页面用，支持状态 / 级别 / 规则类型 / 会话号 / 关键词 / 时间范围组合过滤。 */
    public List<AlertVO> alerts(LoginUser user, AlertQuery query, String authorization) {
        String tenant = tenantOf(user);
        AlertQuery q = query == null
                ? new AlertQuery(null, null, null, null, null, null, null) : query;
        List<QaAlert> rows = qaAlertMapper.selectAlerts(
                tenant,
                q.status(),
                q.severity(),
                q.ruleType(),
                blankToNull(q.sessionNo()),
                blankToNull(q.keyword()),
                parseTime(q.startTime(), false),
                parseTime(q.endTime(), true),
                ALERT_LIST_LIMIT);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> userIds = new ArrayList<>();
        for (QaAlert row : rows) {
            if (row.getAgentId() != null) {
                userIds.add(String.valueOf(row.getAgentId()));
            }
            if (row.getHandlerId() != null) {
                userIds.add(String.valueOf(row.getHandlerId()));
            }
        }
        Map<String, String> names = userNameClient.namesOf(userIds, authorization);
        return rows.stream().map(row -> toVO(row, names)).toList();
    }

    /** 某个会话的告警：坐席切到这条会话时拉一次，之前错过的预警也不会漏。 */
    public List<AlertVO> alertsOfSession(LoginUser user, String sessionNo, String authorization) {
        return alerts(user, new AlertQuery(null, null, null, sessionNo, null, null, null), authorization);
    }

    /**
     * 查询时间：前端传 "2026-09-16" 或 "2026-09-16 08:30:00"，只给日期时按当天首/末补齐。
     *
     * @param endOfDay 只给日期时是否按当天最后一秒
     */
    private LocalDateTime parseTime(String value, boolean endOfDay) {
        String text = blankToNull(value);
        if (text == null) {
            return null;
        }
        text = text.replace('T', ' ').trim();
        if (text.length() == 10) {
            text = text + (endOfDay ? " 23:59:59" : " 00:00:00");
        } else if (text.length() == 16) {
            text = text + ":00";
        }
        if (text.length() > 19) {
            text = text.substring(0, 19);
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            throw new BizException(40001, "时间格式不正确，应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
        }
    }

    /** 标记告警已处理。 */
    @Transactional
    public AlertVO handle(LoginUser user, String id, String remark, String authorization) {
        String tenant = tenantOf(user);
        QaAlert alert = qaAlertMapper.selectById(Long.parseLong(id));
        if (alert == null || !tenant.equals(alert.getTenantCode())
                || Boolean.TRUE.equals(alert.getDeleted())) {
            throw new BizException(40401, "告警不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        QaAlert update = new QaAlert();
        update.setId(alert.getId());
        update.setStatus(ALERT_HANDLED);
        update.setHandlerId(user.userId());
        update.setHandleRemark(remark);
        update.setHandleTime(now);
        update.setEditor(String.valueOf(user.userId()));
        update.setUpdateTime(now);
        qaAlertMapper.updateById(update);

        alert.setStatus(ALERT_HANDLED);
        alert.setHandlerId(user.userId());
        alert.setHandleRemark(remark);
        alert.setHandleTime(now);
        return toVO(alert, userNameClient.namesOf(List.of(String.valueOf(user.userId())), authorization));
    }

    /** 告警总览：质检中心的实时预警卡片用。 */
    public AlertStat stat(String tenantCode) {
        Map<String, Object> row = qaAlertMapper.selectAlertStat(tenantCode);
        if (row == null) {
            return new AlertStat(0, 0, 0, 0);
        }
        return new AlertStat(longValue(row.get("total")), longValue(row.get("pending")),
                longValue(row.get("severe")), longValue(row.get("today")));
    }

    /** 参与实时质检的规则：启用的 + 打开实时开关的 */
    private List<QaRule> realtimeRules(String tenantCode) {
        return qaRuleMapper.selectList(Wrappers.<QaRule>lambdaQuery()
                .eq(QaRule::getTenantCode, tenantCode)
                .eq(QaRule::getDeleted, false)
                .eq(QaRule::getIsEnabled, true)
                .eq(QaRule::getIsRealtime, true));
    }

    /** 三类实时规则判定：命中返回 Hit，没命中返回 null */
    private Hit detect(QaRule rule, String content, boolean fromAgent, SessionMessage previousCustomer) {
        int type = rule.getRuleType() == null ? 0 : rule.getRuleType();
        int severity = rule.getSeverity() == null ? SEVERITY_WARN : rule.getSeverity();
        if (type == RULE_KEYWORD) {
            // 敏感词：客户和客服说的话都查，客服骂人更要拦
            String keyword = firstMatch(content, keywordsOf(rule));
            if (keyword == null) {
                return null;
            }
            return new Hit(keyword, snippetAround(content, content.indexOf(keyword)),
                    ADVICE_KEYWORD, severity);
        }
        if (type == RULE_PROMISE) {
            // 只查客服说的话：客户问"你保证能修好吗"不该算客服违规
            if (!fromAgent) {
                return null;
            }
            String phrase = firstMatch(content, PROMISE_PHRASES);
            if (phrase == null) {
                return null;
            }
            return new Hit(phrase, snippetAround(content, content.indexOf(phrase)),
                    ADVICE_PROMISE, severity);
        }
        if (type == RULE_EMOTION) {
            // 情绪安抚只查"坐席回复"这一侧：客户在发火、坐席却没安抚，才需要提醒。
            // 而且判据要严一点——先看客户上一条是不是**真的**情绪激动（见 isUpset）
            if (!fromAgent || previousCustomer == null) {
                return null;
            }
            String customerText = previousCustomer.getContent();
            String signal = upsetSignal(customerText);
            if (signal == null) {
                return null;
            }
            if (containsAny(content, SOOTHING_WORDS)) {
                return null;
            }
            return new Hit(signal, snippetAround(customerText, customerText.indexOf(signal)),
                    ADVICE_EMOTION, severity);
        }
        return null;
    }

    /**
     * 客户上一条到底算不算"情绪激动"，命中就返回信号词（用于展示），否则返回 null。
     *
     * <p>两档判据：</p>
     * <ul>
     *   <li><b>强信号</b>：投诉 / 差评 / 骗子 / 气死… 这类词本身就说明在发火，直接算；</li>
     *   <li><b>一般不满 + 强调语气</b>："怎么回事""还没"这类词单独出现太常见（"退款还没到"就是正常咨询），
     *       必须配合连续感叹号/问号才算激动，避免把普通追问当成吵架。</li>
     * </ul>
     */
    private String upsetSignal(String customerMessage) {
        String strong = firstMatch(customerMessage, ANGER_WORDS);
        if (strong != null) {
            return strong;
        }
        String weak = firstMatch(customerMessage, UNHAPPY_WORDS);
        if (weak != null && EMPHATIC.matcher(customerMessage).find()) {
            return weak;
        }
        return null;
    }

    /** 敏感词规则的命中词表：优先用配置的命中词，没配就从规则内容的引号里抠 */
    private List<String> keywordsOf(QaRule rule) {
        String raw = rule.getHitKeywords();
        List<String> words = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            Matcher matcher = QUOTED.matcher(rule.getRuleContent() == null ? "" : rule.getRuleContent());
            while (matcher.find()) {
                words.add(matcher.group(1));
            }
            return words;
        }
        for (String part : raw.split("[,，、;；\\s]+")) {
            if (!part.isBlank()) {
                words.add(part.trim());
            }
        }
        return words;
    }

    /** 坐席这条消息之前、客户最近说过的一句（用来判断"客户是不是在发火"） */
    private SessionMessage lastCustomerMessage(String tenantCode, Long sessionId, Long beforeId) {
        List<SessionMessage> rows = sessionMessageMapper.selectBySession(
                tenantCode, sessionId, beforeId, SessionService.VISIBLE_ALL, CONTEXT_LOOKBACK);
        for (SessionMessage row : rows) {
            if (Integer.valueOf(SessionService.SENDER_CUSTOMER).equals(row.getSenderType())
                    && row.getContent() != null && !row.getContent().isBlank()) {
                return row;
            }
        }
        return null;
    }

    private String firstMatch(String text, List<String> words) {
        if (text == null || words == null) {
            return null;
        }
        for (String word : words) {
            if (word != null && !word.isBlank() && text.contains(word)) {
                return word;
            }
        }
        return null;
    }

    private boolean containsAny(String text, List<String> words) {
        return firstMatch(text, words) != null;
    }

    /** 命中片段：截取命中词前后的上下文，让坐席一眼看到是哪句话 */
    private String snippetAround(String content, int index) {
        if (content == null) {
            return null;
        }
        int start = Math.max(0, (index < 0 ? 0 : index) - 20);
        int end = Math.min(content.length(), start + SNIPPET_LIMIT);
        return (start > 0 ? "..." : "") + content.substring(start, end)
                + (end < content.length() ? "..." : "");
    }

    /** 推给坐席：customer-service → 实时网关 → 该会话的坐席连接 */
    private void push(String tenantCode, Session session, AlertVO alert) {
        pushAlert(tenantCode, session.getSessionNo(), session.getAgentId(), alert);
    }

    private void pushAlert(String tenantCode, String sessionNo, Long agentId, AlertVO alert) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("alert", alert);
        notifyClient.notifyQaAlert(tenantCode, sessionNo, agentId, payload);
    }

    /**
     * 响应超时提醒：每 25 秒扫一遍"客户在等、坐席还没回"的会话。
     *
     * <p>和敏感词那些不一样——那些是"这句话有问题"，这个是"这句话晾太久了"，
     * 没有新消息可触发，所以只能定时扫。判定标准是"该会话最后一条有效消息是客户发的、
     * 且已经超过该租户配置的响应超时秒数"。同一条客户消息只提醒一次
     * （靠 (消息, 规则) 唯一索引兜底），坐席回了就自动不再提醒。</p>
     */
    @Scheduled(fixedDelay = 25_000L, initialDelay = 30_000L)
    public void scheduledTimeoutScan() {
        scanUnansweredSessions();
    }

    /**
     * 扫一遍"客户在等"的会话（定时任务每 25 秒调一次；也可以手动触发来排障）。
     *
     * @return 本轮扫到多少条候选、实际新增多少条提醒
     */
    public TimeoutScanResult scanUnansweredSessions() {
        LocalDateTime oldest = LocalDateTime.now().minusSeconds(TIMEOUT_SCAN_FLOOR_SECONDS);
        List<Map<String, Object>> rows;
        try {
            rows = sessionMapper.selectUnansweredSessions(oldest, TIMEOUT_SCAN_LIMIT);
        } catch (Exception e) {
            log.warn("响应超时扫描失败 error={}", e.getMessage());
            return new TimeoutScanResult(0, 0);
        }
        if (rows == null || rows.isEmpty()) {
            return new TimeoutScanResult(0, 0);
        }
        Map<String, QaRule> ruleCache = new HashMap<>();
        int alerted = 0;
        for (Map<String, Object> row : rows) {
            try {
                if (raiseTimeoutAlert(row, ruleCache)) {
                    alerted++;
                }
            } catch (Exception e) {
                log.warn("响应超时提醒失败 tenant={} sessionNo={} error={}",
                        row.get("tenant_code"), row.get("session_no"), e.getMessage());
            }
        }
        if (alerted > 0) {
            log.info("响应超时提醒本轮新增 {} 条（候选 {} 条）", alerted, rows.size());
        }
        return new TimeoutScanResult(rows.size(), alerted);
    }

    /** 单条会话的超时判定；命中就落一条告警并推给坐席 */
    private boolean raiseTimeoutAlert(Map<String, Object> row, Map<String, QaRule> ruleCache) {
        String tenantCode = stringOf(row.get("tenant_code"));
        String sessionNo = stringOf(row.get("session_no"));
        if (tenantCode == null || sessionNo == null) {
            return false;
        }
        QaRule rule = ruleCache.computeIfAbsent(tenantCode, this::timeoutRuleOf);
        if (rule == null) {
            // 租户没配"响应超时"规则就不打扰
            return false;
        }
        LocalDateTime sendTime = timeOf(row.get("send_time"));
        if (sendTime == null) {
            return false;
        }
        long waited = Duration.between(sendTime, LocalDateTime.now()).getSeconds();
        int timeout = rule.getTimeoutSeconds() == null ? 60 : rule.getTimeoutSeconds();
        if (timeout <= 0 || waited < timeout) {
            return false;
        }
        // 1-排队中（还没人接入）、3-接待中（接了没回）：两种"等"给不同的建议
        int sessionStatus = intValue(row.get("session_status"));
        String advice = sessionStatus == SessionService.STATUS_QUEUING
                ? "客户已等待 " + waited + " 秒仍没有坐席接入，请尽快接入。"
                : "客户发出消息后已等待 " + waited + " 秒仍没有回复，请尽快响应。";
        LocalDateTime now = LocalDateTime.now();
        Long agentId = longOrNull(row.get("agent_id"));
        QaAlert alert = QaAlert.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenantCode)
                .sessionId(longValue(row.get("session_id")))
                .sessionNo(sessionNo)
                .agentId(agentId)
                .messageId(longValue(row.get("message_id")))
                .messageSeq(longValue(row.get("message_seq")))
                .ruleId(rule.getId())
                .ruleName(rule.getRuleName())
                .ruleType(RULE_TIMEOUT)
                .severity(rule.getSeverity() == null ? SEVERITY_WARN : rule.getSeverity())
                .snippet(snippetAround(stringOf(row.get("content")), 0))
                .advice(advice)
                .status(ALERT_PENDING)
                .createTime(now)
                .updateTime(now)
                .creator("REALTIME_QA")
                .deleted(false)
                .build();
        if (qaAlertMapper.insertIgnore(alert) == 0) {
            // 这条客户消息已经提醒过了（坐席还没回），不重复刷
            return false;
        }
        pushAlert(tenantCode, sessionNo, agentId, toVO(alert, Map.of()));
        log.info("响应超时提醒 tenant={} sessionNo={} agentId={} 已等待={}秒",
                tenantCode, sessionNo, agentId, waited);
        return true;
    }

    /** 租户的"响应超时"规则（没有就返回 null：宁可不提醒，也不要硬套一个默认值打扰坐席） */
    private QaRule timeoutRuleOf(String tenantCode) {
        return qaRuleMapper.selectOne(Wrappers.<QaRule>lambdaQuery()
                .eq(QaRule::getTenantCode, tenantCode)
                .eq(QaRule::getRuleType, RULE_TIMEOUT)
                .eq(QaRule::getDeleted, false)
                .eq(QaRule::getIsEnabled, true)
                .eq(QaRule::getIsRealtime, true)
                .orderByAsc(QaRule::getId)
                .last("LIMIT 1"));
    }

    private AlertVO toVO(QaAlert alert, Map<String, String> names) {
        int type = alert.getRuleType() == null ? 0 : alert.getRuleType();
        int severity = alert.getSeverity() == null ? SEVERITY_WARN : alert.getSeverity();
        int status = alert.getStatus() == null ? ALERT_PENDING : alert.getStatus();
        String agentName = alert.getAgentId() == null
                ? null : names.get(String.valueOf(alert.getAgentId()));
        String handlerName = alert.getHandlerId() == null
                ? null : names.get(String.valueOf(alert.getHandlerId()));
        return new AlertVO(
                String.valueOf(alert.getId()),
                alert.getSessionNo(),
                alert.getAgentId() == null ? null : String.valueOf(alert.getAgentId()),
                agentName,
                alert.getRuleName(),
                type,
                typeText(type),
                severity,
                severityText(severity),
                alert.getHitKeyword(),
                alert.getSnippet(),
                alert.getAdvice(),
                status,
                status == ALERT_HANDLED ? "已处理" : "待处理",
                handlerName,
                alert.getHandleRemark(),
                alert.getMessageSeq(),
                alert.getCreateTime(),
                alert.getHandleTime());
    }

    private String typeText(int type) {
        if (type == RULE_KEYWORD) {
            return "敏感词";
        }
        if (type == RULE_PROMISE) {
            return "承诺规范";
        }
        if (type == 3) {
            return "必答项";
        }
        if (type == RULE_EMOTION) {
            return "情绪识别";
        }
        return "其它";
    }

    private String severityText(int severity) {
        if (severity == SEVERITY_SEVERE) {
            return "严重";
        }
        return severity == SEVERITY_TIP ? "提示" : "警告";
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String stringOf(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private Long longOrNull(Object value) {
        return value == null ? null : longValue(value);
    }

    /** Map 查询里 TIMESTAMP 会映射成 java.sql.Timestamp，这里统一转 LocalDateTime */
    private LocalDateTime timeOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    private int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    /** 告警视图（质检中心列表 / 工作台提示共用） */
    public record AlertVO(
            String id,
            String sessionNo,
            String agentId,
            String agentName,
            String ruleName,
            int ruleType,
            String ruleTypeText,
            int severity,
            String severityText,
            String hitKeyword,
            String snippet,
            String advice,
            int status,
            String statusText,
            String handlerName,
            String handleRemark,
            Long messageSeq,
            LocalDateTime createTime,
            LocalDateTime handleTime
    ) {
    }

    /** 告警总览 */
    public record AlertStat(long total, long pending, long severe, long today) {
    }

    /** 告警查询条件（都为 null 表示不过滤） */
    public record AlertQuery(
            Integer status,
            Integer severity,
            Integer ruleType,
            String sessionNo,
            String keyword,
            String startTime,
            String endTime
    ) {
    }

    /** 一次命中 */
    private record Hit(String keyword, String snippet, String advice, int severity) {
    }

    /**
     * 响应超时扫描结果。
     *
     * @param scanned 本轮扫到多少条"客户在等"的会话
     * @param alerted 实际新增多少条提醒（重复的、没到点的都不算）
     */
    public record TimeoutScanResult(int scanned, int alerted) {
    }
}
