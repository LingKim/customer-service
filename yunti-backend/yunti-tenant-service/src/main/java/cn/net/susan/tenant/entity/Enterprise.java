package cn.net.susan.tenant.entity;

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
 * enterprise 企业表实体（企业主数据）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("enterprise")
public class Enterprise {

    /** 主键 ID（雪花算法生成） */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 企业编码（对外，定长 16） */
    private String enterpriseCode;

    /** 租户编码（审核通过创建租户后回填） */
    private String tenantCode;

    /** 企业名称 */
    private String companyName;

    /** 所属行业 */
    private String industry;

    /** 团队规模 */
    private String scale;

    /** 统一社会信用代码 / 营业执照号 */
    private String licenseNo;

    /** 注册地址 */
    private String registerAddress;

    /** 法人代表 */
    private String legalPerson;

    /** 营业执照附件文件 ID */
    private Long licenseFileId;

    /** 管理员姓名 */
    private String contactName;

    /** 联系电话 */
    private String contactPhone;

    /** 企业邮箱 */
    private String contactEmail;

    /** 状态码：1-待审核、2-正常、3-已驳回、4-已注销 */
    private Integer status;

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
