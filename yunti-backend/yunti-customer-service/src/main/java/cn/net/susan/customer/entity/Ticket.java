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
 * ticket 工单。
 *
 * <p>SLA 是"两段"的：首次响应（客户等多久有人理）和解决（多久真正处理完）。
 * 只卡一个总时限的话，"有人回复了但一直没解决"和"压根没人理"会被算成同一件事。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ticket")
public class Ticket {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private String ticketNo;

    /** 1-企业内部工单、2-平台支持工单 */
    private Integer ticketType;

    /** 1-订单、2-退款售后、3-物流、4-商品、5-其他 */
    private Integer category;

    private String title;

    private Long customerId;

    private String customerName;

    /** 问题描述（会话转单时带最近聊天记录摘要） */
    @TableField("\"desc\"")
    private String desc;

    /** 1-低、2-中、3-高、4-紧急 */
    private Integer priority;

    /** 1-待处理、2-处理中、3-待客户确认、4-已解决、5-已关闭 */
    private Integer status;

    /** 1-会话转单、2-客户自助、3-坐席新建 */
    private Integer source;

    /** 来源会话号 */
    private String sessionNo;

    /** 来源会话ID */
    private Long sourceSessionId;

    private Long assigneeId;

    private String assigneeName;

    private Long groupId;

    /** 建单时快照的 SLA 时长（规则后来改了，不影响存量工单） */
    private Integer slaFirstMinutes;

    private Integer slaResolveMinutes;

    private LocalDateTime firstResponseDue;

    private LocalDateTime firstResponseAt;

    private LocalDateTime resolveDue;

    private LocalDateTime resolvedAt;

    /** 旧的单段口径，等于 resolveDue（保留兼容） */
    private LocalDateTime slaDeadline;

    /** 1-正常、2-即将超时、3-已超时 */
    private Integer slaState;

    /** 这一轮预警是否已经推送过（避免每 30 秒重复轰炸） */
    private Boolean slaAlerted;

    private LocalDateTime closeTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String creatorName;

    /** 来源渠道：1-在线会话、2-电话热线、3-邮件、4-工单导入、5-其它 */
    private Integer sourceChannel;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
