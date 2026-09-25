package cn.net.susan.user.service;

import cn.net.susan.common.exception.BizException;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

/**
 * 成员邀请邮件发送服务（HTML 模板，163/SSL SMTP 方案参考企业知识库后端）。
 */
@Service
public class MemberMailService {

    private static final Logger log = LoggerFactory.getLogger(MemberMailService.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH:mm");

    private final boolean enabled;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String fromName;
    private final String fromAddress;
    private final String frontendUrl;

    public MemberMailService(
            @Value("${yunti.mail.enabled:}") String enabledRaw,
            @Value("${yunti.mail.host:}") String host,
            @Value("${yunti.mail.port:465}") int port,
            @Value("${yunti.mail.username:}") String username,
            @Value("${yunti.mail.password:}") String password,
            @Value("${yunti.mail.from-name:云梯智能客服}") String fromName,
            @Value("${yunti.mail.from-address:}") String fromAddress,
            @Value("${yunti.member-invite.frontend-url:http://localhost:5173}") String frontendUrl
    ) {
        this.host = host == null ? "" : host.trim();
        this.port = port;
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        boolean explicitConfigured = enabledRaw != null && !enabledRaw.isBlank();
        boolean smtpConfigured = !this.host.isBlank() && !this.username.isBlank() && !this.password.isBlank();
        // 未显式配置开关时：只要提供了 SMTP 账号就自动启用
        this.enabled = explicitConfigured ? Boolean.parseBoolean(enabledRaw) : smtpConfigured;
        this.fromName = fromName == null || fromName.isBlank() ? "云梯智能客服" : fromName;
        this.fromAddress = fromAddress == null || fromAddress.isBlank() ? this.username : fromAddress.trim();
        this.frontendUrl = frontendUrl == null || frontendUrl.isBlank()
                ? "http://localhost:5173"
                : frontendUrl.replaceAll("/+$", "");
    }

    /**
     * 发送成员邀请邮件；未启用邮件服务时返回 false（邀请链接仍可复制使用）。
     */
    public boolean sendInvitationEmail(
            String to,
            String inviteCode,
            String inviterName,
            String roleName,
            LocalDateTime expireTime
    ) {
        if (!enabled) {
            log.info("邮件服务未启用，跳过发送邀请邮件 to={}, inviteCode={}；如需发信请配置 MAIL_HOST/MAIL_USERNAME/MAIL_PASSWORD 后重启 user-service", to, inviteCode);
            return false;
        }
        if (to == null || to.isBlank()) {
            throw new BizException(40001, "缺少受邀邮箱，无法发送邀请邮件");
        }
        if (host.isBlank() || username.isBlank() || fromAddress.isBlank()) {
            throw new BizException(50002, "邮件服务未配置（MAIL_HOST/MAIL_USERNAME/MAIL_PASSWORD），请在启动前配置后重试");
        }

        String inviteUrl = frontendUrl + "/invite/accept/" + inviteCode;
        String subject = "【云梯智能客服】" + inviterName + " 邀请你加入客服团队";
        String html = buildInvitationHtml(inviterName, roleName, inviteUrl, inviteCode, expireTime);

        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        boolean sslEnabled = port == 465;
        props.put("mail.smtp.ssl.enable", String.valueOf(sslEnabled));
        props.put("mail.smtp.starttls.enable", String.valueOf(!sslEnabled));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        try {
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(fromAddress, fromName, "UTF-8"));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
            message.setSubject(subject, "UTF-8");
            message.setContent(html, "text/html;charset=UTF-8");
            Transport.send(message);
            log.info("成员邀请邮件发送成功 to={}, inviteCode={}", to, inviteCode);
            return true;
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("成员邀请邮件发送失败 to={}, inviteCode={}", to, inviteCode, e);
            throw new BizException(50002, "邀请邮件发送失败，请检查 SMTP 配置后重试");
        }
    }

    private String buildInvitationHtml(
            String inviterName,
            String roleName,
            String inviteUrl,
            String inviteCode,
            LocalDateTime expireTime
    ) {
        String expireText = expireTime == null ? "7 天后" : TIME_FORMAT.format(expireTime);
        return """
                <div style="margin:0;padding:24px 12px;background:#f5f8ff;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Microsoft YaHei',Arial,sans-serif;">
                  <div style="max-width:600px;margin:0 auto;background:#ffffff;border:1px solid #e3ebfa;border-radius:16px;overflow:hidden;">
                    <div style="background:linear-gradient(135deg,#1d4ed8,#3b82f6);padding:26px 30px;">
                      <div style="display:flex;align-items:center;gap:10px;">
                        <div style="width:34px;height:34px;background:#ffffff;border-radius:10px;display:flex;align-items:center;justify-content:center;color:#1d4ed8;font-weight:800;font-size:18px;">云</div>
                        <div>
                          <div style="color:#ffffff;font-size:17px;font-weight:700;">云梯智能客服</div>
                          <div style="color:#dbeafe;font-size:12px;margin-top:2px;">MULTI-CHANNEL CUSTOMER SERVICE</div>
                        </div>
                      </div>
                    </div>
                    <div style="padding:30px 32px;">
                      <div style="font-size:21px;font-weight:800;color:#0f172a;">客服团队邀请</div>
                      <p style="font-size:14px;color:#475569;line-height:1.9;margin-top:14px;">
                        你好：<br/>
                        <strong>%s</strong> 邀请你加入云梯智能客服团队，受邀角色为
                        <span style="display:inline-block;background:#eff6ff;color:#1d4ed8;border:1px solid #bfdbfe;border-radius:6px;padding:2px 10px;font-weight:600;">%s</span>。
                      </p>
                      <div style="text-align:center;margin:28px 0;">
                        <a href="%s"
                           style="display:inline-block;background:linear-gradient(135deg,#1d4ed8,#3b82f6);color:#ffffff;text-decoration:none;font-size:15px;font-weight:600;padding:13px 46px;border-radius:10px;box-shadow:0 8px 20px rgba(29,78,216,.25);">
                          接受邀请并加入
                        </a>
                      </div>
                      <div style="background:#f8fafc;border:1px solid #e6e8ef;border-radius:12px;padding:12px 16px;">
                        <div style="font-size:12px;color:#94a3b8;">如果按钮无法点击，请复制以下链接到浏览器打开：</div>
                        <div style="font-size:12px;color:#1d4ed8;word-break:break-all;margin-top:6px;font-family:Menlo,Consolas,monospace;">%s</div>
                      </div>
                      <p style="font-size:12px;color:#94a3b8;line-height:1.8;margin-top:20px;">
                        邀请有效期至 %s，逾期链接将自动失效。<br/>
                        如果你没有申请加入该企业，请忽略此邮件，无需任何操作。
                      </p>
                      <div style="border-top:1px dashed #e6e8ef;margin-top:20px;padding-top:14px;font-size:12px;color:#cbd5e1;text-align:center;">
                        本邮件由云梯智能客服自动发送 · 邀请码 %s
                      </div>
                    </div>
                  </div>
                </div>
                """.formatted(
                safe(inviterName),
                safe(roleName == null ? "客服专员" : roleName),
                inviteUrl,
                inviteUrl,
                expireText,
                inviteCode
        );
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
