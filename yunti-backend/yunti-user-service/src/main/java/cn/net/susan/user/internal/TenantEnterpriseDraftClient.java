package cn.net.susan.user.internal;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * tenant-service 内部接口客户端（企业草稿落库）。
 *
 * <p>仅 user-service 内部调用，不对外开放；生产可替换为 RPC / 消息事件。
 */
@Component
public class TenantEnterpriseDraftClient {

    /**
     * 企业草稿创建请求。
     */
    public record DraftRequest(
            long applicantId,
            String companyName,
            String industry,
            String scale,
            String contactName,
            String contactPhone,
            String contactEmail
    ) {
    }

    /**
     * 企业草稿创建结果。
     */
    public record DraftResponse(long enterpriseId, String enterpriseCode, String tenantCode, int status) {
    }

    private final RestClient restClient;

    private static final Logger log = LoggerFactory.getLogger(TenantEnterpriseDraftClient.class);

    public TenantEnterpriseDraftClient(@Value("${yunti.tenant.internal-base-url}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 创建企业草稿；失败抛业务异常。
     */
    public DraftResponse createDraft(DraftRequest request) {
        try {
            ApiResponse<DraftResponse> response = restClient.post()
                    .uri("/api/tenant/internal/enterprises/draft")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null || response.code() != 0 || response.data() == null) {
                String message = response == null ? "企业资料保存失败" : response.message();
                throw new BizException(50001, message);
            }
            return response.data();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(50001, "企业资料保存服务暂不可用，请稍后重试");
        }
    }

    /**
     * 逻辑删除企业草稿（注册补偿：账号落库失败时回收已预占的编码）。
     */
    public void deleteDraft(String enterpriseCode) {
        try {
            restClient.delete()
                    .uri("/api/tenant/internal/enterprises/draft/{enterpriseCode}", enterpriseCode)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            // 补偿失败只记录日志，不掩盖原始异常
            log.warn("企业草稿回收失败 enterpriseCode={}", enterpriseCode, e);
        }
    }
}
