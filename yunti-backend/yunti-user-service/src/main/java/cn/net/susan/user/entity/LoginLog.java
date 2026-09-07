package cn.net.susan.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * login_log 登录记录实体（user_db，只追加）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("login_log")
public class LoginLog {

    /** 主键 ID（雪花算法生成） */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 租户编码 */
    private String tenantCode;

    /** 用户 ID */
    private Long userId;

    /** 登录方式码：1-密码、2-企业微信 SSO、3-飞书 SSO */
    private Integer loginType;

    /** 登录 IP */
    private String ip;

    /** 设备信息 */
    private String device;

    /** 浏览器 */
    private String browser;

    /** 登录地区 */
    private String region;

    /** 结果码：1-成功、2-失败、3-异常 */
    private Integer result;

    /** 登录时间 */
    private LocalDateTime loginTime;

    /** 创建时间 */
    private LocalDateTime createTime;
}
