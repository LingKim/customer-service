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
 * customer_event 客户动态（客户 360 里的"人对这个客户做过什么"）。
 *
 * <p>为什么单独一张表：客户的会话、工单、订单都能从各自的表里查出来，
 * 只有"谁在什么时候给他打了标 / 调了等级 / 写了备注"没地方落，
 * 而这些恰恰是坐席交接时最需要看的东西（这个客户为什么是风险标签）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("customer_event")
public class CustomerEvent {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long customerId;

    /** 事件类型码：1-建档、2-打标、3-去标、4-等级调整、5-备注更新、6-风险标记 */
    private Integer eventType;

    private String eventTitle;

    private String eventContent;

    /** 关联对象类型码：1-会话、2-工单、3-订单、4-标签 */
    private Integer refType;

    private String refNo;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime eventTime;

    private LocalDateTime createTime;
}
