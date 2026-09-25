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
 * sys_role 角色表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_role")
public class SysRole {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 租户编码（平台角色为 PLATFORM） */
    private String tenantCode;

    /** 角色编码 */
    private String roleCode;

    /** 角色名称 */
    private String roleName;

    /** 类型码：1-平台角色、2-企业角色 */
    private Integer roleType;

    /** 是否内置固定角色 */
    private Boolean isFixed;

    /** 是否启用 */
    private Boolean isEnabled;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
