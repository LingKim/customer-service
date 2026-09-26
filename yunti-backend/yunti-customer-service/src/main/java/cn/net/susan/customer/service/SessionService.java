package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.Channel;
import cn.net.susan.customer.entity.ChannelKey;
import cn.net.susan.customer.entity.CsatRecord;
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
import jakarta.annotation.PreDestroy;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** 消息类型码：2-图片（客户发的截图/照片，走视觉识别） */
    public static final int MSG_TYPE_IMAGE = 2;

    /** 消息发送方码：1-客户、2-坐席、3-机器人、4-系统 */
    public static final int SENDER_CUSTOMER = 1;
    public static final int SENDER_AGENT = 2;
    public static final int SENDER_BOT = 3;
    public static final int SENDER_SYSTEM = 4;

    /** 会话流转类型：6-机器人转人工（与 1-转接区分开，坐席能看出"这单是机器人转过来的"） */
    public static final int EVENT_BOT_TRANSFER = 6;

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
    private final CustomerDynamics dynamics;
    private final cn.net.susan.customer.mapper.CsatRecordMapper csatRecordMapper;
    private final ObjectProvider<CustomerTagRuleService> tagRuleProvider;
    private final ChannelMapper channelMapper;
    private final ChannelKeyMapper channelKeyMapper;
    private final VisitorTokenService visitorTokenService;
    private final SnowflakeIdGenerator idGenerator;
    /** 智能路由：延迟注入，避免和 SessionService 构造循环依赖 */
    private final ObjectProvider<RoutingService> routingProvider;
    /** 实时质检：同样是延迟注入，消息链路不该被质检拖住 */
    private final ObjectProvider<RealtimeQaService> realtimeQaProvider;
    /** 质检中心：会话结束后按真实对话建质检任务 */
    private final ObjectProvider<QaService> qaServiceProvider;

    /** AI 客服大脑：机器人接待一轮（意图 / 情绪 / 回复 / 是否转人工） */
    private final ObjectProvider<BotBrainService> botBrainProvider;

    /** 图片识别：客户发来的图先看懂，再交给大脑回答 */
    private final ObjectProvider<VisionService> visionServiceProvider;

    private final SessionAttachmentService attachmentService;

    /** 回填识别结论后要通知长连接，让双方不用刷新就能看到"AI 判读" */
    private final cn.net.susan.customer.internal.RealtimeNotifyClient notifyClient;

    /** 图片消息的正文是 JSON（fileId / url / name + 识别结论），要解析与回填 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService visionExecutor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "chat-vision");
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void shutdownVisionExecutor() {
        visionExecutor.shutdown();
    }

    /**
     * "依赖没装配上"这类问题的告警开关：一旦发生，每条消息都会命中同一条分支，
     * 按消息打日志会把日志刷爆；但完全不打又会让"功能悄悄不干活"。
     * 所以只报第一次，后续降噪。
     */
    private final AtomicBoolean realtimeQaMissingWarned = new AtomicBoolean(false);
    private final AtomicBoolean qaTaskMissingWarned = new AtomicBoolean(false);
    private final AtomicBoolean routingMissingWarned = new AtomicBoolean(false);
    private final AtomicBoolean botBrainMissingWarned = new AtomicBoolean(false);

    /** 第一次命中才打日志（返回 true 表示这次该打） */
    private boolean warnOnce(AtomicBoolean flag, String message, Object... args) {
        if (flag.compareAndSet(false, true)) {
            log.warn(message, args);
            return true;
        }
        return false;
    }

    public SessionService(
            SessionMapper sessionMapper,
            SessionMessageMapper sessionMessageMapper,
            SessionEventMapper sessionEventMapper,
            CustomerMapper customerMapper,
            CustomerDynamics dynamics,
            cn.net.susan.customer.mapper.CsatRecordMapper csatRecordMapper,
            ObjectProvider<CustomerTagRuleService> tagRuleProvider,
            ChannelMapper channelMapper,
            ChannelKeyMapper channelKeyMapper,
            VisitorTokenService visitorTokenService,
            SnowflakeIdGenerator idGenerator,
            ObjectProvider<RoutingService> routingProvider,
            ObjectProvider<RealtimeQaService> realtimeQaProvider,
            ObjectProvider<QaService> qaServiceProvider,
            ObjectProvider<BotBrainService> botBrainProvider,
            ObjectProvider<VisionService> visionServiceProvider,
            SessionAttachmentService attachmentService,
            cn.net.susan.customer.internal.RealtimeNotifyClient notifyClient
    ) {
        this.sessionMapper = sessionMapper;
        this.sessionMessageMapper = sessionMessageMapper;
        this.sessionEventMapper = sessionEventMapper;
        this.customerMapper = customerMapper;
        this.dynamics = dynamics;
        this.csatRecordMapper = csatRecordMapper;
        this.tagRuleProvider = tagRuleProvider;
        this.channelMapper = channelMapper;
        this.channelKeyMapper = channelKeyMapper;
        this.visitorTokenService = visitorTokenService;
        this.idGenerator = idGenerator;
        this.routingProvider = routingProvider;
        this.realtimeQaProvider = realtimeQaProvider;
        this.qaServiceProvider = qaServiceProvider;
        this.botBrainProvider = botBrainProvider;
        this.visionServiceProvider = visionServiceProvider;
        this.attachmentService = attachmentService;
        this.notifyClient = notifyClient;
    }

    /** 是否开启机器人首轮接待（全局开关，租户级开关由 AI 侧的 bot_setting 决定）。 */
    private boolean botReceptionEnabled() {
        BotBrainService brain = botBrainProvider.getIfAvailable();
        return brain != null && brain.receptionEnabled();
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
            dynamics.system(tenant, customer.getId(), CustomerDynamics.SESSION_START,
                    "发起会话", "渠道：" + channel.getName(), CustomerDynamics.REF_SESSION,
                    session.getSessionNo());
            if (botReceptionEnabled()) {
                // 机器人先接待：先进"2-机器人接待"，不立刻派人工。
                // 客户问完，AI 客服大脑判断该转人工时才入队（见 BotBrainService.escalate）。
                markBotReception(tenant, session);
            } else {
                appendSystemMessage(tenant, session, "访客进入会话，等待客服接入");
                log.info("访客会话创建 tenant={} sessionNo={} channel={} customerNo={}",
                        tenant, session.getSessionNo(), channel.getName(), customer.getCustomerNo());
                // 进线即路由：有在线且接得下的人就直接分过去，不用等坐席手点
                routeQuietly(tenant, session.getSessionNo());
            }
        }

        String token = visitorTokenService.createToken(
                customer.getId(), customer.getName(), tenant, session.getSessionNo());
        String identityToken = visitorTokenService.createIdentityToken(
                customer.getId(), customer.getCustomerNo(), customer.getName(), tenant);
        BotBrainService brain = botBrainProvider.getIfAvailable();
        String botName = brain == null ? null : brain.botProfile(tenant).botName();
        return new OpenResult(
                session.getSessionNo(),
                customer.getCustomerNo(),
                customer.getName(),
                token,
                visitorTokenService.expireAt(),
                session.getStatus(),
                created,
                identityToken,
                botName
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
     * 工作台顶部计数：我的接待 / 待接待。
     *
     * <p>以前这两个数字是前端从**当前这个标签页的列表**里数出来的：
     * 停在「待接待」时"我的接待"就变成 0，停在「我的会话」时"待接待"就是 0——
     * 数字随标签页乱变，还和路由那边的"接待量"对不上（路由算的是 status=3 的会话数）。
     * 现在统一由服务端按固定口径统计：</p>
     * <ul>
     *   <li>mine  = 我负责且接待中（status=3）——和路由判断"接满了没"用的是同一个口径；</li>
     *   <li>queue = 没人负责且未结束（status 1/2）——待接待。</li>
     * </ul>
     */
    public WorkloadVO workload(LoginUser user) {
        String tenant = tenantOf(user);
        Long mine = sessionMapper.selectCount(Wrappers.<Session>lambdaQuery()
                .eq(Session::getTenantCode, tenant)
                .eq(Session::getAgentId, user.userId())
                .eq(Session::getStatus, STATUS_AGENT)
                .eq(Session::getDeleted, false));
        Long queue = sessionMapper.selectCount(Wrappers.<Session>lambdaQuery()
                .eq(Session::getTenantCode, tenant)
                .isNull(Session::getAgentId)
                .in(Session::getStatus, STATUS_QUEUING, STATUS_BOT)
                .eq(Session::getDeleted, false));
        return new WorkloadVO(mine == null ? 0 : mine.intValue(), queue == null ? 0 : queue.intValue());
    }

    /** 工作台顶部计数 */
    public record WorkloadVO(int mine, int queue) {
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
                customer == null ? null : customer.getCustomerNo(),
                session.getSource(),
                session.getIntent(),
                session.getEmotion(),
                session.getBotTransferReason(),
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
        if (senderType == SENDER_CUSTOMER && msgType == MSG_TYPE_IMAGE) {
            List<Long> fileIds = imageFileIds(content);
            if (fileIds.isEmpty() || fileIds.size() > 3) {
                throw new BizException(40001, "一条图片消息需要 1 到 3 张图片");
            }
            for (Long fileId : fileIds) {
                attachmentService.verifySessionFile(tenantCode, sessionNo, fileId);
            }
        }
        SessionMessage message = buildMessage(
                tenantCode, session.getId(), senderType, senderId, msgType, content, visibleTo);
        message.setClientMsgNo(normalizedClientMsgNo);
        message.setSeq(nextSeq(tenantCode, session.getId()));
        sessionMessageMapper.insert(message);

        LocalDateTime now = LocalDateTime.now();
        touchCustomer(tenantCode, session.getCustomerId(), now);
        // 落库成功这一行是消息链路的"账本"：客户说发了、坐席说没收到时先看它——
        // 有这条 seq，说明消息进了库，问题在推送；没有，说明请求压根没到这一层
        // （那就去实时网关看"收到上行消息"有没有打出来）。
        log.info("消息落库 tenant={} sessionNo={} seq={} msgNo={} senderType={} 可见范围={} "
                        + "客户端消息号={} 长度={} 内容={}",
                tenantCode, sessionNo, message.getSeq(), message.getMsgNo(), senderType,
                visibleTo, normalizedClientMsgNo,
                message.getContent() == null ? 0 : message.getContent().length(),
                preview(message.getContent()));
        // 边聊边检：消息落库后立刻过一遍实时质检规则（放在事务提交后跑）
        scheduleRealtimeQa(tenantCode, session, message);
        // 客服大脑：客户说完，机器人回一句（同样放在事务提交后跑）
        scheduleBotReply(tenantCode, session, message);
        return toMessageVO(message);
    }

    /**
     * AI 客服大脑：客户消息落库后，让机器人接一轮。
     *
     * <p>几个刻意的设计：</p>
     * <ol>
     *   <li><b>只对客户消息触发</b>：机器人自己的回复（senderType=3）和系统提示（4）
     *       不会再触发一次，否则就是自己跟自己聊；</li>
     *   <li><b>事务提交后再跑</b>：机器人要读这段对话的完整历史，消息得先落库；
     *       模型调用还慢（秒级），放在事务里会把消息发送这条链路一起拖住；</li>
     *   <li><b>失败只降级、不报错</b>：AI 挂了就当机器人答不出来，直接转人工，
     *       客户发消息这个动作本身永远不能被 AI 的可用性影响。</li>
     * </ol>
     */
    private void scheduleBotReply(String tenantCode, Session session, SessionMessage message) {
        // 机器人自己的回复（3）和系统提示（4）本来就不该再触发一轮，跳过不用记日志
        if (!Integer.valueOf(SENDER_CUSTOMER).equals(message.getSenderType())) {
            return;
        }
        // 客户发的图片（msgType=2）：先让视觉模型看懂图，再把结论拼成上下文交给大脑。
        // 顺序不能反——大脑是按"客户说了什么"来判断意图、检索知识的，图里的内容必须先变成文字。
        if (Integer.valueOf(MSG_TYPE_IMAGE).equals(message.getMsgType())) {
            scheduleImageUnderstanding(tenantCode, session, message);
            return;
        }
        // 下面两种跳过必须留日志：现象都是"客户发了消息、机器人一声不吭"，
        // 没有日志就只能靠猜——这两行是最容易被问到的"为什么不回话"。
        if (!botReceptionEnabled()) {
            log.info("机器人跳过本轮：机器人接待总开关是关的（yunti.bot.enabled / reception-enabled）"
                    + " tenant={} sessionNo={}", tenantCode, session.getSessionNo());
            return;
        }
        if (session.getAgentId() != null) {
            // 已经是人工接待了，机器人不该插话（避免客户以为两个人在同时跟他说话）
            log.info("机器人跳过本轮：会话已由人工客服接待 agentId={} tenant={} sessionNo={}",
                    session.getAgentId(), tenantCode, session.getSessionNo());
            return;
        }
        String sessionNo = session.getSessionNo();
        log.debug("客户消息触发机器人接待 tenant={} sessionNo={} seq={}",
                tenantCode, sessionNo, message.getSeq());
        Runnable task = () -> {
            BotBrainService brain = botBrainProvider.getIfAvailable();
            if (brain == null) {
                warnOnce(botBrainMissingWarned,
                        "机器人接待不可用：BotBrainService 没有装配上，所有会话都不会有机器人回复");
                return;
            }
            try {
                // 异步：机器人这一轮要调 AI（秒级），不能拖住"客户消息发送成功"这条响应
                brain.handleCustomerMessageAsync(tenantCode, sessionNo);
            } catch (Exception e) {
                log.warn("机器人接待失败 tenant={} sessionNo={} error={}",
                        tenantCode, sessionNo, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
    }

    /**
     * 客户发来图片：异步识别 → 把结论写回这条消息 → 再触发大脑回答。
     *
     * <p>三步都在异步线程里做，理由和机器人接待一样：识别要调视觉模型（秒级），
     * 不能拖住"图片消息发送成功"这条响应。</p>
     *
     * <p>写回消息这一步很关键：坐席在工作台看到的就不是一张"光秃秃的图"，
     * 而是**图片 + AI 判读 + OCR 原文**——客户发的是报错截图时，坐席一眼就知道问题在哪。</p>
     */
    private void scheduleImageUnderstanding(String tenantCode, Session session, SessionMessage message) {
        if (!botReceptionEnabled() || session.getAgentId() != null) {
            // 人工已经在接待：机器人不插话，但图片识别对坐席同样有用，所以照样识别、照样回填
            log.info("人工接待中，图片只做识别不回话 tenant={} sessionNo={}", tenantCode, session.getSessionNo());
        }
        String sessionNo = session.getSessionNo();
        Runnable task = () -> {
            VisionService vision = visionServiceProvider.getIfAvailable();
            if (vision == null) {
                return;
            }
            // 识别 + 回答算"一个回合"：从这一刻起就给客户亮"正在输入"。
            // 三张图要下载、压缩、调视觉模型，再交给大脑回答——中间只要熄一次，
            // 客户看到的就是"图发出去了，没人理"（这正是"响应好慢"的那种体感）。
            notifyClient.notifyTyping(tenantCode, sessionNo, "BOT", true);
            // 交给大脑之后由大脑收尾（它自己会亮一次、结束时熄灭），这里就不能再插手，
            // 否则两边一熄一亮，客户看到的输入提示会闪一下
            boolean handedOffToBrain = false;
            try {
                List<Long> fileIds = imageFileIds(message.getContent());
                if (fileIds.isEmpty()) {
                    log.warn("图片消息里没有解析出文件 ID tenant={} sessionNo={} content={}",
                            tenantCode, sessionNo, preview(message.getContent()));
                    return;
                }
                // 客户随图说的那句话：没写字就是空串，视觉模型只按图判断
                String caption = imageCaption(message.getContent());
                VisionService.VisionResult result = vision.recognize(
                        tenantCode, sessionNo, fileIds, caption);
                // ① 把识别结论写回图片消息（坐席与质检都读得到），并推一份"更新后的消息"出去
                MessageVO patched = patchImageMessage(tenantCode, message.getId(), result);
                if (patched != null) {
                    notifyClient.notifyMessage(tenantCode, sessionNo, toMessageMap(patched), true);
                }
                // ② 再把"图里的内容"当成客户说的话，交给大脑回答
                if (session.getAgentId() == null) {
                    BotBrainService brain = botBrainProvider.getIfAvailable();
                    if (brain != null) {
                        handedOffToBrain = true;
                        brain.handleCustomerMessageAsync(tenantCode, sessionNo,
                                vision.toBrainQuestion(result, caption));
                    }
                }
            } catch (Exception e) {
                log.warn("图片识别失败 tenant={} sessionNo={} error={}", tenantCode, sessionNo, e.getMessage());
            } finally {
                if (!handedOffToBrain) {
                    // 人工接待中 / 大脑不可用 / 识别失败：这一轮到此为止，提示得收掉
                    notifyClient.notifyTyping(tenantCode, sessionNo, "BOT", false);
                }
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    visionExecutor.execute(task);
                }
            });
            return;
        }
        visionExecutor.execute(task);
    }

    /**
     * 从图片消息的正文里解析文件 ID。
     *
     * <p>图片消息的 content 是一段 JSON（前端发过来的）：`{"fileId":"...","url":"...","name":"..."}`。
     * 解析失败不抛异常，只记日志——一张图发失败不该影响整条会话。</p>
     */
    private List<Long> imageFileIds(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        // 用有序 Set 去重：前端发多条图的消息时，顶层 fileId 是第一张（兼容老消息），
        // fileIds 里又是完整清单——不去重的话第一张会被识别两遍
        Set<Long> ids = new LinkedHashSet<>();
        try {
            Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<>() {
            });
            Object single = parsed.get("fileId");
            if (single != null) {
                ids.add(Long.parseLong(String.valueOf(single)));
            }
            // 一条消息多张图（最多 3 张，前端限制）：清单在这里
            Object many = parsed.get("fileIds");
            if (many instanceof List<?> list) {
                for (Object item : list) {
                    ids.add(Long.parseLong(String.valueOf(item)));
                }
            }
        } catch (Exception e) {
            log.warn("解析图片消息正文失败：{}", e.getMessage());
        }
        return new ArrayList<>(ids);
    }

    /**
     * 从图片消息的正文里取"客户随图说的那句话"。
     *
     * <p>正文形如 `{"fileId":"...","url":"...","name":"...","text":"这个订单为什么一直失败"}`。
     * 客户可能就是不想写字（只发一张截图），所以取不到 `text` 时返回空串，不是错——
     * 视觉模型只按图判断，大脑那边也就只有图里的内容可用。</p>
     */
    String imageCaption(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<>() {
            });
            Object text = parsed.get("text");
            return text == null ? "" : String.valueOf(text).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 图片消息在**对话历史**里的文本（喂给客服大脑的那份）。
     *
     * <p>历史里的 `content` 是那段 JSON，直接丢给模型就是让它读 `{"fileId":"22..."}`——
     * 既浪费 token 又干扰判断。这里换成"客户说的那句话"，没写字就说明只发了图。</p>
     */
    String imageHistoryText(String content) {
        String caption = imageCaption(content);
        return caption.isEmpty() ? "[客户发来一张图片]" : caption;
    }

    /** 日志里的单行预览（换行压平 + 截断） */
    private String inline(String value) {
        if (value == null || value.isBlank()) {
            return "（没识别到文字）";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }

    /** 消息对象转成"跨服务传的普通 Map"（长连接只认 JSON，不认 record） */
    private Map<String, Object> toMessageMap(MessageVO message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("msgId", message.msgId());
        data.put("msgNo", message.msgNo());
        data.put("clientMsgNo", message.clientMsgNo());
        data.put("seq", message.seq());
        data.put("sessionId", message.sessionId());
        data.put("senderType", message.senderType());
        data.put("senderId", message.senderId());
        data.put("msgType", message.msgType());
        data.put("content", message.content());
        data.put("visibleTo", message.visibleTo());
        data.put("sendTime", message.sendTime());
        return data;
    }

    /**
     * 把识别结论并回图片消息的正文（保留原有的 fileId / url / name）。
     *
     * @return 更新后的消息；没更新成功返回 null（调用方据此决定要不要推送）
     */
    private MessageVO patchImageMessage(String tenantCode, Long messageId, VisionService.VisionResult result) {
        SessionMessage current = sessionMessageMapper.selectById(messageId);
        if (current == null) {
            return null;
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    current.getContent() == null ? "{}" : current.getContent(),
                    new TypeReference<Map<String, Object>>() {
                    });
            payload.putAll(result.toMessagePatch());
            SessionMessage update = new SessionMessage();
            update.setId(messageId);
            update.setContent(objectMapper.writeValueAsString(payload));
            update.setUpdateTime(LocalDateTime.now());
            sessionMessageMapper.updateById(update);
            log.info("图片识别结论已回填 tenant={} messageId={} available={} ocrLength={}",
                    tenantCode, messageId, result.available(), result.ocrText().length());
            current.setContent(update.getContent());
            return toMessageVO(current);
        } catch (Exception e) {
            log.warn("回填图片识别结论失败 messageId={} error={}", messageId, e.getMessage());
            return null;
        }
    }

    /**
     * 实时质检：挂在事务提交之后执行。
     *
     * <p>两个考虑：一是别让质检拖慢消息本身；二是坐席收到预警点进会话时，
     * 触发告警的那条消息必须已经落库了。质检失败只记日志，绝不能反过来影响消息链路。</p>
     */
    private void scheduleRealtimeQa(String tenantCode, Session session, SessionMessage message) {
        Runnable task = () -> {
            RealtimeQaService qa = realtimeQaProvider.getIfAvailable();
            if (qa == null) {
                warnOnce(realtimeQaMissingWarned,
                        "实时质检不可用：RealtimeQaService 没有装配上，边聊边检不会生效");
                return;
            }
            try {
                qa.check(tenantCode, session, message.getId(), message.getSeq(),
                        message.getSenderType() == null ? SENDER_CUSTOMER : message.getSenderType(),
                        message.getContent(), message.getVisibleTo());
            } catch (Exception e) {
                log.warn("会话实时质检失败 tenant={} sessionNo={} error={}",
                        tenantCode, session.getSessionNo(), e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
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
        return claimSession(tenantCode, sessionNo, agentId, RoutingService.SOURCE_MANUAL);
    }

    /**
     * 内部接口：坐席认领会话（带来源，用于区分"人工接入"和"智能路由分配"）。
     */
    @Transactional
    public Session claimSession(String tenantCode, String sessionNo, Long agentId, String source) {
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
        boolean byRouting = RoutingService.SOURCE_ROUTING.equals(source);
        recordEvent(tenantCode, session.getId(), EVENT_ASSIGN, agentId, QUEUE_TARGET, String.valueOf(agentId),
                byRouting ? "智能路由自动分配" : "坐席接入");
        appendSystemMessage(tenantCode, session, "人工客服已接入，很高兴为您服务");
        log.info("坐席认领会话 tenant={} sessionNo={} agentId={} 来源={}",
                tenantCode, sessionNo, agentId, byRouting ? "智能路由" : "人工");
        return session;
    }

    /**
     * 进入"机器人接待"：状态切到 2，并落一条系统提示。
     *
     * <p>为什么状态要单独一个值：<b>排队调度（每 10 秒扫一次）只捞"1-排队中"</b>。
     * 如果机器人接待还留在 1，会话会在 10 秒内被派给人工坐席，机器人一句话都没机会说——
     * 这是"机器人接待"最容易踩的坑：功能写完了，但被路由抢走了。</p>
     */
    @Transactional
    public Session markBotReception(String tenantCode, Session session) {
        LocalDateTime now = LocalDateTime.now();
        Session update = new Session();
        update.setId(session.getId());
        update.setStatus(STATUS_BOT);
        update.setUpdateTime(now);
        sessionMapper.updateById(update);
        session.setStatus(STATUS_BOT);
        // 开场白单独发一条机器人消息：客户一进线就看到欢迎语，之后每条回答就只是回答。
        // （以前把欢迎语拼在第一条回答前面，客户问完问题还要再被自我介绍一遍，很啰嗦）
        BotBrainService brain = botBrainProvider.getIfAvailable();
        String greeting = brain == null ? null : brain.botProfile(tenantCode).welcomeMessage();
        if (greeting != null && !greeting.isBlank()) {
            SessionMessage welcome = buildMessage(
                    tenantCode, session.getId(), SENDER_BOT, null, 1, greeting, VISIBLE_ALL);
            welcome.setSeq(nextSeq(tenantCode, session.getId()));
            sessionMessageMapper.insert(welcome);
        } else {
            // 取不到欢迎语（AI 没起 / 没配）时退回一句通用提示，别让开场是空的
            appendSystemMessage(tenantCode, session, "已接入智能客服，请直接描述您的问题");
        }
        log.info("进入机器人接待 tenant={} sessionNo={}", tenantCode, session.getSessionNo());
        return session;
    }

    /**
     * 机器人转人工：状态切回"1-排队中"，记一条流转，并立刻尝试分配坐席。
     *
     * @param reason 转人工原因（坐席能看到"这单为什么转过来"，而不是只看到一个待接待）
     * @param notice 给客户看的一句系统提示；机器人自己已经说了交接话术时传 null，避免同一句话说两遍
     * @return 落库的那条系统提示（没传 notice 时返回 null），调用方拿去推给长连接
     */
    @Transactional
    public EscalateResult escalateToHuman(String tenantCode, String sessionNo, String reason, String notice) {
        Session session = requireSession(tenantCode, sessionNo);
        if (Integer.valueOf(STATUS_CLOSED).equals(session.getStatus())) {
            return EscalateResult.EMPTY;
        }
        if (session.getAgentId() != null) {
            // 已经有人工接手了，机器人不该再抢着转一次
            return new EscalateResult(null, session.getAgentId());
        }
        String remark = trimRemark(reason);
        // 已经因为机器人的原因转过一次了：只保证它在队列里，不再重复记流转、也不再补一句一样的话
        // （否则客户连发几条消息、或者 AI 一直连不上，对话框里会刷出一串"正在为您转接人工客服"）
        boolean alreadyTransferred = session.getBotTransferReason() != null
                && Integer.valueOf(STATUS_QUEUING).equals(session.getStatus());
        if (alreadyTransferred) {
            log.info("机器人转人工已记录过，跳过重复处理 tenant={} sessionNo={} 原原因={}",
                    tenantCode, sessionNo, session.getBotTransferReason());
            routeQuietly(tenantCode, sessionNo);
            // 已经在队列里：再试一次分配，把结果告诉调用方（客户可能还在等）
            return new EscalateResult(null, currentAgentId(tenantCode, sessionNo));
        }
        Session update = new Session();
        update.setId(session.getId());
        update.setStatus(STATUS_QUEUING);
        // 原因落在会话上：坐席列表和会话头部直接显示，不用再翻流转记录
        update.setBotTransferReason(remark);
        update.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(update);
        session.setStatus(STATUS_QUEUING);
        session.setBotTransferReason(remark);
        recordEvent(tenantCode, session.getId(), EVENT_BOT_TRANSFER, null, "BOT", QUEUE_TARGET,
                remark == null ? "智能客服转人工" : remark);
        dynamics.system(tenantCode, session.getCustomerId(), CustomerDynamics.HUMAN_TRANSFER,
                "转人工：" + (remark == null ? "智能客服转人工" : remark),
                "会话 " + sessionNo + " 由智能客服转人工接待",
                CustomerDynamics.REF_SESSION, sessionNo);
        MessageVO noticeVO = null;
        if (notice != null && !notice.isBlank()) {
            SessionMessage message = buildMessage(
                    tenantCode, session.getId(), SENDER_SYSTEM, null, 5, notice, VISIBLE_ALL);
            message.setSeq(nextSeq(tenantCode, session.getId()));
            sessionMessageMapper.insert(message);
            noticeVO = toMessageVO(message);
        }
        log.info("机器人转人工 tenant={} sessionNo={} 原因={}", tenantCode, sessionNo, remark);
        // 转完立刻试一次自动分配；分不到就留在队列里，等坐席认领或 10 秒兜底调度
        routeQuietly(tenantCode, sessionNo);
        return new EscalateResult(noticeVO, currentAgentId(tenantCode, sessionNo));
    }

    /** 再查一次会话当前的负责坐席（分配成功与否，决定跟客户怎么说明） */
    private Long currentAgentId(String tenantCode, String sessionNo) {
        try {
            return requireSession(tenantCode, sessionNo).getAgentId();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 转人工的结果。
     *
     * @param notice  需要立刻推给客户的提示（没有就为 null）
     * @param agentId 实际接手的坐席；为 null 表示"还在队列里等"——
     *                调用方据此决定要不要告诉客户"当前没人，已排队"，而不是让客户无限等一句话
     */
    public record EscalateResult(MessageVO notice, Long agentId) {
        public static final EscalateResult EMPTY = new EscalateResult(null, null);

        public boolean assigned() {
            return agentId != null;
        }
    }

    /**
     * 记录机器人这一轮识别出的意图与情绪（工作台列表与会话头部直接展示）。
     */
    @Transactional
    public void updateBotState(String tenantCode, Long sessionId, String intent, String emotion) {
        if (sessionId == null) {
            return;
        }
        Session update = new Session();
        update.setId(sessionId);
        if (intent != null && !intent.isBlank()) {
            update.setIntent(intent.length() > 64 ? intent.substring(0, 64) : intent);
        }
        if (emotion != null && !emotion.isBlank()) {
            update.setEmotion(emotion.length() > 20 ? emotion.substring(0, 20) : emotion);
        }
        update.setUpdateTime(LocalDateTime.now());
        sessionMapper.updateById(update);
    }

    /**
     * 触发一次路由；失败不影响主流程（坐席照样能手动接入，定时任务也会兜底）。
     */
    private void routeQuietly(String tenantCode, String sessionNo) {
        RoutingService routing = routingProvider.getIfAvailable();
        if (routing == null) {
            warnOnce(routingMissingWarned,
                    "智能路由不可用：RoutingService 没有装配上，会话只会停在队列里等坐席手点");
            return;
        }
        try {
            routing.assignIfPossible(tenantCode, sessionNo);
        } catch (Exception e) {
            log.warn("进线路由失败 tenant={} sessionNo={} error={}", tenantCode, sessionNo, e.getMessage());
        }
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
        dynamics.record(tenantCode, session.getCustomerId(), CustomerDynamics.SESSION_END,
                "会话结束", trimRemark(remark) == null ? "会话 " + sessionNo + " 已结束"
                        : "会话 " + sessionNo + " 已结束：" + trimRemark(remark),
                CustomerDynamics.REF_SESSION, sessionNo, operatorId, null);
        recalcCustomerTags(tenantCode, session.getCustomerId());
        // 会话一结束就进质检：按真实对话建一条待复核任务（事务提交后跑）
        scheduleQaTask(tenantCode, session, operatorId);
        log.info("会话已结束 tenant={} sessionNo={} operator={}", tenantCode, sessionNo, operatorId);
        return session;
    }

    /**
     * 会话结束 / 转人工之后，跑一次规则标签重算。
     *
     * <p>"近 30 天投诉两次""近 90 天退款三次"这类标签只有在会话结束后才算得准；
     * 规则引擎出问题不能让会话结束不了，所以只记日志。</p>
     */
    private void recalcCustomerTags(String tenantCode, Long customerId) {
        if (customerId == null) {
            return;
        }
        try {
            CustomerTagRuleService rules = tagRuleProvider.getIfAvailable();
            if (rules != null) {
                rules.recalculateForCustomer(tenantCode, customerId, null);
            }
        } catch (Exception e) {
            log.warn("规则标签重算失败（不影响会话结束）tenant={} customerId={} error={}",
                    tenantCode, customerId, e.getMessage());
        }
    }

    /**
     * 满意度评价（会话结束后由客户评价，坐席回访时也能代录）。
     *
     * <p>三处都要写：</p>
     * <ol>
     *   <li><b>csat_record</b>：评价明细（一条会话一条，重复评价是"改评价"，做更新）；</li>
     *   <li><b>session.csat_score</b>：这条会话几分（工作台/轨迹里直接显示）；</li>
     *   <li><b>customer.csat</b>：这个人的口碑均值（客户 360 画像上的"满意度"）；</li>
     * </ol>
     *
     * <p>只允许评价"已结束"的会话：还没接待完就打分，等于让客户给半成品打分。</p>
     */
    @Transactional
    public CsatResult submitCsat(String tenantCode, String sessionNo, int score, String feedback,
                                 Long operatorId, String operatorName) {
        if (score < 1 || score > 5) {
            throw new BizException(40001, "满意度评分只能是 1~5");
        }
        Session session = requireSession(tenantCode, sessionNo);
        if (!Integer.valueOf(STATUS_CLOSED).equals(session.getStatus())) {
            throw new BizException(40001, "会话还没结束，暂时不能评价");
        }
        LocalDateTime now = LocalDateTime.now();
        String text = trimRemark(feedback);
        Long customerId = session.getCustomerId();
        Long agentId = session.getAgentId();

        CsatRecord existing = csatRecordMapper.selectOne(
                Wrappers.<CsatRecord>lambdaQuery()
                        .eq(CsatRecord::getTenantCode, tenantCode)
                        .eq(CsatRecord::getSessionId, session.getId())
                        .last("LIMIT 1"));
        if (existing == null) {
            csatRecordMapper.insert(CsatRecord.builder()
                    .id(idGenerator.nextId())
                    .tenantCode(tenantCode)
                    .sessionId(session.getId())
                    .customerId(customerId)
                    .agentId(agentId)
                    .score(score)
                    .feedback(text)
                    .evaluateTime(now)
                    .createTime(now)
                    .updateTime(now)
                    .creator(operatorName == null ? "VISITOR" : operatorName)
                    .deleted(false)
                    .build());
        } else {
            CsatRecord update = new CsatRecord();
            update.setId(existing.getId());
            update.setScore(score);
            update.setFeedback(text);
            update.setEvaluateTime(now);
            update.setUpdateTime(now);
            update.setEditor(operatorName);
            csatRecordMapper.updateById(update);
        }

        Session update = new Session();
        update.setId(session.getId());
        update.setCsatScore(score);
        update.setUpdateTime(now);
        sessionMapper.updateById(update);

        BigDecimal average = null;
        if (customerId != null) {
            average = csatRecordMapper.selectCustomerAverage(tenantCode, customerId);
            Customer customer = new Customer();
            customer.setId(customerId);
            customer.setCsat(average);
            customer.setUpdateTime(now);
            customerMapper.updateById(customer);
        }
        dynamics.record(tenantCode, customerId, CustomerDynamics.CSAT,
                "满意度评价 " + score + " 分",
                text == null ? "会话 " + sessionNo + " 的满意度评价" : text,
                CustomerDynamics.REF_SESSION, sessionNo, operatorId, operatorName);
        log.info("满意度评价 tenant={} sessionNo={} score={} 客户均值={}",
                tenantCode, sessionNo, score, average);
        return new CsatResult(score, text, average);
    }

    /** 满意度评价结果 */
    public record CsatResult(int score, String feedback, BigDecimal average) {
    }

    /**
     * 会话结束 → 生成质检任务。
     *
     * <p>放在事务提交之后：任务里要读这条会话的完整对话，得等消息和"会话已结束"都落库；
     * 生成失败只记日志——质检不该反过来影响结束会话这件事本身。</p>
     */
    private void scheduleQaTask(String tenantCode, Session session, Long operatorId) {
        Runnable task = () -> {
            QaService qa = qaServiceProvider.getIfAvailable();
            if (qa == null) {
                warnOnce(qaTaskMissingWarned,
                        "质检任务不可用：QaService 没有装配上，会话结束后不会生成质检任务");
                return;
            }
            try {
                qa.createFromSession(tenantCode, session, operatorId);
            } catch (Exception e) {
                log.warn("生成质检任务失败 tenant={} sessionNo={} error={}",
                        tenantCode, session.getSessionNo(), e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
            return;
        }
        task.run();
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
                    // 渠道绑了技能组就带着走：路由据此只在组内找人，找不到再按排队策略升级
                    .skillGroupId(channel.getSkillGroupId())
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
                countCustomerSession(tenant, customer.getId(), now);
                return session;
            } catch (DuplicateKeyException e) {
                log.warn("会话号冲突，重试第 {} 次", attempt + 1);
            }
        }
        throw new BizException(50001, "会话创建失败，请稍后重试");
    }

    /**
     * 客户 360 的冗余计数：累计会话数 +1、最近会话时间刷新。
     *
     * <p>为什么在 SQL 里做自增（而不是先读出来加一）：同一秒两个渠道同时进线也不会算漏，
     * 而且这个计数只用于客户列表的排序与筛选，展示口径以实时聚合为准。</p>
     */
    private void countCustomerSession(String tenant, Long customerId, LocalDateTime time) {
        if (customerId == null) {
            return;
        }
        customerMapper.update(null, Wrappers.<Customer>lambdaUpdate()
                .eq(Customer::getTenantCode, tenant)
                .eq(Customer::getId, customerId)
                .setSql("session_count = session_count + 1")
                .set(Customer::getLastSessionAt, time)
                .set(Customer::getUpdateTime, time));
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

    /** 日志里的内容预览：换行压平、超长截断，够定位问题就行，不把整段会话搬进日志 */
    private String preview(String content) {
        if (content == null || content.isBlank()) {
            return "-";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
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
        appendCustomerCreatedEvent(customer, channelName);
        return customer;
    }

    /**
     * 客户 360 的第一条动态：建档。
     *
     * <p>访客是"一进会话就自动建档"的，如果不留这一条记录，新客户的「客户动态」页签
     * 在有人给他打标 / 写备注之前一直是空的——坐席会以为"这个客户没有历史"，
     * 其实是"没人记过"。写失败只记日志：建档是主流程，不能因为一条动态把进线堵住。</p>
     */
    private void appendCustomerCreatedEvent(Customer customer, String channelName) {
        dynamics.system(customer.getTenantCode(), customer.getId(), CustomerDynamics.CREATE,
                "客户建档", channelName == null ? "来源：未知渠道" : "来源：" + channelName, null, null);
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
                stringValue(row.get("customer_no")),
                stringValue(row.get("source")),
                stringValue(row.get("intent")),
                stringValue(row.get("emotion")),
                stringValue(row.get("bot_transfer_reason")),
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
            String visitorIdentityToken,
            /**
             * 机器人显示名（租户配置的 bot_name）。
             *
             * <p>客户端的消息标签用它，而不是写死"机器人"——真实客服系统给客户看的是
             * 助手名字（店小蜜、小云…），不是"你在跟机器人说话"这种提醒。</p>
             */
            String botName
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
            String customerNo,
            String source,
            String intent,
            String emotion,
            String botTransferReason,
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
