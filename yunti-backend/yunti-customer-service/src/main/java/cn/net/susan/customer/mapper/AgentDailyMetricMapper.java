package cn.net.susan.customer.mapper;

import cn.net.susan.customer.entity.AgentDailyMetric;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * agent_daily_metric 坐席绩效 Mapper。
 */
@Mapper
public interface AgentDailyMetricMapper extends BaseMapper<AgentDailyMetric> {

    /**
     * 重算某一天的坐席绩效（幂等 upsert）。
     *
     * <p>一条 SQL 从 session / session_message / session_event / csat_record 把当天的
     * 接待量、消息量、首响、平均响应、会话时长、转接、满意度都算出来，按 (租户, 坐席) upsert。
     * 重算再多次结果都一样——这也是"报表可重算"的前提。</p>
     *
     * @param from 当天起点（含）
     * @param to   次日起点（不含）
     */
    int rebuildDaily(@Param("tenantCode") String tenantCode,
                     @Param("statDate") LocalDate statDate,
                     @Param("from") java.time.LocalDateTime from,
                     @Param("to") java.time.LocalDateTime to);

    /** 报表：时间范围内按坐席汇总（排序与分页在 SQL 里做，避免内存里排序）。 */
    List<Map<String, Object>> selectAgentReport(@Param("tenantCode") String tenantCode,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to,
                                                @Param("agentId") Long agentId,
                                                @Param("orderBy") String orderBy,
                                                @Param("limit") int limit,
                                                @Param("offset") int offset);

    /** 报表总数（配合分页）。 */
    long countAgentReport(@Param("tenantCode") String tenantCode,
                          @Param("from") LocalDate from,
                          @Param("to") LocalDate to,
                          @Param("agentId") Long agentId);

    /** 某个坐席的按天趋势（下钻用）。 */
    List<Map<String, Object>> selectAgentTrend(@Param("tenantCode") String tenantCode,
                                               @Param("agentId") Long agentId,
                                               @Param("from") LocalDate from,
                                               @Param("to") LocalDate to);

    /** 租户级按天趋势（大屏用，不区分坐席）。 */
    List<Map<String, Object>> selectTenantTrend(@Param("tenantCode") String tenantCode,
                                                @Param("from") LocalDate from,
                                                @Param("to") LocalDate to);
}
