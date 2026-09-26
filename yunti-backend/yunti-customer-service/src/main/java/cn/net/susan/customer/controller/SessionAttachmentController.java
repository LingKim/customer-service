package cn.net.susan.customer.controller;

import cn.net.susan.common.api.ApiResponse;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.customer.entity.Channel;
import cn.net.susan.customer.entity.ChannelKey;
import cn.net.susan.customer.mapper.ChannelKeyMapper;
import cn.net.susan.customer.mapper.ChannelMapper;
import cn.net.susan.customer.service.SessionAttachmentService;
import cn.net.susan.customer.security.VisitorTokenService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * 聊天图片附件：客户在访客窗口发图。
 *
 * <p>两个接口，分工不同：</p>
 *
 * <ul>
 *   <li><b>上传</b>：访客没有登录态，所以用**渠道密钥**（appKey）证明"这条会话来自我的渠道"。
 *       租户由密钥解析出来，不信前端传的任何租户参数——这是多租户系统的底线；</li>
 *   <li><b>读取</b>：验证带有效期的签名 URL，访客和坐席均可显示图片。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/customer/sessions/attachments")
public class SessionAttachmentController {

    private final SessionAttachmentService attachmentService;
    private final ChannelKeyMapper channelKeyMapper;
    private final ChannelMapper channelMapper;
    private final VisitorTokenService visitorTokenService;

    public SessionAttachmentController(
            SessionAttachmentService attachmentService,
            ChannelKeyMapper channelKeyMapper,
            ChannelMapper channelMapper,
            VisitorTokenService visitorTokenService
    ) {
        this.attachmentService = attachmentService;
        this.channelKeyMapper = channelKeyMapper;
        this.channelMapper = channelMapper;
        this.visitorTokenService = visitorTokenService;
    }

    /**
     * 访客上传一张聊天图片（聊天窗口里点「发送图片」）。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SessionAttachmentService.UploadResult> upload(
            @RequestParam("appKey") String appKey,
            @RequestParam("sessionNo") String sessionNo,
            @RequestHeader("X-Visitor-Token") String visitorToken,
            @RequestPart("file") MultipartFile file
    ) {
        String tenant = tenantOf(appKey);
        VisitorTokenService.VisitorPrincipal visitor = visitorTokenService.parseToken(visitorToken);
        if (!tenant.equals(visitor.tenantCode()) || !sessionNo.equals(visitor.sessionNo())) {
            throw new BizException(40301, "访客会话身份不匹配");
        }
        return ApiResponse.ok(attachmentService.upload(tenant, sessionNo, file));
    }

    /**
     * 按文件 ID 读取图片（消息气泡里直接当 img src 用）。
     */
    @GetMapping("/{fileId}")
    public ResponseEntity<byte[]> load(@PathVariable long fileId,
                                       @RequestParam long expires,
                                       @RequestParam String signature) throws IOException {
        attachmentService.verifySignature(fileId, expires, signature);
        SessionAttachmentService.FileContent content = attachmentService.load(fileId);
        String encoded = URLEncoder.encode(content.fileName() == null ? "image" : content.fileName(),
                StandardCharsets.UTF_8).replace("+", "%20");
        MediaType mediaType = content.mimeType() == null
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(content.mimeType());
        return ResponseEntity.ok()
                // inline：让浏览器直接显示，而不是弹下载
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                // 图片按 fileId 不可变，缓存一天，消息列表反复渲染时不再回源
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .contentType(mediaType)
                .body(content.payload());
    }

    /** 渠道密钥 → 租户：和访客开会话用的是同一套校验 */
    private String tenantOf(String appKey) {
        ChannelKey channelKey = channelKeyMapper.selectOne(Wrappers.<ChannelKey>lambdaQuery()
                .eq(ChannelKey::getAppKey, appKey)
                .eq(ChannelKey::getStatus, 1)
                .eq(ChannelKey::getDeleted, false)
                .last("LIMIT 1"));
        if (channelKey == null) {
            throw new BizException(40401, "渠道密钥无效或已停用");
        }
        if (channelKey.getExpireTime() != null && channelKey.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BizException(40301, "渠道密钥已过期，请联系企业管理员重新生成");
        }
        Channel channel = channelMapper.selectById(channelKey.getChannelId());
        if (channel == null || Boolean.TRUE.equals(channel.getDeleted())) {
            throw new BizException(40401, "渠道不存在");
        }
        if (!Integer.valueOf(1).equals(channel.getStatus())) {
            throw new BizException(40301, "渠道已停用，暂时无法发送图片");
        }
        return channelKey.getTenantCode();
    }
}
