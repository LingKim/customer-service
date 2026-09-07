package cn.net.susan.gateway.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 网关占位接口。
 */
@RestController
@RequestMapping("/api/gateway")
public class GatewayController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.ok(Map.of(
                "service", "gateway-service",
                "tenantCode", TenantContext.get() == null ? "" : TenantContext.get()
        ));
    }
}
