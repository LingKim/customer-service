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
 * sys_user 用户表实体（平台账号 + 企业账号统一存放）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("sys_user")
public class SysUser {

    /** 主键 ID（雪花算法生成，非自增） */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 主归属租户；平台账号为 PLATFORM，未开通企业暂挂 PLATFORM，开通后回填正式 T 编码 */
    private String tenantCode;

    /** 用户编号（对外） */
    private String userNo;

    /** 姓名 */
    private String name;

    /** 手机号（脱敏存储） */
    private String phone;

    /** 邮箱 */
    private String email;

    /** 密码哈希（BCrypt），SSO 账号可为空 */
    private String password;

    /** 头像 URL */
    private String avatar;

    /** 类型码：1-平台账号、2-企业账号 */
    private Integer userType;

    /** 状态码：1-正常、2-停用、3-锁定 */
    private Integer status;

    /** 最近登录时间 */
    private LocalDateTime lastLoginTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;

    /** 创建人 */
    private String creator;

    /** 更新人 */
    private String editor;

    /** 逻辑删除标记 */
    @TableField("is_deleted")
    private Boolean deleted;
}
