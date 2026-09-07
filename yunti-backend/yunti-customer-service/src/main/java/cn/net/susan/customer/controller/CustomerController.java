package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * customer-service 骨架接口。
 */
@RestController
@RequestMapping("/api/customer")
public class CustomerController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.ok(Map.of(
                "service", "customer-service",
                "tenantCode", TenantContext.get() == null ? "" : TenantContext.get()
        ));
    }
}
