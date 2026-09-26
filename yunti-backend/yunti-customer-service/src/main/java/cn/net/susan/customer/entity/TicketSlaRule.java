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
 * ticket_sla_rule 工单 SLA 规则：按优先级配置"首次响应多久、解决多久"。
 *
 * <p>为什么按优先级而不是按分类：紧急工单和低优先工单都可能是"退款"，
 * 但等待时间的要求完全不同——SLA 的本质是"多久内必须有人动"。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ticket_sla_rule")
public class TicketSlaRule {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 1-低、2-中、3-高、4-紧急 */
    private Integer priority;

    private Integer firstResponseMinutes;

    private Integer resolveMinutes;

    private Boolean isEnabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
