package cn.net.susan.tenant.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.api.ResultCode;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.tenant.security.JwtTokenParser;
import cn.net.susan.tenant.service.EnterpriseOnboardingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前企业用户的企业资料与审核状态接口。
 */
@RestController
@RequestMapping("/api/tenant/enterprise/my")
public class EnterpriseOnboardingController {

    private final EnterpriseOnboardingService onboardingService;
    private final JwtTokenParser jwtTokenParser;

    public EnterpriseOnboardingController(
            EnterpriseOnboardingService onboardingService,
            JwtTokenParser jwtTokenParser
    ) {
        this.onboardingService = onboardingService;
        this.jwtTokenParser = jwtTokenParser;
    }

    @GetMapping
    public ApiResponse<EnterpriseOnboardingService.GuideState> state(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return ApiResponse.ok(onboardingService.getState(jwtTokenParser.requireLoginUser(authorization)));
    }

    @PostMapping("/profile")
    public ApiResponse<EnterpriseOnboardingService.GuideState> saveProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ProfileBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(onboardingService.saveProfile(user, toRequest(body)));
    }

    @PostMapping("/submit")
    public ApiResponse<EnterpriseOnboardingService.GuideState> submit(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ProfileBody body
    ) {
        LoginUser user = jwtTokenParser.requireLoginUser(authorization);
        return ApiResponse.ok(onboardingService.submitReview(user, authorization, toRequest(body)));
    }

    private EnterpriseOnboardingService.ProfileRequest toRequest(ProfileBody body) {
        try {
            long fileId = Long.parseLong(body.licenseFileId());
            if (fileId <= 0) {
                throw new NumberFormatException("non-positive file id");
            }
            return new EnterpriseOnboardingService.ProfileRequest(
                    body.companyName(),
                    body.industry(),
                    body.scale(),
                    body.licenseNo(),
                    body.registerAddress(),
                    body.legalPerson(),
                    fileId,
                    body.contactName(),
                    body.contactPhone(),
                    body.contactEmail()
            );
        } catch (NumberFormatException e) {
            throw new BizException(ResultCode.BAD_REQUEST.getCode(), "营业执照文件 ID 格式不正确");
        }
    }

    public record ProfileBody(
            @NotBlank(message = "企业名称不能为空")
            @Size(max = 128)
            String companyName,
            @NotBlank(message = "所属行业不能为空")
            @Size(max = 32)
            String industry,
            @NotBlank(message = "团队规模不能为空")
            @Size(max = 32)
            String scale,
            @NotBlank(message = "统一社会信用代码不能为空")
            @Pattern(regexp = "^[0-9A-HJ-NPQRTUWXY]{18}$", message = "统一社会信用代码格式不正确")
            String licenseNo,
            @NotBlank(message = "注册地址不能为空")
            @Size(max = 255)
            String registerAddress,
            @NotBlank(message = "法人代表不能为空")
            @Size(max = 64)
            String legalPerson,
            @NotBlank(message = "请上传营业执照扫描件")
            @Pattern(regexp = "^[1-9]\\d*$", message = "营业执照文件 ID 格式不正确")
            String licenseFileId,
            @NotBlank(message = "管理员姓名不能为空")
            @Size(max = 64)
            String contactName,
            @NotBlank(message = "联系电话不能为空")
            @Pattern(regexp = "^1\\d{10}$", message = "联系电话格式不正确")
            String contactPhone,
            @NotBlank(message = "企业邮箱不能为空")
            @Email(message = "企业邮箱格式不正确")
            @Size(max = 128)
            String contactEmail
    ) {
    }
}
