package cn.net.susan.tenant.admin.service;

import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.tenant.admin.dto.ReviewPageVO;
import cn.net.susan.tenant.entity.Enterprise;
import cn.net.susan.tenant.entity.EnterpriseReview;
import cn.net.susan.tenant.entity.Tenant;
import cn.net.susan.tenant.internal.UserTenantBackfillClient;
import cn.net.susan.tenant.mapper.EnterpriseMapper;
import cn.net.susan.tenant.mapper.EnterpriseReviewMapper;
import cn.net.susan.tenant.mapper.TenantMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class EnterpriseReviewAdminService {
    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final EnterpriseReviewMapper reviewMapper;
    private final EnterpriseMapper enterpriseMapper;
    private final TenantMapper tenantMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final UserTenantBackfillClient backfillClient;
    private final TransactionTemplate transactionTemplate;

    public EnterpriseReviewAdminService(EnterpriseReviewMapper reviewMapper,
                                        EnterpriseMapper enterpriseMapper,
                                        TenantMapper tenantMapper,
                                        SnowflakeIdGenerator idGenerator,
                                        UserTenantBackfillClient backfillClient,
                                        TransactionTemplate transactionTemplate) {
        this.reviewMapper = reviewMapper;
        this.enterpriseMapper = enterpriseMapper;
        this.tenantMapper = tenantMapper;
        this.idGenerator = idGenerator;
        this.backfillClient = backfillClient;
        this.transactionTemplate = transactionTemplate;
    }

    @Transactional(readOnly = true)
    public IPage<ReviewPageVO> page(int pageNum, int pageSize, Integer status, String keyword) {
        String search = keyword == null || keyword.isBlank() ? null : keyword.trim();
        return reviewMapper.selectReviewAdminPage(new Page<>(pageNum, pageSize), status, search);
    }

    @Transactional(readOnly = true)
    public ReviewPageVO detail(long reviewId) {
        EnterpriseReview review = requireReview(reviewId);
        Enterprise enterprise = requireEnterprise(review.getEnterpriseId());
        return toVO(review, enterprise);
    }

    /**
     * 本地事务先提交，再执行可重试的用户归属回填。回填失败时重试本接口不会重复创建租户。
     */
    public ReviewPageVO approve(long reviewId, long reviewerId, String authorization) {
        Approval approval = transactionTemplate.execute(status -> approveLocal(reviewId, reviewerId));
        if (approval == null) {
            throw new BizException(50001, "审核事务未完成");
        }
        backfillClient.backfillTenantCode(approval.applicantId(), approval.tenantCode(), authorization);
        return detail(reviewId);
    }

    private Approval approveLocal(long reviewId, long reviewerId) {
        EnterpriseReview snapshot = requireReview(reviewId);
        Enterprise enterprise = enterpriseMapper.findByIdForUpdate(snapshot.getEnterpriseId());
        if (enterprise == null) {
            throw new BizException(40401, "企业信息不存在");
        }
        EnterpriseReview review = reviewMapper.findByIdForUpdate(reviewId);
        if (review == null) {
            throw new BizException(40401, "审核记录不存在");
        }
        if (review.getStatus() == 2) {
            if (enterprise.getStatus() != 2 || enterprise.getTenantCode() == null) {
                throw new BizException(40002, "审核与企业状态不一致");
            }
            return new Approval(review.getApplicantId(), enterprise.getTenantCode());
        }
        requirePendingLatest(review, enterprise);
        if (tenantMapper.selectCount(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getEnterpriseId, enterprise.getId()).eq(Tenant::getDeleted, false)) > 0) {
            throw new BizException(40002, "企业已创建租户");
        }

        long sequence = tenantMapper.nextCodeSequence();
        if (sequence > 9_999_999L) {
            throw new BizException(50001, "租户编码序号已用尽");
        }
        String tenantCode = "T" + CODE_DATE.format(LocalDate.now()) + String.format("%07d", sequence);
        LocalDateTime now = LocalDateTime.now();
        tenantMapper.insert(Tenant.builder().id(idGenerator.nextId()).tenantCode(tenantCode)
                .enterpriseId(enterprise.getId()).status(1).isolationMode(1)
                .creator(String.valueOf(reviewerId)).deleted(false).build());
        enterprise.setTenantCode(tenantCode);
        enterprise.setStatus(2);
        enterprise.setEditor(String.valueOf(reviewerId));
        enterprise.setUpdateTime(now);
        enterpriseMapper.updateById(enterprise);
        review.setTenantCode(tenantCode);
        review.setStatus(2);
        review.setReviewerId(reviewerId);
        review.setReviewTime(now);
        review.setUpdateTime(now);
        reviewMapper.updateById(review);
        return new Approval(review.getApplicantId(), tenantCode);
    }

    @Transactional
    public ReviewPageVO reject(long reviewId, long reviewerId, String reason) {
        EnterpriseReview snapshot = requireReview(reviewId);
        Enterprise enterprise = enterpriseMapper.findByIdForUpdate(snapshot.getEnterpriseId());
        if (enterprise == null) {
            throw new BizException(40401, "企业信息不存在");
        }
        EnterpriseReview review = reviewMapper.findByIdForUpdate(reviewId);
        if (review == null) {
            throw new BizException(40401, "审核记录不存在");
        }
        requirePendingLatest(review, enterprise);
        LocalDateTime now = LocalDateTime.now();
        review.setStatus(3);
        review.setRejectReason(reason.trim());
        review.setReviewerId(reviewerId);
        review.setReviewTime(now);
        review.setUpdateTime(now);
        reviewMapper.updateById(review);
        enterprise.setStatus(3);
        enterprise.setEditor(String.valueOf(reviewerId));
        enterprise.setUpdateTime(now);
        enterpriseMapper.updateById(enterprise);
        return toVO(review, enterprise);
    }

    private void requirePendingLatest(EnterpriseReview review, Enterprise enterprise) {
        if (review.getStatus() != 1 || enterprise.getStatus() != 1) {
            throw new BizException(40001, "仅待审核申请可操作");
        }
        EnterpriseReview latest = reviewMapper.findLatestByEnterpriseId(enterprise.getId());
        if (latest == null || !latest.getId().equals(review.getId())) {
            throw new BizException(40001, "只能审核最新申请");
        }
    }

    private EnterpriseReview requireReview(long reviewId) {
        EnterpriseReview review = reviewMapper.selectById(reviewId);
        if (review == null || Boolean.TRUE.equals(review.getDeleted())) {
            throw new BizException(40401, "审核记录不存在");
        }
        return review;
    }

    private Enterprise requireEnterprise(long enterpriseId) {
        Enterprise enterprise = enterpriseMapper.selectById(enterpriseId);
        if (enterprise == null || Boolean.TRUE.equals(enterprise.getDeleted())) {
            throw new BizException(40401, "企业信息不存在");
        }
        return enterprise;
    }

    private ReviewPageVO toVO(EnterpriseReview review, Enterprise enterprise) {
        ReviewPageVO vo = new ReviewPageVO();
        vo.setId(String.valueOf(review.getId()));
        vo.setEnterpriseId(String.valueOf(enterprise.getId()));
        vo.setEnterpriseCode(enterprise.getEnterpriseCode());
        vo.setTenantCode(enterprise.getTenantCode());
        vo.setApplyNo(review.getApplyNo());
        vo.setVersionNo(review.getVersionNo());
        vo.setApplicantId(String.valueOf(review.getApplicantId()));
        vo.setCompanyName(review.getCompanyName());
        vo.setIndustry(review.getIndustry());
        vo.setScale(review.getScale());
        vo.setContactName(review.getContactName());
        vo.setContactPhone(review.getContactPhone());
        vo.setContactEmail(review.getContactEmail());
        vo.setLicenseNo(review.getLicenseNo());
        vo.setRegisterAddress(review.getRegisterAddress());
        vo.setLegalPerson(review.getLegalPerson());
        vo.setLicenseFileId(review.getLicenseFileId() == null ? null : String.valueOf(review.getLicenseFileId()));
        vo.setStatus(review.getStatus());
        vo.setRejectReason(review.getRejectReason());
        vo.setReviewerId(review.getReviewerId() == null ? null : String.valueOf(review.getReviewerId()));
        vo.setReviewTime(review.getReviewTime());
        vo.setSubmitTime(review.getSubmitTime());
        vo.setEnterpriseStatus(enterprise.getStatus());
        return vo;
    }

    private record Approval(long applicantId, String tenantCode) {}
}
