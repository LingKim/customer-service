package cn.net.susan.customer.internal;

import cn.net.susan.common.api.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * user-service 内部客户端：查用户的租户角色（工单权限判断用）。
 *
 * <p>为什么让 customer-service 反向查一次，而不是把角色塞进 JWT：
 * 角色是**会变的**（今天把某个客服提成主管，明天就该生效），塞进令牌要等令牌过期才生效；
 * 而且令牌是给前端用的，不该承载服务间的权限判断。这里一次内部调用就够（本地毫秒级）。</p>
 */
@Component
public class UserRoleClient {

    private static final Logger log = LoggerFactory.getLogger(UserRoleClient.class);

    private final RestClient restClient;
    private final String sharedSecret;

    public UserRoleClient(@Value("${yunti.user.internal-base-url}") String baseUrl,
                          @Value("${yunti.internal.shared-secret:}") String sharedSecret) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.sharedSecret = sharedSecret;
    }

    /**
     * 查用户在某租户下的角色编码。
     *
     * @return 角色编码列表；**返回 null 表示"问不到"**（用户服务不可用），
     *         返回空列表表示"这个租户还没有角色数据"（老租户，按企业管理员兼容）。
     *         两者语义不同，调用方要区别对待：问不到时保守拒绝管理类操作，
     *         但日常作业（建单/回复）照常放行。
     */
    public List<String> rolesOf(long userId, String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            return List.of();
        }
        if (sharedSecret == null || sharedSecret.isBlank()) {
            log.warn("未配置用户角色查询内部共享密钥");
            return null;
        }
        try {
            ApiResponse<List<String>> response = restClient.get()
                    .header("X-Yunti-Internal-Secret", sharedSecret)
                    .uri("/api/user/internal/users/{userId}/roles?tenantCode={tenant}",
                            userId, tenantCode)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {
                    });
            return response == null || response.code() != 0 || response.data() == null
                    ? null : response.data();
        } catch (Exception e) {
            log.warn("查询用户角色失败（按保守策略处理）userId={} tenant={} error={}",
                    userId, tenantCode, e.getMessage());
            return null;
        }
    }
}
