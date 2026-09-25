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
 * member_invite 成员邀请表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("member_invite")
public class MemberInvite {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    /** 邀请码 */
    private String inviteCode;

    /** 邀请人用户 ID */
    private Long inviterId;

    /** 受邀默认角色 ID */
    private Long roleId;

    private String inviteePhone;

    private String inviteeEmail;

    /** 状态码：1-有效、2-已使用、3-已失效 */
    private Integer status;

    /** 使用人用户 ID */
    private Long usedBy;

    private LocalDateTime usedTime;

    private LocalDateTime expireTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
