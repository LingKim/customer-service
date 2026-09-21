package cn.net.susan.tenant.internal;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * customer-service 内部文件归属校验客户端。
 */
@Component
public class CustomerFileOwnershipClient {

    private static final int FILE_VALIDATION_FAILED = 40003;

    private final RestClient restClient;

    public CustomerFileOwnershipClient(
            RestClient.Builder restClientBuilder,
            @Value("${yunti.customer.internal-base-url:http://127.0.0.1:9093}") String baseUrl
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    public boolean requireOwnedBy(long fileId, String authorization) {
        try {
            ApiResponse<Boolean> response = restClient.get()
                    .uri("/api/customer/internal/files/enterprise/{fileId}/ownership", fileId)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            if (response == null || response.code() != 0 || !Boolean.TRUE.equals(response.data())) {
                throw new BizException(FILE_VALIDATION_FAILED, "营业执照文件不存在或不属于当前用户");
            }
            return true;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(FILE_VALIDATION_FAILED, "营业执照文件校验失败，请稍后重试");
        }
    }
}
