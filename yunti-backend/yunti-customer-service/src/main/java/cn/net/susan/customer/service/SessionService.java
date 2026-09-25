package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.Channel;
import cn.net.susan.customer.entity.ChannelKey;
import cn.net.susan.customer.entity.Customer;
import cn.net.susan.customer.entity.Session;
import cn.net.susan.customer.entity.SessionEvent;
import cn.net.susan.customer.entity.SessionMessage;
import cn.net.susan.customer.mapper.ChannelKeyMapper;
import cn.net.susan.customer.mapper.ChannelMapper;
import cn.net.susan.customer.mapper.CustomerMapper;
import cn.net.susan.customer.mapper.SessionMapper;
import cn.net.susan.customer.mapper.SessionMessageMapper;
import cn.net.susan.customer.mapper.SessionEventMapper;
import cn.net.susan.customer.security.VisitorTokenService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 会话与消息：实时通信底座的业务落库方。
 *
 * <p>职责划分：长连接、心跳、广播由 yunti-realtime-service 负责；
 * 会话与消息的"唯一写入口"在 customer-service，避免多服务同时写客户库。</p>
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private static final DateTimeFormatter NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 匿名客户编号用的字符集：去掉 I / L / O / U 这些容易看错的字母 */
    private static final char[] CUSTOMER_NO_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    /** 匿名客户编号随机部分长度：26 × 5bit = 130 位，撞不出来也猜不出来 */
    private static final int CUSTOMER_NO_RANDOM_LENGTH = 26;

    /** 会话状态码 */
    public static final int STATUS_QUEUING = 1;
    public static final int STATUS_BOT = 2;
    public static final int STATUS_AGENT = 3;
    public static final int STATUS_CLOSED = 4;

    /** 消息发送方码：1-客户、2-坐席、3-机器人、4-系统 */
    public static final int SENDER_CUSTOMER = 1;
    public static final int SENDER_AGENT = 2;
    public static final int SENDER_BOT = 3;
    public static final int SENDER_SYSTEM = 4;

    /** 可见范围码：1-客户与坐席都可见、2-仅坐席可见（内部备注） */
    public static final int VISIBLE_ALL = 1;
    public static final int VISIBLE_AGENT_ONLY = 2;

    /** 会话事件类型码：1-转接、2-升级、3-分配、4-关闭、5-超时 */
    public static final int EVENT_TRANSFER = 1;
    public static final int EVENT_ASSIGN = 3;
    public static final int EVENT_CLOSE = 4;

    /** 退回队列时事件里的目标值 */
    private static final String QUEUE_TARGET = "QUEUE";

    private static final int DEFAULT_HISTORY_LIMIT = 30;
    private static final int MAX_HISTORY_LIMIT = 100;
    /** 增量补拉的默认/最大条数：断线补偿一次最多拉这么多，剩下靠下一次轮询继续追 */
    private static final int DEFAULT_SYNC_LIMIT = 100;
    private static final int MAX_SYNC_LIMIT = 500;
    private static final int SESSION_LIST_LIMIT = 100;
    private static final int EVENT_LIST_LIMIT = 20;

    private final SessionMapper sessionMapper;
    private final SessionMessageMapper sessionMessageMapper;
    private final SessionEventMapper sessionEventMapper;
    private final CustomerMapper customerMapper;
    private final ChannelMapper channelMapper;
    private final ChannelKeyMapper channelKeyMapper;
    private final VisitorTokenService visitorTokenService;
    private final SnowflakeIdGenerator idGenerator;

    public SessionService(
            SessionMapper sessionMapper,
            SessionMessageMapper sessionMessageMapper,
            SessionEventMapper sessionEventMapper,
            CustomerMapper customerMapper,
            ChannelMapper channelMapper,
            ChannelKeyMapper channelKeyMapper,
            VisitorTokenService visitorTokenService,
            SnowflakeIdGenerator idGenerator
    ) {
        this.sessionMapper = sessionMapper;
        this.sessionMessageMapper = sessionMessageMapper;
        this.sessionEventMapper = sessionEventMapper;
        this.customerMapper = customerMapper;
        this.channelMapper = channelMapper;
        this.channelKeyMapper = channelKeyMapper;
        this.visitorTokenService = visitorTokenService;
        this.idGenerator = idGenerator;
    }

    /**
     * 访客打开会话：渠道密钥校验 → 客户建档/复用 → 开会话/复用未结束会话 → 发访客令牌。
     */
    @Transactional
    public OpenResult openSession(OpenCommand command) {
        if (command.appKey() == null || command.appKey().isBlank()) {
            throw new BizException(40001, "缺少渠道密钥");
        }
        ChannelKey channelKey = channelKeyMapper.selectOne(Wrappers.<ChannelKey>lambdaQuery()
                .eq(ChannelKey::getAppKey, command.appKey().trim())
                .eq(ChannelKey::getStatus, 1)
                .eq(ChannelKey::getDeleted, false)
                .last("LIMIT 1"));
        if (channelKey == null) {
            throw new BizException(40401, "渠道密钥无效或已停用");
        }
        if (channelKey.getExpireTime() != null && channelKey.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BizException(40301, "渠道密钥已过期，请联系企业管理员重新生成");
        }
        Channel channel = channelMapper.selectById(channelKey.getChannelId());
        if (channel == null || Boolean.TRUE.equals(channel.getDeleted())) {
            throw new BizException(40401, "渠道不存在");
        }
        if (!Integer.valueOf(1).equals(channel.getStatus())) {
            throw new BizException(40301, "渠道已停用，暂时无法接待");
        }
        requireOriginAllowed(channel, command.origin());

        String tenant = channelKey.getTenantCode();
        Customer customer = resolveVisitor(tenant, command.visitorToken(), command.visitorName(), channel.getName());

        Session session = sessionMapper.selectOpenSession(tenant, channel.getId(), customer.getId());
        boolean created = false;
        if (session == null) {
            session = createSession(tenant, channel, customer);
            created = true;
            appendSystemMessage(tenant, session, "访客进入会话，等待客服接入");
            log.info("访客会话创建 tenant={} sessionNo={} channel={} customerNo={}",
                    tenant, session.getSessionNo(), channel.getName(), customer.getCustomerNo());
        }

        String token = visitorTokenService.createToken(
                customer.getId(), customer.getName(), tenant, session.getSessionNo());
        String identityToken = visitorTokenService.createIdentityToken(
                customer.getId(), customer.getCustomerNo(), customer.getName(), tenant);
        return new OpenResult(
                session.getSessionNo(),
                customer.getCustomerNo(),
                customer.getName(),
                token,
                visitorTokenService.expireAt(),
                session.getStatus(),
                created,
                identityToken
        );
    }

    /**
     * 坐席工作台：我的会话列表（可按状态与关键词过滤）。
     */
    public List<SessionVO> agentSessions(LoginUser user, String scope, String keyword) {
        String tenant = tenantOf(user);
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        String normalizedScope = scope == null || scope.isBlank() ? "all" : scope.trim().toLowerCase();
        Long agentId = "mine".equals(normalizedScope) ? user.userId() : null;
        boolean unassigned = "queue".equals(normalizedScope);
        List<Map<String, Object>> rows = sessionMapper.selectAgentSessions(
                tenant, normalizedKeyword, agentId, unassigned, SESSION_LIST_LIMIT);
        List<SessionVO> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            result.add(toSessionVO(row));
        }
        return result;
    }

    /**
     * 坐席查看历史消息（按会话号，校验租户）。
     */
    public List<MessageVO> history(LoginUser user, String sessionNo, Long beforeId, Integer limit) {
        String tenant = tenantOf(user);
        Session session = requireSession(tenant, sessionNo);
        // 坐席能看到全部消息，包括内部备注
        return historyOf(tenant, session, beforeId, limit, null);
    }

    /**
     * 内部接口：按租户 + 会话号拉历史消息（实时网关在 JOIN 时回给前端）。
     */
    public List<MessageVO> historyByTenant(
            String tenantCode,
            String sessionNo,
            Long beforeId,
            Integer limit,
            boolean agentView
    ) {
        Session session = requireSession(tenantCode, sessionNo);
        // 访客视角过滤掉内部备注；坐席视角全都能看
        return historyOf(tenantCode, session, beforeId, limit, agentView ? null : VISIBLE_ALL);
    }

    /**
     * 拉取会话详情（坐席工作台右侧头部信息）。
     */
    public SessionVO sessionDetail(LoginUser user, String sessionNo) {
        String tenant = tenantOf(user);
        Session session = requireSession(tenant, sessionNo);
        Customer customer = session.getCustomerId() == null ? null : customerMapper.selectOne(
                Wrappers.<Customer>lambdaQuery()
                        .eq(Customer::getId, session.getCustomerId())
                        .eq(Customer::getTenantCode, tenant)
                        .eq(Customer::getDeleted, false));
        return new SessionVO(
                session.getSessionNo(),
                session.getStatus(),
                session.getAgentId(),
                session.getChannelId(),
                session.getCustomerId(),
                customer == null ? null : customer.getName(),
                customer == null ? null : customer.getLevel(),
                session.getSource(),
                session.getIntent(),
                session.getEmotion(),
                session.getStartTime(),
                session.getEndTime(),
                null,
                null,
                null,
                0
        );
    }

    /**
     * 内部接口：按租户 + 会话号取会话（实时网关用）。
     */
    public Session requireSession(String tenantCode, String sessionNo) {
        if (tenantCode == null || tenantCode.isBlank() || sessionNo == null || sessionNo.isBlank()) {
            throw new BizException(40001, "缺少租户或会话号");
        }
        Session session = sessionMapper.selectOne(Wrappers.<Session>lambdaQuery()
                .eq(Session::getTenantCode, tenantCode)
                .eq(Session::getSessionNo, sessionNo)
                .eq(Session::getDeleted, false)
                .last("LIMIT 1"));
        if (session == null) {
            throw new BizException(40401, "会话不存在");
        }
        return session;
    }

    /**
     * 内部接口：落一条消息。
     */
    @Transactional
    public MessageVO appendMessage(
            String tenantCode,
            String sessionNo,
            int senderType,
            Long senderId,
            int msgType,
            String content
    ) {
        return appendMessage(tenantCode, sessionNo, senderType, senderId, msgType, content, VISIBLE_ALL);
    }

    /**
     * 内部接口：落一条消息（带可见范围，内部备注用 {@code VISIBLE_AGENT_ONLY}）。
     */
    @Transactional
    public MessageVO appendMessage(
            String tenantCode,
            String sessionNo,
            int senderType,
            Long senderId,
            int msgType,
            String content,
            int visibleTo
    ) {
        return appendMessage(tenantCode, sessionNo, senderType, senderId, msgType, content, visibleTo, null);
    }

    /**
     * 内部接口：落一条消息（消息必达版）。
     *
     * <p>两条保证：</p>
     * <ol>
     *   <li><b>幂等</b>：带 {@code clientMsgNo} 时先按 (租户, 会话, clientMsgNo) 查一次，
     *       已经落过就直接把原消息返回——客户端断线重发、连发两次都只会有一条；</li>
     *   <li><b>时序</b>：序号由数据库 {@code UPDATE ... RETURNING} 自增分配，
     *       同一会话内严格递增，双方按序号排序就不会出现顺序不一致。</li>
     * </ol>
     */
    @Transactional
    public MessageVO appendMessage(
            String tenantCode,
            String sessionNo,
            int senderType,
            Long senderId,
            int msgType,
            String content,
            int visibleTo,
            String clientMsgNo
    ) {
        Session session = requireSession(tenantCode, sessionNo);
        if (sessionMapper.lockSessionForMessage(tenantCode, session.getId()) == null) {
            throw new BizException(40401, "会话不存在");
        }
        session = requireSession(tenantCode, sessionNo);
        String normalizedClientMsgNo = clientMsgNo == null || clientMsgNo.isBlank() ? null : clientMsgNo.trim();
        if (normalizedClientMsgNo != null && normalizedClientMsgNo.length() > 64) {
            throw new BizException(40001, "客户端消息号最长 64 字符");
        }
        if (normalizedClientMsgNo != null) {
            SessionMessage existing = sessionMessageMapper.selectByClientMsgNo(
                    tenantCode, session.getId(), normalizedClientMsgNo);
            if (existing != null) {
                if (!Objects.equals(existing.getSenderType(), senderType)
                        || !Objects.equals(existing.getSenderId(), senderId)
                        || !Objects.equals(existing.getMsgType(), msgType)
                        || !Objects.equals(existing.getVisibleTo(), visibleTo)
                        || !Objects.equals(existing.getContent(), content)) {
                    throw new BizException(40002, "客户端消息号已用于另一条消息");
                }
                log.info("消息重复投递，直接复用已落库的消息 tenant={} sessionNo={} clientMsgNo={} msgNo={}",
                        tenantCode, sessionNo, normalizedClientMsgNo, existing.getMsgNo());
                return toMessageVO(existing);
            }
        }
        if (Integer.valueOf(STATUS_CLOSED).equals(session.getStatus())) {
            throw new BizException(40301, "会话已结束，无法继续发送消息");
        }
        SessionMessage message = buildMessage(
                tenantCode, session.getId(), senderType, senderId, msgType, content, visibleTo);
        message.setClientMsgNo(normalizedClientMsgNo);
        message.setSeq(nextSeq(tenantCode, session.getId()));
        sessionMessageMapper.insert(message);

        LocalDateTime now = LocalDateTime.now();
        touchCustomer(tenantCode, session.getCustomerId(), now);
        return toMessageVO(message);
    }

    /** 分配会话内的下一个消息序号（数据库自增，并发安全） */
    private long nextSeq(String tenantCode, Long sessionId) {
        Long seq = sessionMapper.nextMessageSeq(tenantCode, sessionId);
        if (seq == null) {
            throw new BizException(40401, "会话不存在");
        }
        return seq;
    }

    /**
     * 内部接口：增量补拉——取序号大于 afterSeq 的消息（客户端重连后补齐断线期间漏掉的消息）。
     */
    public List<MessageVO> messagesAfter(
            String tenantCode,
            String sessionNo,
            long afterSeq,
            Integer limit,
            boolean agentView
    ) {
        Session session = requireSession(tenantCode, sessionNo);
        int size = limit == null || limit <= 0 ? DEFAULT_SYNC_LIMIT : Math.min(limit, MAX_SYNC_LIMIT);
        List<SessionMessage> rows = sessionMessageMapper.selectAfterSeq(
                tenantCode, session.getId(), afterSeq, agentView ? null : VISIBLE_ALL, size);
        return rows.stream().map(this::toMessageVO).toList();
    }

    /**
     * 对外接口：工作台按序号补拉（断线重连后补齐当前会话漏掉的消息）。
     */
    public List<MessageVO> messagesAfter(LoginUser user, String sessionNo, long afterSeq, Integer limit) {
        return messagesAfter(tenantOf(user), sessionNo, afterSeq, limit, true);
    }

    /**
     * 内部接口：坐席认领会话（待接待 → 我的）。
     *
     * <p>并发抢单靠"库里已有负责人就拒绝"兜住：两个坐席同时点接入，只有一个能成功。</p>
     */
    @Transactional
    public Session claimSession(String tenantCode, String sessionNo, Long agentId) {
        Session session = requireSession(tenantCode, sessionNo);
        if (Integer.valueOf(STATUS_CLOSED).equals(session.getStatus())) {
            throw new BizException(40301, "会话已结束");
        }
        if (session.getAgentId() != null && !session.getAgentId().equals(agentId)) {
            throw new BizException(40301, "该会话已由其他客服接待");
        }
        if (session.getAgentId() != null) {
            return session;
        }
        if (sessionMapper.claimIfUnassigned(tenantCode, session.getId(), agentId) != 1) {
            Session current = requireSession(tenantCode, sessionNo);
            if (agentId.equals(current.getAgentId())) {
                return current;
            }
            throw new BizException(40301, "该会话已由其他客服接待");
        }
        session.setAgentId(agentId);
        session.setStatus(STATUS_AGENT);
        recordEvent(tenantCode, session.getId(), EVENT_ASSIGN, agentId, QUEUE_TARGET, String.valueOf(agentId), "坐席接入");
        appendSystemMessage(tenantCode, session, "人工客服已接入，很高兴为您服务");
        log.info("坐席认领会话 tenant={} sessionNo={} agentId={}", tenantCode, sessionNo, agentId);
        return session;
    }

    /**
     * 内部接口：退回队列（负责人释放会话，其他人可以再接）。
     */
    @Transactional
    public Session releaseSession(String tenantCode, String sessionNo, Long agentId) {
        Session session = requireSession(tenantCode, sessionNo);
        if (Integer.valueOf(STATUS_CLOSED).equals(session.getStatus())) {
            throw new BizException(40301, "会话已结束");
        }
        if (session.getAgentId() == null || !session.getAgentId().equals(agentId)) {
            throw new BizException(40301, "只有当前接待客服可以退回队列");
        }
        if (sessionMapper.changeAssignment(tenantCode, session.getId(), agentId, null, STATUS_QUEUING) != 1) {
            throw new BizException(40301, "会话负责人已变化，请刷新后重试");
        }
        session.setAgentId(null);
        session.setStatus(STATUS_QUEUING);
        recordEvent(tenantCode, session.getId(), EVENT_TRANSFER, agentId, String.valueOf(agentId), QUEUE_TARGET, "退回队列");
        appendSystemMessage(tenantCode, session, "会话已退回待接待队列");
        log.info("会话退回队列 tenant={} sessionNo={} agentId={}", tenantCode, sessionNo, agentId);
        return session;
    }

    /**
     * 内部接口：把会话转接给另一位坐席。
     */
    @Transactional
    public Session transferSession(
            String tenantCode,
            String sessionNo,
            Long fromAgentId,
            Long toAgentId,
            String remark
    ) {
        if (toAgentId == null) {
            throw new BizException(40001, "请选择要转接的客服");
        }
        if (toAgentId.equals(fromAgentId)) {
            throw new BizException(40001, "不能转接给自己");
        }
        Session session = requireSession(tenantCode, sessionNo);
        if (Integer.valueOf(STATUS_CLOSED).equals(session.getStatus())) {
            throw new BizException(40301, "会话已结束");
        }
        if (session.getAgentId() == null || !session.getAgentId().equals(fromAgentId)) {
            throw new BizException(40301, "只有当前接待客服可以转接");
        }
        if (sessionMapper.changeAssignment(tenantCode, session.getId(), fromAgentId, toAgentId, STATUS_AGENT) != 1) {
            throw new BizException(40301, "会话负责人已变化，请刷新后重试");
        }
        session.setAgentId(toAgentId);
        session.setStatus(STATUS_AGENT);
        recordEvent(tenantCode, session.getId(), EVENT_TRANSFER, fromAgentId,
                String.valueOf(fromAgentId), String.valueOf(toAgentId), trimRemark(remark));
        appendSystemMessage(tenantCode, session, "会话已转接给其他客服，请稍候");
        log.info("会话转接 tenant={} sessionNo={} from={} to={}", tenantCode, sessionNo, fromAgentId, toAgentId);
        return session;
    }

    /**
     * 内部接口：结束会话（可带结束小结）。
     */
    @Transactional
    public Session closeSession(String tenantCode, String sessionNo, Long operatorId, String remark) {
        Session session = requireSession(tenantCode, sessionNo);
        if (Integer.valueOf(STATUS_CLOSED).equals(session.getStatus())) {
            return session;
        }
        LocalDateTime now = LocalDateTime.now();
        Session update = new Session();
        update.setId(session.getId());
        update.setStatus(STATUS_CLOSED);
        update.setEndTime(now);
        update.setUpdateTime(now);
        sessionMapper.updateById(update);
        session.setStatus(STATUS_CLOSED);
        session.setEndTime(now);
        recordEvent(tenantCode, session.getId(), EVENT_CLOSE, operatorId, null, null, trimRemark(remark));
        appendSystemMessage(tenantCode, session, "本次会话已结束，感谢您的咨询");
        log.info("会话已结束 tenant={} sessionNo={} operator={}", tenantCode, sessionNo, operatorId);
        return session;
    }

    /**
     * 会话流转记录（转接 / 分配 / 关闭），工作台用来展示"这单经历了什么"。
     */
    public List<EventVO> events(LoginUser user, String sessionNo) {
        String tenant = tenantOf(user);
        Session session = requireSession(tenant, sessionNo);
        return sessionEventMapper.selectBySession(tenant, session.getId(), EVENT_LIST_LIMIT).stream()
                .map(row -> new EventVO(
                        intValue(row.get("event_type")),
                        longValue(row.get("operator_id")),
                        stringValue(row.get("from_value")),
                        stringValue(row.get("to_value")),
                        stringValue(row.get("remark")),
                        dateValue(row.get("event_time"))))
                .toList();
    }

    /**
     * 内部接口：各坐席当前接待量（工作台展示"谁忙谁闲"，配合在线连接判断负载）。
     */
    public List<AgentLoadVO> agentWorkload(String tenantCode) {
        return sessionEventMapper.selectAgentWorkload(tenantCode).stream()
                .map(row -> new AgentLoadVO(longValue(row.get("agent_id")), intValue(row.get("session_count"))))
                .toList();
    }

    private List<MessageVO> historyOf(String tenant, Session session, Long beforeId, Integer limit, Integer visibleTo) {
        int size = limit == null || limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
        List<SessionMessage> rows = sessionMessageMapper.selectBySession(
                tenant, session.getId(), beforeId, visibleTo, size);
        // SQL 是倒序取的，展示要正序
        Collections.reverse(rows);
        return rows.stream().map(this::toMessageVO).toList();
    }

    private Session createSession(String tenant, Channel channel, Customer customer) {
        LocalDateTime now = LocalDateTime.now();
        for (int attempt = 0; attempt < 5; attempt++) {
            Session session = Session.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenant)
                    .sessionNo(generateSessionNo())
                    .channelId(channel.getId())
                    .customerId(customer.getId())
                    .status(STATUS_QUEUING)
                    .source(channel.getName())
                    .startTime(now)
                    .createTime(now)
                    .updateTime(now)
                    .creator("VISITOR")
                    .deleted(false)
                    .build();
            try {
                sessionMapper.insert(session);
                return session;
            } catch (DuplicateKeyException e) {
                log.warn("会话号冲突，重试第 {} 次", attempt + 1);
            }
        }
        throw new BizException(50001, "会话创建失败，请稍后重试");
    }

    private void appendSystemMessage(String tenant, Session session, String content) {
        SessionMessage message = buildMessage(
                tenant, session.getId(), SENDER_SYSTEM, null, 5, content, VISIBLE_ALL);
        // 系统提示同样要占一个序号，否则客户端按序号排序/补拉时会出现空洞
        message.setSeq(nextSeq(tenant, session.getId()));
        sessionMessageMapper.insert(message);
    }

    private SessionMessage buildMessage(
            String tenant,
            Long sessionId,
            int senderType,
            Long senderId,
            int msgType,
            String content,
            int visibleTo
    ) {
        LocalDateTime now = LocalDateTime.now();
        return SessionMessage.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .sessionId(sessionId)
                .msgNo(generateMsgNo())
                .msgType(msgType)
                .senderType(senderType)
                .senderId(senderId)
                .content(content == null ? "" : content)
                .status(1)
                .visibleTo(visibleTo)
                .sendTime(now)
                .createTime(now)
                .updateTime(now)
                .deleted(false)
                .build();
    }

    /**
     * 记一条会话流转事件（转接 / 分配 / 关闭）。
     */
    private void recordEvent(
            String tenant,
            Long sessionId,
            int eventType,
            Long operatorId,
            String fromValue,
            String toValue,
            String remark
    ) {
        LocalDateTime now = LocalDateTime.now();
        sessionEventMapper.insert(SessionEvent.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .sessionId(sessionId)
                .eventType(eventType)
                .operatorId(operatorId)
                .fromValue(fromValue)
                .toValue(toValue)
                .remark(remark)
                .eventTime(now)
                .createTime(now)
                .build());
    }

    private String trimRemark(String remark) {
        if (remark == null || remark.isBlank()) {
            return null;
        }
        String value = remark.trim();
        return value.length() > 255 ? value.substring(0, 255) : value;
    }

    private void touchCustomer(String tenant, Long customerId, LocalDateTime time) {
        if (customerId == null) {
            return;
        }
        Customer update = new Customer();
        update.setId(customerId);
        update.setLastActive(time);
        update.setUpdateTime(time);
        customerMapper.updateById(update);
    }

    /**
     * 访客建档：只认服务端签发的<b>访客身份令牌</b>，认不出来就当新访客。
     *
     * <p>为什么不再直接用前端回传的客户编号？编号是明文、可枚举的，
     * 别人拿到编号就能顶替这个客户继续会话。改成"签名令牌 + 随机编号"后，
     * 前端只有一个不透明字符串，伪造不了、也猜不到。</p>
     */
    private Customer resolveVisitor(String tenant, String identityToken, String visitorName, String channelName) {
        VisitorTokenService.VisitorIdentity identity = visitorTokenService.parseIdentityToken(identityToken);
        if (identity != null && tenant.equals(identity.tenantCode())) {
            Customer existing = customerMapper.selectById(identity.customerId());
            if (existing != null
                    && !Boolean.TRUE.equals(existing.getDeleted())
                    && tenant.equals(existing.getTenantCode())) {
                return touchVisitor(existing, visitorName);
            }
        }
        String customerNo = generateCustomerNo();
        LocalDateTime now = LocalDateTime.now();
        Customer customer = Customer.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .customerNo(customerNo)
                .name(visitorName == null || visitorName.isBlank() ? "访客" + customerNo.substring(customerNo.length() - 4) : visitorName.trim())
                .level(1)
                .channel(channelName)
                .ordersCount(0)
                .totalValue(java.math.BigDecimal.ZERO)
                .points(0)
                .lastActive(now)
                .createTime(now)
                .updateTime(now)
                .creator("VISITOR")
                .deleted(false)
                .build();
        customerMapper.insert(customer);
        return customer;
    }

    /**
     * 老访客回来：刷新最后活跃时间；如果这次报了新名字，顺手更新档案。
     */
    private Customer touchVisitor(Customer customer, String visitorName) {
        Customer update = new Customer();
        update.setId(customer.getId());
        update.setLastActive(LocalDateTime.now());
        update.setUpdateTime(LocalDateTime.now());
        boolean renamed = false;
        if (visitorName != null && !visitorName.isBlank() && !visitorName.trim().equals(customer.getName())) {
            update.setName(visitorName.trim());
            renamed = true;
        }
        customerMapper.updateById(update);
        if (renamed) {
            customer.setName(update.getName());
        }
        customer.setLastActive(update.getLastActive());
        return customer;
    }

    /**
     * 渠道域名白名单：没配置就不限制（兼容老渠道）；配置了就只放行名单里的来源。
     *
     * <p>规则写法：{@code shop.example.com} 精确匹配；{@code *.example.com} 匹配子域名；
     * {@code localhost:5173} 只放行这个端口；{@code *} 放行全部。多个规则用逗号分隔。</p>
     */
    private void requireOriginAllowed(Channel channel, String origin) {
        String allowed = channel.getAllowedOrigins();
        if (allowed == null || allowed.isBlank()) {
            return;
        }
        String host = hostOf(origin);
        if (host == null) {
            throw new BizException(40301, "该渠道已开启域名白名单，缺少来源信息，无法接入");
        }
        String hostWithPort = hostWithPortOf(origin);
        for (String rule : allowed.split(",")) {
            String candidate = rule.trim().toLowerCase();
            if (candidate.isEmpty()) {
                continue;
            }
            if ("*".equals(candidate) || candidate.equals(host)) {
                return;
            }
            if (candidate.startsWith("*.") && host.endsWith(candidate.substring(1))) {
                return;
            }
            if (candidate.contains(":") && candidate.equals(hostWithPort)) {
                return;
            }
        }
        log.warn("渠道域名未在白名单内 channel={} origin={} allowed={}", channel.getName(), host, allowed);
        throw new BizException(40301, "当前域名未在渠道白名单内，请联系企业管理员");
    }

    /** 从 Origin / Referer 里取出 host（全小写，不带端口、路径、协议）。 */
    private String hostOf(String origin) {
        String hostWithPort = hostWithPortOf(origin);
        if (hostWithPort == null) {
            return null;
        }
        int colon = hostWithPort.indexOf(':');
        return colon >= 0 ? hostWithPort.substring(0, colon) : hostWithPort;
    }

    /** 从 Origin / Referer 里取出 host[:port]（全小写，不带协议与路径）。 */
    private String hostWithPortOf(String origin) {
        if (origin == null || origin.isBlank()) {
            return null;
        }
        String value = origin.trim().toLowerCase();
        int schemeIndex = value.indexOf("://");
        if (schemeIndex >= 0) {
            value = value.substring(schemeIndex + 3);
        }
        int pathIndex = value.indexOf('/');
        if (pathIndex >= 0) {
            value = value.substring(0, pathIndex);
        }
        int at = value.indexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        return value.isBlank() ? null : value;
    }

    private String tenantOf(LoginUser user) {
        if (user == null || user.userType() != 2) {
            throw new BizException(40301, "仅企业成员可使用在线客服");
        }
        String tenant = user.tenantCode();
        if (tenant == null || tenant.isBlank() || "PLATFORM".equals(tenant)) {
            throw new BizException(40301, "请先完成企业开通后再使用在线客服");
        }
        return tenant;
    }

    private String generateSessionNo() {
        return "S" + LocalDateTime.now().format(NO_TIME) + String.format("%03d", RANDOM.nextInt(1000));
    }

    private String generateMsgNo() {
        return "M" + LocalDateTime.now().format(NO_TIME) + String.format("%04d", RANDOM.nextInt(10000));
    }

    private String generateCustomerNo() {
        StringBuilder sb = new StringBuilder(CUSTOMER_NO_RANDOM_LENGTH + 1);
        sb.append('V');
        for (int i = 0; i < CUSTOMER_NO_RANDOM_LENGTH; i++) {
            sb.append(CUSTOMER_NO_ALPHABET[RANDOM.nextInt(CUSTOMER_NO_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private SessionVO toSessionVO(Map<String, Object> row) {
        return new SessionVO(
                stringValue(row.get("session_no")),
                intValue(row.get("status")),
                longValue(row.get("agent_id")),
                longValue(row.get("channel_id")),
                longValue(row.get("customer_id")),
                stringValue(row.get("customer_name")),
                intValue(row.get("customer_level")),
                stringValue(row.get("source")),
                stringValue(row.get("intent")),
                stringValue(row.get("emotion")),
                dateValue(row.get("start_time")),
                dateValue(row.get("end_time")),
                stringValue(row.get("last_content")),
                intValue(row.get("last_sender_type")),
                dateValue(row.get("last_time")),
                intValue(row.get("msg_count"))
        );
    }

    private MessageVO toMessageVO(SessionMessage message) {
        return new MessageVO(
                String.valueOf(message.getId()),
                message.getMsgNo(),
                message.getClientMsgNo(),
                message.getSeq(),
                message.getSessionId(),
                message.getSenderType(),
                message.getSenderId(),
                message.getMsgType(),
                message.getContent(),
                message.getVisibleTo() == null ? VISIBLE_ALL : message.getVisibleTo(),
                message.getSendTime()
        );
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long longValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Integer intValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private LocalDateTime dateValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    /** 访客开会话入参 */
    public record OpenCommand(String appKey, String visitorToken, String visitorName, String origin) {
    }

    /** 访客开会话结果 */
    public record OpenResult(
            String sessionNo,
            String customerNo,
            String customerName,
            String visitorToken,
            LocalDateTime tokenExpireAt,
            int sessionStatus,
            boolean created,
            /** 访客身份令牌（长期）：前端保存，下次打开时回传以复用客户档案 */
            String visitorIdentityToken
    ) {
    }

    /** 会话列表 / 详情 */
    public record SessionVO(
            String sessionNo,
            Integer status,
            Long agentId,
            Long channelId,
            Long customerId,
            String customerName,
            Integer customerLevel,
            String source,
            String intent,
            String emotion,
            LocalDateTime startTime,
            LocalDateTime endTime,
            String lastContent,
            Integer lastSenderType,
            LocalDateTime lastTime,
            Integer msgCount
    ) {
    }

    /** 消息 */
    public record MessageVO(
            String msgId,
            String msgNo,
            /** 客户端消息号：前端据此把"发送中"的气泡换成"已发送" */
            String clientMsgNo,
            /** 会话内序号：双方按它排序，也是增量补拉的游标 */
            Long seq,
            Long sessionId,
            Integer senderType,
            Long senderId,
            Integer msgType,
            String content,
            Integer visibleTo,
            LocalDateTime sendTime
    ) {
    }

    /** 会话流转记录 */
    public record EventVO(
            Integer eventType,
            Long operatorId,
            String fromValue,
            String toValue,
            String remark,
            LocalDateTime eventTime
    ) {
    }

    /** 坐席接待量 */
    public record AgentLoadVO(Long agentId, Integer sessionCount) {
    }
}
