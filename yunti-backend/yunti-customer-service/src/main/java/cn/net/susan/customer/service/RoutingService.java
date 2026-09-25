package cn.net.susan.customer.service;

import cn.net.susan.common.exception.BizException;
import cn.net.susan.customer.entity.Session;
import cn.net.susan.customer.entity.SkillGroup;
import cn.net.susan.customer.mapper.AgentStatusMapper;
import cn.net.susan.customer.mapper.SessionMapper;
import cn.net.susan.customer.mapper.SkillGroupMapper;
import cn.net.susan.customer.internal.RealtimeNotifyClient;
import cn.net.susan.customer.mapper.SkillGroupMemberMapper;
import cn.net.susan.customer.entity.SkillGroupMember;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 智能路由：把排队中的会话分给"最合适"的坐席。
 *
 * <p>选人规则（依次执行）：</p>
 * <ol>
 *   <li>渠道绑了技能组就只在组内找，找不到人再考虑升级；</li>
 *   <li>只找状态为「在线」且长连接在线的坐席，忙碌 / 小休 / 关掉浏览器的一律跳过；</li>
 *   <li>没接满的人里优先给手上会话最少的（负载均衡），一样多时给最久没换状态的；</li>
 *   <li>排队超过技能组配置的 overflow_after_seconds 仍没分出去，就放宽技能组限制，
 *       交给其它在线坐席（排队超时升级）。</li>
 * </ol>
 */
