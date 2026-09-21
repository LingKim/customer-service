package cn.net.susan.tenant.service;

import cn.net.susan.common.api.ResultCode;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.tenant.entity.Enterprise;
import cn.net.susan.tenant.entity.EnterpriseReview;
import cn.net.susan.tenant.internal.CustomerFileOwnershipClient;
import cn.net.susan.tenant.mapper.EnterpriseMapper;
import cn.net.susan.tenant.mapper.EnterpriseReviewMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 企业用户自助开通服务。
 */
@Service
public class EnterpriseOnboardingService {

    public static final int ENTERPRISE_APPROVED = 2;
    public static final int REVIEW_PENDING = 1;
    public static final int REVIEW_APPROVED = 2;
    public static final int REVIEW_REJECTED = 3;

    public static final String STAGE_PENDING_PROFILE = "PENDING_PROFILE";
    public static final String STAGE_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STAGE_REJECTED = "REJECTED";
    public static final String STAGE_APPROVED = "APPROVED";

    private static final int ENTERPRISE_PENDING = 1;
    private static final int ENTERPRISE_REJECTED = 3;
    private static final int ENTERPRISE_CANCELLED = 4;
    private static final int ENTERPRISE_USER = 2;
    private static final DateTimeFormatter APPLY_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final EnterpriseMapper enterpriseMapper;
    private final EnterpriseReviewMapper enterpriseReviewMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final CustomerFileOwnershipClient fileOwnershipClient;

    public EnterpriseOnboardingService(
            EnterpriseMapper enterpriseMapper,
            EnterpriseReviewMapper enterpriseReviewMapper,
            SnowflakeIdGenerator idGenerator,
            CustomerFileOwnershipClient fileOwnershipClient
    ) {
        this.enterpriseMapper = enterpriseMapper;
        this.enterpriseReviewMapper = enterpriseReviewMapper;
        this.idGenerator = idGenerator;
        this.fileOwnershipClient = fileOwnershipClient;
    }

    @Transactional(readOnly = true)
    public GuideState getState(LoginUser user) {
        Enterprise enterprise = requireEnterprise(user);
        EnterpriseReview review = enterpriseReviewMapper.findLatestByEnterpriseId(enterprise.getId());
        return toState(enterprise, review, resolveStage(enterprise, review));
    }

    @Transactional
    public GuideState saveProfile(LoginUser user, ProfileRequest request) {
        Enterprise enterprise = requireEnterprise(user);
        EnterpriseReview review = enterpriseReviewMapper.findLatestByEnterpriseId(enterprise.getId());
        String stage = resolveStage(enterprise, review);
        requireEditable(stage);
        updateEnterpriseProfile(enterprise, request);
        return toState(enterprise, review, stage);
    }

    @Transactional
    public GuideState submitReview(LoginUser user, String authorization, ProfileRequest request) {
        Enterprise enterprise = requireEnterpriseForUpdate(user);
        EnterpriseReview latestReview = enterpriseReviewMapper.findLatestByEnterpriseId(enterprise.getId());
        requireEditable(resolveStage(enterprise, latestReview));

        fileOwnershipClient.requireOwnedBy(request.licenseFileId(), authorization);

        LocalDateTime now = LocalDateTime.now();
        EnterpriseReview review = EnterpriseReview.builder()
                .id(idGenerator.nextId())
                .enterpriseId(enterprise.getId())
                .tenantCode(emptyToNull(enterprise.getTenantCode()))
                .applyNo(generateApplyNo(now))
                .versionNo(enterpriseReviewMapper.nextVersionNo(enterprise.getId()))
                .applicantId(user.userId())
                .companyName(request.companyName())
                .industry(request.industry())
                .scale(request.scale())
                .contactName(request.contactName())
                .contactPhone(request.contactPhone())
                .contactEmail(request.contactEmail())
                .licenseNo(request.licenseNo())
                .registerAddress(request.registerAddress())
                .legalPerson(request.legalPerson())
                .licenseFileId(request.licenseFileId())
                .status(REVIEW_PENDING)
                .submitTime(now)
                .creator(String.valueOf(user.userId()))
                .deleted(false)
                .build();
        enterpriseReviewMapper.insert(review);
        updateEnterpriseProfile(enterprise, request);
        return toState(enterprise, review, STAGE_PENDING_REVIEW);
    }

    private Enterprise requireEnterprise(LoginUser user) {
        if (user == null || user.userType() != ENTERPRISE_USER) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        Enterprise enterprise = enterpriseMapper.findByApplicantId(String.valueOf(user.userId()));
        if (enterprise == null) {
            throw new BizException(40401, "账号未关联企业资料，请通过注册流程开通企业");
        }
        return enterprise;
    }

    private Enterprise requireEnterpriseForUpdate(LoginUser user) {
        if (user == null || user.userType() != ENTERPRISE_USER) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        Enterprise enterprise = enterpriseMapper.findByApplicantIdForUpdate(String.valueOf(user.userId()));
        if (enterprise == null) {
            throw new BizException(40401, "账号未关联企业资料，请通过注册流程开通企业");
        }
        return enterprise;
    }

