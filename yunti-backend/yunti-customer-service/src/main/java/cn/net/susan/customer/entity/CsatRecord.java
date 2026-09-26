package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * csat_record 满意度评价（第 4 篇设计的表，客户 360 这一篇正式开写）。
 *
 * <p>一条会话一个评价（`uk_csat_session` 唯一）。评分同时回写到 `session.csat_score`
 * （单次会话的口径）和 `customer.csat`（这个人的口碑均值）——两个粒度都要有：
 * 会话详情看"这次几分"，客户 360 看"这个人历史上平均几分"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("csat_record")
public class CsatRecord {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long sessionId;

    private Long customerId;

    private Long agentId;

    /** 评分 1-5 */
    private Integer score;

    private String feedback;

    private LocalDateTime evaluateTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
