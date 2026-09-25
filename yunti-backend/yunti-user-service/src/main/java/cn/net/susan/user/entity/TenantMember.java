package cn.net.susan.user.entity;

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
 * tenant_member 企业成员关系表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("tenant_member")
public class TenantMember {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 租户编码 */
    private String tenantCode;

    /** 用户 ID */
    private Long userId;

    /** 加入时间 */
    private LocalDateTime joinTime;

    /** 状态码：1-在职、2-已退出 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
