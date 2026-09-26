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

    // ------------------------------------------------------------------ 数据大屏 / 坐席绩效

    /** 大屏"此刻"：在线坐席、忙碌坐席、排队 / 进行中 / 机器人接待中的会话数 */
    Map<String, Object> selectRealtimeOverview(
            @Param("tenantCode") String tenantCode,
            @Param("dayStart") java.time.LocalDateTime dayStart,
            @Param("now") java.time.LocalDateTime now
    );

    /** 大屏"今日"：会话 / 消息 / 评价 / 首响 / 机器人占比 */
    Map<String, Object> selectTodayMetrics(
            @Param("tenantCode") String tenantCode,
            @Param("dayStart") java.time.LocalDateTime dayStart,
            @Param("now") java.time.LocalDateTime now
    );

    /** 今日 24 小时会话趋势（只返回有数据的小时） */
    List<Map<String, Object>> selectHourlyTrend(
            @Param("tenantCode") String tenantCode,
            @Param("dayStart") java.time.LocalDateTime dayStart,
            @Param("now") java.time.LocalDateTime now
    );

    /** 今日渠道分布 */
    List<Map<String, Object>> selectChannelDistribution(
            @Param("tenantCode") String tenantCode,
            @Param("dayStart") java.time.LocalDateTime dayStart,
            @Param("now") java.time.LocalDateTime now
    );

    /** 今日意图 Top（机器人 / 大脑识别的结果） */
    List<Map<String, Object>> selectIntentDistribution(
            @Param("tenantCode") String tenantCode,
            @Param("dayStart") java.time.LocalDateTime dayStart,
            @Param("now") java.time.LocalDateTime now
    );

    /** 今日情绪分布 */
    List<Map<String, Object>> selectEmotionDistribution(
            @Param("tenantCode") String tenantCode,
            @Param("dayStart") java.time.LocalDateTime dayStart,
            @Param("now") java.time.LocalDateTime now
    );

    /** 今日坐席排行（大屏右侧） */
    List<Map<String, Object>> selectTodayAgentRanking(
            @Param("tenantCode") String tenantCode,
            @Param("dayStart") java.time.LocalDateTime dayStart,
            @Param("now") java.time.LocalDateTime now,
            @Param("limit") int limit
    );

    /** 大屏「坐席实时状态」：在线 / 忙碌状态 + 今日服务量 */
    List<Map<String, Object>> selectAgentLive(
            @Param("tenantCode") String tenantCode,
            @Param("dayStart") java.time.LocalDateTime dayStart,
            @Param("now") java.time.LocalDateTime now,
            @Param("limit") int limit
    );

    /** 大屏「实时动态」：今天的会话 / 转人工 / 评价 / 工单 / 质检预警事件流 */
    List<Map<String, Object>> selectRecentActivity(
            @Param("tenantCode") String tenantCode,
            @Param("dayStart") java.time.LocalDateTime dayStart,
            @Param("now") java.time.LocalDateTime now,
            @Param("limit") int limit
    );

    /** 坐席绩效下钻：某个坐席在时间范围内的会话明细 */
    List<Map<String, Object>> selectAgentSessionsForReport(
            @Param("tenantCode") String tenantCode,
            @Param("agentId") Long agentId,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to,
            @Param("limit") int limit
    );

    /** 有会话数据的租户（绩效定时重算按租户逐个跑） */
    List<String> selectTenantsForMetrics();
}
