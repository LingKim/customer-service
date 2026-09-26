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

    /**
     * 客户 360：一批客户的会话统计（累计会话数 / 最近会话时间 / 转人工次数）。
     *
     * <p>为什么不在客户列表里循环查：一页 50 个客户就是 50 次查询，
     * 客户 360 是要天天打开的页面，这种写法迟早被数据量打回来。</p>
     */
    List<Map<String, Object>> selectCustomerSessionStats(
            @Param("tenantCode") String tenantCode,
            @Param("customerIds") List<Long> customerIds
    );

    /**
     * 客户 360：一批客户各自<b>最近一次</b>会话（列表里直接看到"最近意图 / 情绪"）。
     *
     * <p>用 Postgres 的 DISTINCT ON 取每个客户最新那条，比"先查全部再在内存里分组"省事且不会漏。</p>
     */
    List<Map<String, Object>> selectCustomerLatestSessions(
            @Param("tenantCode") String tenantCode,
            @Param("customerIds") List<Long> customerIds
    );

    /**
     * 客户 360 · 会话轨迹：某个客户的全部会话（倒序），带坐席姓名、消息数、是否转工单。
     */
    List<Map<String, Object>> selectSessionsOfCustomer(
            @Param("tenantCode") String tenantCode,
            @Param("customerId") Long customerId,
            @Param("limit") int limit
    );

    /**
     * 客户 360 · 概览：租户级别的客户经营数字（客户数 / 本月新增 / 有标签的 / 风险客户 …）。
     */
    Map<String, Object> selectCustomerOverview(
            @Param("tenantCode") String tenantCode,
            @Param("monthStart") java.time.LocalDateTime monthStart,
            @Param("activeSince") java.time.LocalDateTime activeSince
    );

    /**
     * 客户 360 · 规则标签：算一个客户在"最近 windowDays 天"里的会话类指标。
     *
     * <p>为什么要按窗口算：规则标签大多是"近期行为"——"近 30 天投诉两次"和"三年前投诉过两次"
     * 是两种客户，不带上窗口的话标签永远摘不掉。</p>
     *
     * @param windowDays 统计窗口（天）；0 或负数表示全周期
     */
    Map<String, Object> selectCustomerRuleMetrics(
            @Param("tenantCode") String tenantCode,
            @Param("customerId") Long customerId,
            @Param("windowDays") int windowDays
    );
}
