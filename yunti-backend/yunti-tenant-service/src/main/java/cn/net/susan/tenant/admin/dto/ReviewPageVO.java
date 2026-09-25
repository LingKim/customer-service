package cn.net.susan.tenant.admin.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ReviewPageVO {
    private String id;
    private String enterpriseId;
    private String enterpriseCode;
    private String tenantCode;
    private String applyNo;
    private Integer versionNo;
    private String applicantId;
    private String companyName;
    private String industry;
    private String scale;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String licenseNo;
    private String registerAddress;
    private String legalPerson;
    private String licenseFileId;
    private Integer status;
    private String rejectReason;
    private String reviewerId;
    private LocalDateTime reviewTime;
    private LocalDateTime submitTime;
    private Integer enterpriseStatus;
}
