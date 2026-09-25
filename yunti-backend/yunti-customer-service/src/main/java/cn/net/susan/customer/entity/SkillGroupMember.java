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
 * skill_group_member 技能组坐席绑定实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("skill_group_member")
public class SkillGroupMember {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String tenantCode;

    private Long skillGroupId;

    private Long userId;

    /** 是否组长 */
    private Boolean isLeader;

    /** 状态码：1-在组、2-已移出 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private String creator;

    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
