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
 * session_message 会话消息表实体：AI 质检的真实对话来源。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("session_message")
public class SessionMessage {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long sessionId;

    /** 消息编号（客户端幂等键） */
    private String msgNo;

    /** 类型码：1-文本、2-图片、3-卡片、4-事件、5-系统 */
    private Integer msgType;

    /** 发送方码：1-客户、2-坐席、3-机器人、4-系统 */
    private Integer senderType;

    private Long senderId;

    /** 消息内容（文本或 JSON 结构） */
    private String content;

    /** 消息状态码：1-已发送、2-已送达、3-已读、4-失败 */
    private Integer status;

    /** 可见范围码：1-客户与坐席都可见、2-仅坐席可见（内部备注） */
    private Integer visibleTo;

    private LocalDateTime sendTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
