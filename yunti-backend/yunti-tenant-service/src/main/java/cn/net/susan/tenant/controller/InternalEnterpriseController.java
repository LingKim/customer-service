package cn.net.susan.tenant.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.tenant.service.EnterpriseDraftService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部接口：仅供 user-service 注册流程调用（企业资料落库）。
 */
@RestController
@RequestMapping("/api/tenant/internal")
public class InternalEnterpriseController {

    private final EnterpriseDraftService draftService;

    public InternalEnterpriseController(EnterpriseDraftService draftService) {
        this.draftService = draftService;
    }

    /**
     * 创建企业草稿。
     */
    @PostMapping("/enterprises/draft")
    public ApiResponse<EnterpriseDraftService.DraftResult> createDraft(@RequestBody DraftBody body) {
        EnterpriseDraftService.DraftResult result = draftService.createDraft(
                new EnterpriseDraftService.DraftCommand(
                        body.applicantId(),
                        body.companyName(),
                        body.industry(),
                        body.scale(),
                        body.contactName(),
                        body.contactPhone(),
                        body.contactEmail()
                )
        );
        return ApiResponse.ok(result);
    }

    /**
     * 内部请求体。
     */
    public record DraftBody(
            @NotNull(message = "申请人用户 ID 不能为空")
            Long applicantId,
            @NotBlank(message = "企业名称不能为空")
            @Size(max = 128)
            String companyName,
            @Size(max = 32)
            String industry,
            @Size(max = 32)
            String scale,
            @NotBlank(message = "联系人不能为空")
            @Size(max = 64)
            String contactName,
            @Size(max = 20)
            String contactPhone,
            @Size(max = 128)
            String contactEmail
    ) {
    }
}
