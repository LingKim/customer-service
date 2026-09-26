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
 * notification 消息表（消息中心组件的后端，第 4 篇设计里就留好了这张表）。
 *
 * <p>消息按"租户 + 类型 + 目标"存：`target_type=2` 是发给整个企业的（工单 SLA 预警这类），
 * `target_type=1` 是发给具体某个人的（分派给我的工单）；已读状态另存
 * {@link NotificationRead}，同一条消息每人各有一行。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("notification")
public class Notification {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 1-系统、2-工单、3-审核、4-质检、5-公告、6-账单 */
    private Integer notifyType;

    private String title;

    private String content;

    /** 点击后跳转的页面标识（前端自己映射到路由） */
    private String linkView;

    /** 1-用户、2-企业 */
    private Integer targetType;

    private Long targetId;

    private LocalDateTime publishTime;

    private LocalDateTime createTime;
}
