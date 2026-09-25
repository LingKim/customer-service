package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.AgentStatus;
import cn.net.susan.customer.mapper.AgentStatusMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 坐席状态：在线 / 忙碌 / 小休 + 最多同时接待几单。
 *
 * <p>智能路由只把新会话分给"在线且没接满"的坐席；没有这一层，
 * 就只能靠谁手快谁抢到，等于没有调度。</p>
 */
@Service
public class AgentStatusService {

    /** 状态码：1-在线、2-忙碌、3-小休 */
    public static final int STATUS_ONLINE = 1;
    public static final int STATUS_BUSY = 2;
    public static final int STATUS_BREAK = 3;

    /** 默认最多同时接待 5 单 */
    private static final int DEFAULT_MAX_CONCURRENCY = 5;
    private static final int MAX_CONCURRENCY_LIMIT = 50;

    private final AgentStatusMapper agentStatusMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final RoutingService routingService;

    public AgentStatusService(
            AgentStatusMapper agentStatusMapper,
            SnowflakeIdGenerator idGenerator,
            RoutingService routingService
    ) {
        this.agentStatusMapper = agentStatusMapper;
        this.idGenerator = idGenerator;
        this.routingService = routingService;
    }

    /**
     * 当前坐席的状态（没有就按"在线"补一条，避免新同事第一次登录没有状态）。
     */
    @Transactional
    public AgentStatus of(String tenantCode, long agentId) {
        AgentStatus existing = find(tenantCode, agentId);
        if (existing != null) {
            return existing;
        }
        LocalDateTime now = LocalDateTime.now();
        AgentStatus created = AgentStatus.builder()
                .id(idGenerator.nextId())
                .tenantCode(tenantCode)
                .agentId(agentId)
                .status(STATUS_ONLINE)
                .maxConcurrency(DEFAULT_MAX_CONCURRENCY)
                .statusTime(now)
                .createTime(now)
                .updateTime(now)
                .creator(String.valueOf(agentId))
                .deleted(false)
                .build();
        agentStatusMapper.insert(created);
        return created;
    }

    /**
     * 修改状态（坐席自己在工作台切"在线 / 忙碌 / 小休"）。
     */
    @Transactional
    public AgentStatus update(String tenantCode, long agentId, Integer status, Integer maxConcurrency) {
        AgentStatus current = of(tenantCode, agentId);
        if (status != null && (status < STATUS_ONLINE || status > STATUS_BREAK)) {
            throw new BizException(40001, "坐席状态取值不合法");
        }
        if (maxConcurrency != null && (maxConcurrency < 1 || maxConcurrency > MAX_CONCURRENCY_LIMIT)) {
            throw new BizException(40001, "最多同时接待数应在 1~" + MAX_CONCURRENCY_LIMIT + " 之间");
        }
        AgentStatus update = new AgentStatus();
        update.setId(current.getId());
        update.setUpdateTime(LocalDateTime.now());
        if (status != null && !status.equals(current.getStatus())) {
            update.setStatus(status);
            update.setStatusTime(LocalDateTime.now());
        }
        if (maxConcurrency != null) {
            update.setMaxConcurrency(maxConcurrency);
        }
        agentStatusMapper.updateById(update);
        AgentStatus latest = of(tenantCode, agentId);
        // 切回"在线"意味着他能接单了：立刻把积压的排队会话分一次
        if (status != null && status == STATUS_ONLINE) {
            routingService.assignQueue(tenantCode);
        }
        return latest;
    }

    /** 修改当前登录坐席的状态 */
    public AgentStatus update(LoginUser user, Integer status, Integer maxConcurrency) {
        return update(tenantOf(user), user.userId(), status, maxConcurrency);
    }

    /**
     * 长连接上下线标记（实时网关维护）：下线的人不该再被分派新会话。
     */
    @Transactional
    public void markConnected(String tenantCode, long agentId, boolean connected) {
        AgentStatus current = of(tenantCode, agentId);
        if (Boolean.valueOf(connected).equals(current.getIsConnected())) {
            return;
        }
        AgentStatus update = new AgentStatus();
        update.setId(current.getId());
        update.setIsConnected(connected);
        update.setUpdateTime(LocalDateTime.now());
        update.setStatusTime(LocalDateTime.now());
        agentStatusMapper.updateById(update);
        if (connected) {
            routingService.assignQueue(tenantCode);
        }
    }

    /** 租户内所有坐席的状态（工作台展示"同事在忙什么"） */
    public List<AgentStatus> list(String tenantCode) {
        return agentStatusMapper.selectList(Wrappers.<AgentStatus>lambdaQuery()
                .eq(AgentStatus::getTenantCode, tenantCode)
                .eq(AgentStatus::getDeleted, false)
                .orderByAsc(AgentStatus::getAgentId));
    }

    /** 当前登录坐席的状态 */
    public AgentStatus of(LoginUser user) {
        return of(tenantOf(user), user.userId());
    }

    private AgentStatus find(String tenantCode, long agentId) {
        return agentStatusMapper.selectOne(Wrappers.<AgentStatus>lambdaQuery()
                .eq(AgentStatus::getTenantCode, tenantCode)
                .eq(AgentStatus::getAgentId, agentId)
                .eq(AgentStatus::getDeleted, false)
                .last("LIMIT 1"));
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
}
