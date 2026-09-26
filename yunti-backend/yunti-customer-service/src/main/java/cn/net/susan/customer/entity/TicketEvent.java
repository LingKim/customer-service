package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ticket_event 工单流转记录。
 *
 * <p>工单的"闭环"就靠这张表：谁在什么时候创建、分派、回复、改状态、超时预警，
 * 一条条按时间排开，客户投诉"我的工单没人管"时，翻这张表就能给答案。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ticket_event")
public class TicketEvent {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long ticketId;

    /** 1-创建、2-分配、3-回复、4-升级、5-关闭、6-重开、7-状态变更、8-SLA预警 */
    private Integer eventType;

    private Long operatorId;

    private String operatorName;

    private Integer fromStatus;

    private Integer toStatus;

    /** 这条回复是否对客户可见（false=内部备注） */
    private Boolean visibleToCustomer;

    private String content;

    private LocalDateTime eventTime;

    private LocalDateTime createTime;
}