    private String resolveStage(Enterprise enterprise, EnterpriseReview review) {
        Integer enterpriseStatus = enterprise.getStatus();
        if (enterpriseStatus == null) {
            throw new BizException(40002, "企业状态缺失");
        }
        switch (enterpriseStatus) {
            case ENTERPRISE_APPROVED:
                return STAGE_APPROVED;
            case ENTERPRISE_CANCELLED:
                throw new BizException(40002, "企业已注销，无法办理开通");
            case ENTERPRISE_PENDING, ENTERPRISE_REJECTED:
                break;
            default:
                throw new BizException(40002, "未知的企业状态：" + enterpriseStatus);
        }

        if (review == null) {
            return enterpriseStatus == ENTERPRISE_REJECTED ? STAGE_REJECTED : STAGE_PENDING_PROFILE;
        }
        Integer reviewStatus = review.getStatus();
        if (reviewStatus == null) {
            throw new BizException(40002, "企业审核状态缺失");
        }
        return switch (reviewStatus) {
            case REVIEW_PENDING -> STAGE_PENDING_REVIEW;
            case REVIEW_APPROVED -> STAGE_APPROVED;
            case REVIEW_REJECTED -> STAGE_REJECTED;
            default -> throw new BizException(40002, "未知的企业审核状态：" + reviewStatus);
        };
    }

    private void requireEditable(String stage) {
        switch (stage) {
            case STAGE_PENDING_PROFILE, STAGE_REJECTED:
                return;
            case STAGE_PENDING_REVIEW:
                throw new BizException(40001, "企业资料审核中，暂不可修改");
            case STAGE_APPROVED:
                throw new BizException(40001, "企业已开通，无需重复提交审核");
            default:
                throw new BizException(40002, "未知的企业开通阶段：" + stage);
        }
    }

    private void updateEnterpriseProfile(Enterprise enterprise, ProfileRequest request) {
        LambdaUpdateWrapper<Enterprise> update = Wrappers.lambdaUpdate(Enterprise.class)
                .eq(Enterprise::getId, enterprise.getId())
                .eq(Enterprise::getDeleted, false)
                .set(Enterprise::getCompanyName, request.companyName())
                .set(Enterprise::getIndustry, request.industry())
                .set(Enterprise::getScale, request.scale())
                .set(Enterprise::getLicenseNo, request.licenseNo())
                .set(Enterprise::getRegisterAddress, request.registerAddress())
                .set(Enterprise::getLegalPerson, request.legalPerson())
                .set(Enterprise::getLicenseFileId, request.licenseFileId())
                .set(Enterprise::getContactName, request.contactName())
                .set(Enterprise::getContactPhone, request.contactPhone())
                .set(Enterprise::getContactEmail, request.contactEmail())
                .set(Enterprise::getEditor, "SELF_SERVICE")
                .set(Enterprise::getUpdateTime, LocalDateTime.now());
        enterpriseMapper.update(null, update);

        enterprise.setCompanyName(request.companyName());
        enterprise.setIndustry(request.industry());
        enterprise.setScale(request.scale());
        enterprise.setLicenseNo(request.licenseNo());
        enterprise.setRegisterAddress(request.registerAddress());
        enterprise.setLegalPerson(request.legalPerson());
        enterprise.setLicenseFileId(request.licenseFileId());
        enterprise.setContactName(request.contactName());
        enterprise.setContactPhone(request.contactPhone());
        enterprise.setContactEmail(request.contactEmail());
    }

    private GuideState toState(Enterprise enterprise, EnterpriseReview review, String stage) {
        return new GuideState(
                String.valueOf(enterprise.getId()),
                enterprise.getEnterpriseCode(),
                stage,
                enterprise.getCompanyName(),
                enterprise.getIndustry(),
                enterprise.getScale(),
                enterprise.getLicenseNo(),
                enterprise.getRegisterAddress(),
                enterprise.getLegalPerson(),
                enterprise.getLicenseFileId() == null ? null : String.valueOf(enterprise.getLicenseFileId()),
                enterprise.getContactName(),
                enterprise.getContactPhone(),
                enterprise.getContactEmail(),
                review == null ? null : review.getApplyNo(),
                review == null ? null : review.getVersionNo(),
                review == null ? null : review.getRejectReason(),
                review == null || review.getSubmitTime() == null ? null : review.getSubmitTime().toString(),
                emptyToNull(enterprise.getTenantCode())
        );
    }

    private String generateApplyNo(LocalDateTime now) {
        return "YR" + APPLY_NO_TIME.format(now) + ThreadLocalRandom.current().nextInt(1000, 10000);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record GuideState(
            String enterpriseId,
            String enterpriseCode,
            String stage,
            String companyName,
            String industry,
            String scale,
            String licenseNo,
            String registerAddress,
            String legalPerson,
            String licenseFileId,
            String contactName,
            String contactPhone,
            String contactEmail,
            String applyNo,
            Integer versionNo,
            String rejectReason,
            String submitTime,
            String tenantCode
    ) {
    }

    public record ProfileRequest(
            String companyName,
            String industry,
            String scale,
            String licenseNo,
            String registerAddress,
            String legalPerson,
            Long licenseFileId,
            String contactName,
            String contactPhone,
            String contactEmail
    ) {
    }
}
