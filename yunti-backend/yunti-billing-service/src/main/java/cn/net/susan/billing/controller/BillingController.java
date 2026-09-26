package cn.net.susan.billing.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.context.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * billing-service 骨架接口。
 */
@RestController
@RequestMapping("/api/billing")
public class BillingController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.ok(Map.of(
                "service", "billing-service",
                "tenantCode", TenantContext.get() == null ? "" : TenantContext.get()
        ));
    }

    /** 套餐域尚未落地；公开接口只返回已发布的套餐，不伪造报价。 */
    @GetMapping("/public/plans")
    public ApiResponse<List<Map<String, Object>>> publicPlans() {
        return ApiResponse.ok(List.of());
    }
}
