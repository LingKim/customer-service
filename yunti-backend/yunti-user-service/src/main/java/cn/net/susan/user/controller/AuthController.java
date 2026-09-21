package cn.net.susan.user.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.api.ResultCode;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.user.dto.CaptchaResponse;
import cn.net.susan.user.dto.LoginRequest;
import cn.net.susan.user.dto.LoginResponse;
import cn.net.susan.user.dto.MeResponse;
import cn.net.susan.user.dto.RegisterRequest;
import cn.net.susan.user.dto.RegisterResponse;
import cn.net.susan.user.mapper.SysUserMapper;
import cn.net.susan.user.security.CaptchaStore;
import cn.net.susan.user.security.JwtService;
import cn.net.susan.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 企业用户注册 / 登录 / 验证码 / 当前用户。
 */
@RestController
@RequestMapping("/api/user/auth")
public class AuthController {

    private static final String IMAGE_PREFIX = "data:image/png;base64,";

    private final AuthService authService;
    private final CaptchaStore captchaStore;
    private final JwtService jwtService;
    private final SysUserMapper sysUserMapper;
    private final boolean captchaDebug;

    public AuthController(
            AuthService authService,
            CaptchaStore captchaStore,
            JwtService jwtService,
            SysUserMapper sysUserMapper,
            @Value("${yunti.auth.captcha-debug:false}") boolean captchaDebug
    ) {
        this.authService = authService;
        this.captchaStore = captchaStore;
        this.jwtService = jwtService;
        this.sysUserMapper = sysUserMapper;
        this.captchaDebug = captchaDebug;
    }

    /**
     * 获取图形验证码（登录 / 注册共用）。
     */
    @GetMapping("/captcha")
    public ApiResponse<CaptchaResponse> captcha() {
        String code = captchaStore.randomCode();
        String id = captchaStore.create(code);
        return ApiResponse.ok(new CaptchaResponse(id, IMAGE_PREFIX + toBase64(render(code)),
                captchaDebug ? code : null));
    }

    /**
     * 企业账号注册。
     */
    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    /**
     * 账号密码登录。
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {
        String ip = httpRequest.getRemoteAddr();
        String userAgent = httpRequest.getHeader("User-Agent");
        return ApiResponse.ok(authService.login(request, ip, userAgent));
    }

    /**
     * 当前登录用户（前端进入后台后用于恢复会话）。
     */
    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        LoginUser loginUser = jwtService.parseToken(resolveBearer(authorization));
        if (sysUserMapper.findByAccount(loginUser.userNo()) == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return ApiResponse.ok(MeResponse.from(loginUser));
    }

    /**
     * 修改当前登录用户密码。
     */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ChangePasswordRequest request
    ) {
        LoginUser loginUser = jwtService.parseToken(resolveBearer(authorization));
        authService.changePassword(loginUser, request.oldPassword(), request.newPassword());
        return ApiResponse.ok();
    }

    private String resolveBearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return authorization.substring(7);
    }

    private BufferedImage render(String code) {
        int width = 120;
        int height = 40;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(243, 246, 250));
        g.fillRect(0, 0, width, height);
        g.setColor(new Color(100, 116, 139, 80));
        g.setStroke(new BasicStroke(1.2f));
        for (int i = 0; i < 5; i++) {
            int x1 = (int) (Math.random() * width);
            int y1 = (int) (Math.random() * height);
            g.drawLine(x1, y1, x1 + 24, y1 - 18);
        }
        g.setFont(new Font("SansSerif", Font.BOLD, 24));
        char[] chars = code.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            g.setColor(new Color(30, 64, 175));
            g.drawString(String.valueOf(chars[i]), 16 + i * 24, 29);
        }
        g.dispose();
        return image;
    }

    private String toBase64(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("验证码图片生成失败", e);
        }
    }

    /**
     * 修改密码请求体。
     */
    public record ChangePasswordRequest(
            @NotBlank(message = "请输入原密码")
            String oldPassword,
            @NotBlank(message = "请输入新密码")
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
                    message = "新密码需不少于 8 位，且同时包含字母和数字")
            @Size(max = 64)
            String newPassword
    ) {
    }
}
