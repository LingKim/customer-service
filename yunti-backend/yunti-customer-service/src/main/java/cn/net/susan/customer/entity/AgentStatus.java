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
 * agent_status 坐席状态表实体：智能路由靠它判断"谁现在能接单、还能接几单"。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("agent_status")
public class AgentStatus {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long agentId;

    /** 状态码：1-在线、2-忙碌、3-小休 */
    private Integer status;

    /** 最多同时接待几个会话 */
    private Integer maxConcurrency;

    /** 长连接是否在线（实时网关维护）：关掉浏览器就不该再被分派新会话 */
    private Boolean isConnected;

    /** 状态变更时间：同负载时优先把新会话给"更久没换状态"的人 */
    private LocalDateTime statusTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
