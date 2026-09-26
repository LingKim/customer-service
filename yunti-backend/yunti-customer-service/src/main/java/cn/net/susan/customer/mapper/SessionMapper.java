package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.Session;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * session 会话 Mapper。
 */
@Mapper
public interface SessionMapper extends BaseMapper<Session> {

    /**
     * 坐席工作台的会话列表：带客户名与最后一条消息（SQL 见 SessionMapper.xml）。
     */
    List<Map<String, Object>> selectAgentSessions(
            @Param("tenantCode") String tenantCode,
            @Param("keyword") String keyword,
            @Param("agentId") Long agentId,
            @Param("unassigned") boolean unassigned,
            @Param("limit") int limit
    );

    /**
     * 访客复用会话：同一渠道下未结束的最近一条会话。
     */
    Session selectOpenSession(
            @Param("tenantCode") String tenantCode,
            @Param("channelId") Long channelId,
            @Param("customerId") Long customerId
    );

    int claimIfUnassigned(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId,
            @Param("agentId") Long agentId
    );

    int changeAssignment(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId,
            @Param("expectedAgentId") Long expectedAgentId,
            @Param("newAgentId") Long newAgentId,
            @Param("newStatus") int newStatus
    );

    Long nextMessageSeq(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId
    );

    Long lockSessionForMessage(
            @Param("tenantCode") String tenantCode,
            @Param("sessionId") Long sessionId
    );

    /**
     * 排队中的会话（先来先服务），智能路由按这个顺序分配。
     */
    List<Session> selectQueuedSessions(
            @Param("tenantCode") String tenantCode,
            @Param("limit") int limit
    );

    /**
     * 还有排队会话的租户列表（定时任务用）。
     */
    List<String> selectTenantsWithQueue();

    /**
     * 已结束、但还没有质检任务的会话（质检中心"发起全量质检"扫的就是这批）。
     */
    List<Session> selectClosedSessionsWithoutTask(
            @Param("tenantCode") String tenantCode,
            @Param("limit") int limit
    );

    /**
     * "客户在等、坐席还没回"的会话：最后一条有效消息是客户发的，且已经等了足够久。
     *
     * <p>响应超时提醒的扫描对象。返回 Map 是因为要同时带出那条消息的 ID / 序号 / 内容 ——
     * 提醒要指到具体哪句话没被回，而且靠 (消息, 规则) 唯一索引避免重复提醒。</p>
     *
     * @param oldest 只看比这个时间更早的消息（now - 最小超时阈值），把明显还没到点的先筛掉
     */
    List<Map<String, Object>> selectUnansweredSessions(
            @Param("oldest") java.time.LocalDateTime oldest,
            @Param("limit") int limit
    );
}
