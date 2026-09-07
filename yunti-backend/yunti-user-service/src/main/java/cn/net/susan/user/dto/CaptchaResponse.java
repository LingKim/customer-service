package cn.net.susan.user.dto;

/**
 * 图形验证码响应。
 *
 * @param captchaId   验证码 ID（提交登录/注册时回传）
 * @param imageBase64 验证码图片（data:image/png;base64, 前缀）
 * @param debugCode   仅验证码调试模式（captcha-debug=true）返回明文，生产不返回
 */
public record CaptchaResponse(String captchaId, String imageBase64, String debugCode) {
}
