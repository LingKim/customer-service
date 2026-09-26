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
 * notification_read 消息已读表：一条消息一个人一行（唯一索引 uk_tenant_notify_user 挡重复）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("notification_read")
public class NotificationRead {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long notifyId;

    private Long userId;

    private LocalDateTime readTime;

    private LocalDateTime createTime;
}
