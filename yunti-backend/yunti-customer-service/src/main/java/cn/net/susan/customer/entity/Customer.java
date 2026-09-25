package cn.net.susan.customer.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * customer 客户表实体（访客进门后先建档，再开会话）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("customer")
public class Customer {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String customerNo;

    private String name;

    private String phone;

    /** 会员等级码：1-普通、2-银卡、3-金卡、4-铂金、5-企业 */
    private Integer level;

    /** 来源渠道 */
    private String channel;

    private Integer ordersCount;

    private BigDecimal totalValue;

    private Integer points;

    private BigDecimal csat;

    private Integer sentiment;

    private LocalDateTime lastActive;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
