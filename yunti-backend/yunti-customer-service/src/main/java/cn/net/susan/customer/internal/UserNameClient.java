package cn.net.susan.customer.internal;

import cn.net.susan.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class UserNameClient {
    private final RestClient restClient;

    public UserNameClient(@Value("${yunti.user.internal-base-url}") String baseUrl) {
        restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public Map<String, String> namesOf(List<String> userIds, String authorization) {
        List<String> ids = userIds.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        try {
            ApiResponse<Map<String, String>> response = restClient.get()
                    .uri("/api/user/internal/users/names?ids={ids}", String.join(",", ids))
                    .header("Authorization", authorization)
                    .retrieve().body(new ParameterizedTypeReference<>() {});
            return response == null || response.code() != 0 || response.data() == null ? Map.of() : response.data();
        } catch (Exception e) {
            return Map.of();
        }
    }
}
