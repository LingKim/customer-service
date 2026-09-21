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
 * 企业资料审核快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("enterprise_review")
public class EnterpriseReview {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;
    private Long enterpriseId;
    private String tenantCode;
    private String applyNo;
    private Integer versionNo;
    private Long applicantId;
    private String companyName;
    private String industry;
    private String scale;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String licenseNo;
    private String registerAddress;
    private String legalPerson;
    private Long licenseFileId;
    private Integer status;
    private Long reviewerId;
    private LocalDateTime reviewTime;
    private String rejectReason;
    private LocalDateTime submitTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private String creator;
    private String editor;

    @TableField("is_deleted")
    private Boolean deleted;
}
