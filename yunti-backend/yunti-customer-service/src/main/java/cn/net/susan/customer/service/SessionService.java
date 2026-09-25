package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.Channel;
import cn.net.susan.customer.entity.ChannelKey;
import cn.net.susan.customer.entity.Customer;
import cn.net.susan.customer.entity.Session;
import cn.net.susan.customer.entity.SessionMessage;
import cn.net.susan.customer.mapper.ChannelKeyMapper;
import cn.net.susan.customer.mapper.ChannelMapper;
import cn.net.susan.customer.mapper.CustomerMapper;
import cn.net.susan.customer.mapper.SessionMapper;
import cn.net.susan.customer.mapper.SessionMessageMapper;
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

    private static final int DEFAULT_HISTORY_LIMIT = 30;
    private static final int MAX_HISTORY_LIMIT = 100;
    private static final int SESSION_LIST_LIMIT = 100;

    private final SessionMapper sessionMapper;
    private final SessionMessageMapper sessionMessageMapper;
    private final CustomerMapper customerMapper;
    private final ChannelMapper channelMapper;
    private final ChannelKeyMapper channelKeyMapper;
    private final VisitorTokenService visitorTokenService;
    private final SnowflakeIdGenerator idGenerator;

    public SessionService(
            SessionMapper sessionMapper,
            SessionMessageMapper sessionMessageMapper,
            CustomerMapper customerMapper,
            ChannelMapper channelMapper,
            ChannelKeyMapper channelKeyMapper,
            VisitorTokenService visitorTokenService,
            SnowflakeIdGenerator idGenerator
    ) {
        this.sessionMapper = sessionMapper;
        this.sessionMessageMapper = sessionMessageMapper;
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

        String tenant = channelKey.getTenantCode();
        Customer customer = resolveVisitor(tenant, command.visitorKey(), command.visitorName(), channel.getName());

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
        return new OpenResult(
                session.getSessionNo(),
                customer.getCustomerNo(),
                customer.getName(),
                token,
                visitorTokenService.expireAt(),
                session.getStatus(),
                created
        );
    }

    /**
     * 坐席工作台：我的会话列表（可按状态与关键词过滤）。
     */
    public List<SessionVO> agentSessions(LoginUser user, Integer status, String keyword, Long agentId) {
        String tenant = tenantOf(user);
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        List<Map<String, Object>> rows = sessionMapper.selectAgentSessions(tenant, status, normalizedKeyword, SESSION_LIST_LIMIT);
        List<SessionVO> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            SessionVO vo = toSessionVO(row);
            // agentOnly=true 时只保留"分配给我"的会话，用于工作台的"我的会话"筛选
            if (agentId != null && !agentId.equals(vo.agentId())) {
                continue;
            }
            result.add(vo);
        }
        return result;
    }

    /**
     * 坐席查看历史消息（按会话号，校验租户）。
     */
    public List<MessageVO> history(LoginUser user, String sessionNo, Long beforeId, Integer limit) {
        String tenant = tenantOf(user);
        Session session = requireSession(tenant, sessionNo);
        return historyOf(tenant, session, beforeId, limit);
    }

    /**
     * 内部接口：按租户 + 会话号拉历史消息（实时网关在 JOIN 时回给前端）。
     */
    public List<MessageVO> historyByTenant(String tenantCode, String sessionNo, Long beforeId, Integer limit) {
        Session session = requireSession(tenantCode, sessionNo);
        return historyOf(tenantCode, session, beforeId, limit);
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
        Session session = requireSession(tenantCode, sessionNo);
        if (Integer.valueOf(STATUS_CLOSED).equals(session.getStatus())) {
            throw new BizException(40301, "会话已结束，无法继续发送消息");
        }
        SessionMessage message = buildMessage(tenantCode, session.getId(), senderType, senderId, msgType, content);
        sessionMessageMapper.insert(message);

        LocalDateTime now = LocalDateTime.now();
        Session update = new Session();
        update.setId(session.getId());
        update.setUpdateTime(now);
        // 坐席开口即视为接入，会话从"排队中"进入"人工接待"
        if (senderType == SENDER_AGENT && session.getAgentId() == null) {
            update.setAgentId(senderId);
            update.setStatus(STATUS_AGENT);
        }
        sessionMapper.updateById(update);
        touchCustomer(tenantCode, session.getCustomerId(), now);
        return toMessageVO(message);
    }

    /**
     * 内部接口：坐席接入会话（认领）。
     */
    @Transactional
    public Session assignAgent(String tenantCode, String sessionNo, Long agentId) {
        Session session = requireSession(tenantCode, sessionNo);
        if (Integer.valueOf(STATUS_CLOSED).equals(session.getStatus())) {
            throw new BizException(40301, "会话已结束");
        }
        if (session.getAgentId() != null && !session.getAgentId().equals(agentId)) {
            throw new BizException(40301, "该会话已由其他客服接待");
        }
        Session update = new Session();
        update.setId(session.getId());
        update.setAgentId(agentId);
        update.setStatus(STATUS_AGENT);
        update.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(update);
        session.setAgentId(agentId);
        session.setStatus(STATUS_AGENT);
        log.info("坐席接入会话 tenant={} sessionNo={} agentId={}", tenantCode, sessionNo, agentId);
        return session;
    }

    /**
     * 内部接口：结束会话。
     */
    @Transactional
    public Session closeSession(String tenantCode, String sessionNo) {
        Session session = requireSession(tenantCode, sessionNo);
        LocalDateTime now = LocalDateTime.now();
        Session update = new Session();
        update.setId(session.getId());
        update.setStatus(STATUS_CLOSED);
        update.setEndTime(now);
        update.setUpdateTime(now);
        sessionMapper.updateById(update);
        session.setStatus(STATUS_CLOSED);
        session.setEndTime(now);
        log.info("会话已结束 tenant={} sessionNo={}", tenantCode, sessionNo);
        return session;
    }

    private List<MessageVO> historyOf(String tenant, Session session, Long beforeId, Integer limit) {
        int size = limit == null || limit <= 0 ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
        List<SessionMessage> rows = sessionMessageMapper.selectBySession(tenant, session.getId(), beforeId, size);
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
        sessionMessageMapper.insert(buildMessage(tenant, session.getId(), SENDER_SYSTEM, null, 5, content));
    }

    private SessionMessage buildMessage(
            String tenant,
            Long sessionId,
            int senderType,
            Long senderId,
            int msgType,
            String content
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
                .sendTime(now)
                .createTime(now)
                .updateTime(now)
                .deleted(false)
                .build();
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
     * 访客建档：visitorKey 由访客端本地保存，用来复用同一个客户档案。
     */
    private Customer resolveVisitor(String tenant, String visitorKey, String visitorName, String channelName) {
        String customerNo = visitorKey == null || visitorKey.isBlank() ? generateCustomerNo() : visitorKey.trim();
        Customer existing = customerMapper.selectOne(Wrappers.<Customer>lambdaQuery()
                .eq(Customer::getTenantCode, tenant)
                .eq(Customer::getCustomerNo, customerNo)
                .eq(Customer::getDeleted, false)
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        Customer customer = Customer.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenant)
                .customerNo(customerNo)
                .name(visitorName == null || visitorName.isBlank() ? "访客" + customerNo.substring(Math.max(0, customerNo.length() - 4)) : visitorName.trim())
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
        return "V" + LocalDateTime.now().format(NO_TIME) + String.format("%03d", RANDOM.nextInt(1000));
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
                message.getSessionId(),
                message.getSenderType(),
                message.getSenderId(),
                message.getMsgType(),
                message.getContent(),
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
    public record OpenCommand(String appKey, String visitorKey, String visitorName) {
    }

    /** 访客开会话结果 */
    public record OpenResult(
            String sessionNo,
            String customerNo,
            String customerName,
            String visitorToken,
            LocalDateTime tokenExpireAt,
            int sessionStatus,
            boolean created
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
            Long sessionId,
            Integer senderType,
            Long senderId,
            Integer msgType,
            String content,
            LocalDateTime sendTime
    ) {
    }
}