@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    /** 一次最多扫描多少条排队会话 */
    private static final int QUEUE_SCAN_LIMIT = 100;
    /** 默认排队升级秒数（技能组没配置时兜底） */
    private static final int DEFAULT_OVERFLOW_SECONDS = 60;

    /** 认领来源：人工接入 */
    public static final String SOURCE_MANUAL = "manual";
    /** 认领来源：智能路由自动分配 */
    public static final String SOURCE_ROUTING = "routing";

    private final SessionMapper sessionMapper;
    private final SkillGroupMapper skillGroupMapper;
    private final AgentStatusMapper agentStatusMapper;
    private final SkillGroupMemberMapper memberMapper;
    private final SessionService sessionService;
    private final RealtimeNotifyClient notifyClient;

    public RoutingService(
            SessionMapper sessionMapper,
            SkillGroupMapper skillGroupMapper,
            AgentStatusMapper agentStatusMapper,
            SkillGroupMemberMapper memberMapper,
            SessionService sessionService,
            RealtimeNotifyClient notifyClient
    ) {
        this.sessionMapper = sessionMapper;
        this.skillGroupMapper = skillGroupMapper;
        this.agentStatusMapper = agentStatusMapper;
        this.memberMapper = memberMapper;
        this.sessionService = sessionService;
        this.notifyClient = notifyClient;
    }

    /**
     * 定时兜底：每 10 秒扫一遍排队会话。
     *
     * <p>坐席上线、状态切回在线、有人结束会话都会立刻触发一次调度，这里只是兜底，
     * 保证"有人能接但没触发调度"的情况也能被捞起来。</p>
     */
    @Scheduled(fixedDelay = 10_000L, initialDelay = 10_000L)
    public void dispatchQueue() {
        List<String> tenants = sessionMapper.selectTenantsWithQueue();
        for (String tenant : tenants) {
            try {
                int assigned = assignQueue(tenant);
                if (assigned > 0) {
                    log.info("排队调度完成 tenant={} 本次分配={} 条", tenant, assigned);
                }
            } catch (Exception e) {
                log.warn("排队调度失败 tenant={} error={}", tenant, e.getMessage());
            }
        }
    }

    /**
     * 扫描该租户的排队会话：先来先服务，逐个尝试分配。
     *
     * @return 本次分配成功的会话数
     */
    @Transactional
    public int assignQueue(String tenantCode) {
        List<Session> queue = sessionMapper.selectQueuedSessions(tenantCode, QUEUE_SCAN_LIMIT);
        int assigned = 0;
        for (Session session : queue) {
            if (assign(tenantCode, session)) {
                assigned++;
            }
        }
        return assigned;
    }

    /**
     * 立刻尝试给某条会话分配坐席（访客刚进线时调用）。
     */
    public boolean assignIfPossible(String tenantCode, String sessionNo) {
        Session session;
        try {
            session = sessionService.requireSession(tenantCode, sessionNo);
        } catch (BizException e) {
            return false;
        }
        return assign(tenantCode, session);
    }

    private boolean assign(String tenantCode, Session session) {
        if (session.getAgentId() != null
                || Integer.valueOf(SessionService.STATUS_CLOSED).equals(session.getStatus())) {
            return false;
        }
        Candidate candidate = pick(tenantCode, session);
        if (candidate == null) {
            return false;
        }
        try {
            sessionService.claimSession(tenantCode, session.getSessionNo(), candidate.agentId(), SOURCE_ROUTING);
            log.info("智能路由分配 tenant={} sessionNo={} agentId={} 原负载={} 升级={}",
                    tenantCode, session.getSessionNo(), candidate.agentId(),
                    candidate.activeCount(), candidate.overflow());
            notifyAssignedAfterCommit(tenantCode, candidate.agentId(), session.getSessionNo());
            return true;
        } catch (BizException e) {
            // 抢单失败（刚好被人工接入等）属于正常情况，交给下一轮
            log.info("智能路由分配未成功 tenant={} sessionNo={} 原因={}",
                    tenantCode, session.getSessionNo(), e.getMessage());
            return false;
        }
    }

    /**
     * 通知坐席"你被派单了"；放在事务提交之后发，避免坐席收到通知去拉列表时还没提交。
     */
    private void notifyAssignedAfterCommit(String tenantCode, long agentId, String sessionNo) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    notifyClient.notifyAssigned(tenantCode, agentId, sessionNo, SOURCE_ROUTING);
                }
            });
            return;
        }
        notifyClient.notifyAssigned(tenantCode, agentId, sessionNo, SOURCE_ROUTING);
    }

    /**
     * 选人：先按技能组找，找不到再看是否该升级。
     */
    private Candidate pick(String tenantCode, Session session) {
        Long skillGroupId = effectiveGroupId(tenantCode, session.getSkillGroupId());
        Candidate inGroup = firstAvailable(agentStatusMapper.selectCandidates(tenantCode, skillGroupId), false);
        if (inGroup != null) {
            return inGroup;
        }
        if (skillGroupId == null || !overflowReached(tenantCode, skillGroupId, session)) {
            return null;
        }
        // 排队超时：放宽技能组限制，交给其它在线坐席
        return firstAvailable(agentStatusMapper.selectCandidates(tenantCode, null), true);
    }

    /**
     * 会话实际生效的技能组：<b>默认技能组里一个人都没有时视为"不限技能组"</b>。
     *
     * <p>渠道创建时都会自动绑一个默认技能组，但企业往往没往里放人；
     * 如果按"组内没人就不分配"，所有会话都要干等 60 秒升级，等于没有路由。</p>
     */
    private Long effectiveGroupId(String tenantCode, Long skillGroupId) {
        if (skillGroupId == null) {
            return null;
        }
        SkillGroup group = skillGroupMapper.selectById(skillGroupId);
        if (group == null || !tenantCode.equals(group.getTenantCode())) {
            return null;
        }
        if (!Boolean.TRUE.equals(group.getIsDefault())) {
            return skillGroupId;
        }
        Long members = memberMapper.selectCount(Wrappers.<SkillGroupMember>lambdaQuery()
                .eq(SkillGroupMember::getTenantCode, tenantCode)
                .eq(SkillGroupMember::getSkillGroupId, skillGroupId)
                .eq(SkillGroupMember::getStatus, 1)
                .eq(SkillGroupMember::getDeleted, false));
        return members != null && members > 0 ? skillGroupId : null;
    }

    /** 从候选里挑第一个"还没接满"的（SQL 已按负载升序、状态停留时间升序排好） */
    private Candidate firstAvailable(List<Map<String, Object>> rows, boolean overflow) {
        for (Map<String, Object> row : rows) {
            long agentId = longValue(row.get("agent_id"));
            int load = intValue(row.get("active_count"));
            int max = intValue(row.get("max_concurrency"));
            if (max <= 0) {
                max = 1;
            }
            if (load < max) {
                return new Candidate(agentId, load, overflow);
            }
        }
        return null;
    }

    /** 排队是否已经超过技能组配置的升级时长 */
    private boolean overflowReached(String tenantCode, Long skillGroupId, Session session) {
        SkillGroup group = skillGroupMapper.selectById(skillGroupId);
        if (group == null || !tenantCode.equals(group.getTenantCode())) {
            return true;
        }
        int seconds = group.getOverflowAfterSeconds() == null
                ? DEFAULT_OVERFLOW_SECONDS : group.getOverflowAfterSeconds();
        if (seconds <= 0) {
            return false;
        }
        if (session.getStartTime() == null) {
            return false;
        }
        return Duration.between(session.getStartTime(), LocalDateTime.now()).getSeconds() >= seconds;
    }

    private long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    /** 候选人 */
    private record Candidate(long agentId, int activeCount, boolean overflow) {
    }
}
