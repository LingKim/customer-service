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
 * session_event 会话事件表：转接、升级、分配、关闭、超时都能追溯。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("session_event")
public class SessionEvent {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long sessionId;

    /** 事件类型码：1-转接、2-升级、3-分配、4-关闭、5-超时 */
    private Integer eventType;

    /** 操作人（坐席用户 ID） */
    private Long operatorId;

    /** 来源值（原坐席 / 原技能组） */
    private String fromValue;

    /** 目标值（新坐席 / 技能组 / QUEUE 表示退回队列） */
    private String toValue;

    /** 备注（转接原因、结束小结等） */
    private String remark;

    private LocalDateTime eventTime;

    private LocalDateTime createTime;
}
