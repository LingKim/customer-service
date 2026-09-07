package cn.net.susan.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 企业账号注册请求（对应登录页「注册企业账号」表单）。
 */
public record RegisterRequest(
        @NotBlank(message = "企业名称不能为空")
        @Size(max = 128, message = "企业名称最长 128 个字符")
        String companyName,

        @Size(max = 32, message = "所属行业最长 32 个字符")
        String industry,

        @Size(max = 32, message = "团队规模最长 32 个字符")
        String scale,

        @NotBlank(message = "联系人姓名不能为空")
        @Size(max = 64, message = "联系人姓名最长 64 个字符")
        String contactName,

        @NotBlank(message = "手机号不能为空")
        @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
        String phone,

        @NotBlank(message = "邮箱不能为空")
        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱最长 128 个字符")
        String email,

        @NotBlank(message = "密码不能为空")
        @Pattern(
                regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                message = "密码需不少于 8 位，且同时包含字母和数字"
        )
        String password,

        @NotBlank(message = "请输入图形验证码")
        String captchaId,

        @NotBlank(message = "请输入图形验证码")
        @Size(max = 8, message = "验证码长度不正确")
        String captchaCode
) {
}
