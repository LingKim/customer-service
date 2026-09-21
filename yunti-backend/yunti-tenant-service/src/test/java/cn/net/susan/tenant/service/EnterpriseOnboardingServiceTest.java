package cn.net.susan.tenant.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.tenant.entity.Enterprise;
import cn.net.susan.tenant.entity.EnterpriseReview;
import cn.net.susan.tenant.internal.CustomerFileOwnershipClient;
import cn.net.susan.tenant.mapper.EnterpriseMapper;
import cn.net.susan.tenant.mapper.EnterpriseReviewMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnterpriseOnboardingServiceTest {

    private final EnterpriseMapper enterpriseMapper = mock(EnterpriseMapper.class);
    private final EnterpriseReviewMapper reviewMapper = mock(EnterpriseReviewMapper.class);
    private final SnowflakeIdGenerator idGenerator = mock(SnowflakeIdGenerator.class);
    private final CustomerFileOwnershipClient fileOwnershipClient = mock(CustomerFileOwnershipClient.class);

    private EnterpriseOnboardingService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "unit-test"),
                Enterprise.class
        );
        service = new EnterpriseOnboardingService(
                enterpriseMapper,
                reviewMapper,
                idGenerator,
                fileOwnershipClient
        );
    }

    @Test
    void shouldRejectProfileChangeWhileReviewIsPending() {
        givenEnterprise(1);
        when(reviewMapper.findLatestByEnterpriseId(100L))
                .thenReturn(review(EnterpriseOnboardingService.REVIEW_PENDING));

        assertThatThrownBy(() -> service.saveProfile(enterpriseUser(), profile()))
                .isInstanceOf(BizException.class)
                .hasMessage("企业资料审核中，暂不可修改");

        verify(enterpriseMapper, never()).update(any(), any());
    }

    @Test
    void shouldRejectRepeatedSubmitAfterApproval() {
        givenEnterprise(EnterpriseOnboardingService.ENTERPRISE_APPROVED);

        assertThatThrownBy(() -> service.submitReview(enterpriseUser(), "Bearer token", profile()))
                .isInstanceOf(BizException.class)
                .hasMessage("企业已开通，无需重复提交审核");

        verify(fileOwnershipClient, never()).requireOwnedBy(anyLong(), anyString());
        verify(reviewMapper, never()).insert(any(EnterpriseReview.class));
    }

    @Test
    void shouldRejectUnknownReviewStatusExplicitly() {
        givenEnterprise(1);
        when(reviewMapper.findLatestByEnterpriseId(100L)).thenReturn(review(99));

        assertThatThrownBy(() -> service.getState(enterpriseUser()))
                .isInstanceOf(BizException.class)
                .hasMessage("未知的企业审核状态：99");
    }

    @Test
    void shouldValidateLicenseOwnershipBeforeResubmittingRejectedProfile() {
        givenEnterprise(1);
        when(reviewMapper.findLatestByEnterpriseId(100L))
                .thenReturn(review(EnterpriseOnboardingService.REVIEW_REJECTED));
        when(fileOwnershipClient.requireOwnedBy(900L, "Bearer token")).thenReturn(true);
        when(reviewMapper.nextVersionNo(100L)).thenReturn(2);
        when(idGenerator.nextId()).thenReturn(300L);

        EnterpriseOnboardingService.GuideState state = service.submitReview(
                enterpriseUser(), "Bearer token", profile());

        assertThat(state.enterpriseId()).isEqualTo("100");
        assertThat(state.licenseFileId()).isEqualTo("900");
        verify(fileOwnershipClient).requireOwnedBy(900L, "Bearer token");
        verify(reviewMapper).insert(any(EnterpriseReview.class));
    }

    private void givenEnterprise(int status) {
        Enterprise enterprise = Enterprise.builder()
                .id(100L)
                .enterpriseCode("E202609210000001")
                .companyName("云梯测试企业")
                .status(status)
                .creator("200")
                .deleted(false)
                .build();
        when(enterpriseMapper.findByApplicantId("200")).thenReturn(enterprise);
        when(enterpriseMapper.findByApplicantIdForUpdate("200")).thenReturn(enterprise);
    }

    private EnterpriseReview review(int status) {
        return EnterpriseReview.builder()
                .id(500L)
                .enterpriseId(100L)
                .applyNo("YR20260921000000001")
                .versionNo(1)
                .status(status)
                .build();
    }

    private LoginUser enterpriseUser() {
        return new LoginUser(200L, "U000200", "李老板", 2, "");
    }

    private EnterpriseOnboardingService.ProfileRequest profile() {
        return new EnterpriseOnboardingService.ProfileRequest(
                "云梯测试企业",
                "软件和信息技术服务业",
                "1-20人",
                "91310000MA1K123456",
                "上海市浦东新区",
                "张三",
                900L,
                "李老板",
                "13800000000",
                "owner@example.com"
        );
    }
}
