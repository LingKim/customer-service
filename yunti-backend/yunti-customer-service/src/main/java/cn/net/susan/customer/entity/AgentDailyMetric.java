package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * agent_daily_metric 坐席绩效日聚合（坐席 × 天）。
 *
 * <p>为什么要有这张表：绩效报表要按坐席筛选、排序、分页、下钻，还要给大屏 5 秒刷一次用；
 * 每次都去 session / session_message 现算，数据量一大就拖死列表。
 * 预聚合之后报表只读几十行，重算又是幂等的（同一天重算多少次结果都一样）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_daily_metric")
public class AgentDailyMetric {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 坐席用户 ID；为空表示"未分配 / 机器人接待"这一行 */
    private Long agentId;

    private String agentName;

    private LocalDate statDate;

    /** 接待会话数 */
    private Integer sessionCount;

    /** 人工接待过的会话数 */
    private Integer humanSessionCount;

    /** 坐席发出的消息数 */
    private Integer messageCount;

    /** 客户发出的消息数 */
    private Integer customerMessageCount;

    /** 首次响应时长均值（秒） */
    private Integer firstResponseSeconds;

    /** 平均响应时长（秒） */
    private Integer avgResponseSeconds;

    /** 平均会话时长（秒） */
    private Integer avgSessionSeconds;

    /** 转接次数 */
    private Integer transferCount;

    /** 评价条数 */
    private Integer csatCount;

    /** 满意度均值（1~5） */
    private BigDecimal csatScore;

    /** 好评数（4~5 分） */
    private Integer goodCsatCount;

    /** 结束的会话数 */
    private Integer closeCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
