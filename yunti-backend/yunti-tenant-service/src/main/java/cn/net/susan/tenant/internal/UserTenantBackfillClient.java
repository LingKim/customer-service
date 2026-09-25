package cn.net.susan.tenant.internal;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class UserTenantBackfillClient {
    private final RestClient restClient;

    public UserTenantBackfillClient(@Value("${yunti.user.internal-base-url}") String baseUrl) {
        restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public void backfillTenantCode(long userId, String tenantCode, String authorization) {
        ApiResponse<Void> response = restClient.put()
                .uri("/api/user/internal/users/{userId}/tenant-code", userId)
                .header("Authorization", authorization)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new BackfillBody(tenantCode))
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
        if (response == null || response.code() != 0) {
            throw new BizException(50201, "用户租户编码回填失败，请重试审核通过操作");
        }
    }

    private record BackfillBody(String tenantCode) {}
}
