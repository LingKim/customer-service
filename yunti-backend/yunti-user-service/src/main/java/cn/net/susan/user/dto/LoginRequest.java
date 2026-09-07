package cn.net.susan.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求（账号支持：手机号 / 邮箱 / 用户编号）。
 */
public record LoginRequest(
        @NotBlank(message = "账号不能为空")
        @Size(max = 128, message = "账号最长 128 个字符")
        String account,

        @NotBlank(message = "密码不能为空")
        @Size(max = 64, message = "密码最长 64 个字符")
        String password,

        @NotBlank(message = "请输入图形验证码")
        String captchaId,

        @NotBlank(message = "请输入图形验证码")
        @Size(max = 8, message = "验证码长度不正确")
        String captchaCode
) {
}
