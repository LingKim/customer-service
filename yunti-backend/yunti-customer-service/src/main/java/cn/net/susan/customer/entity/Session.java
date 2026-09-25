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
 * session 会话表实体：一次"客户来找我们聊天"的完整过程。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("session")
public class Session {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 会话编号（对外，UUID 风格短号） */
    private String sessionNo;

    private Long channelId;

    private Long customerId;

    /** 状态码：1-排队中、2-机器人接待、3-人工接待、4-已结束 */
    private Integer status;

    private Long skillGroupId;

    /** 当前坐席用户 ID */
    private Long agentId;

    private String intent;

    private String emotion;

    /** 来源：网站、微信、小程序等 */
    private String source;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer csatScore;

    /** 会话已分配的最大消息序号（新消息 = 该值 + 1） */
    private Long lastMsgSeq;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
