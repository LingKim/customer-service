package cn.net.susan.customer.service;

import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.entity.FileMeta;
import cn.net.susan.customer.mapper.FileMetaMapper;
import cn.net.susan.customer.mapper.SessionMapper;
import cn.net.susan.customer.entity.Session;
import cn.net.susan.customer.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 会话图片附件：客户在聊天窗口里发的图片，存 RustFS 并登记 file_meta。
 *
 * <p>这里最值钱的不是"上传"，而是**送进模型之前的那步转码**（和第 23 篇参考的招聘系统同一套做法）：</p>
 *
 * <ol>
 *   <li>手机直出照片动辄 4~8 MB，直接 base64 塞进模型请求，body 会涨到十几 MB——又慢又容易被网关截断；</li>
 *   <li>所以先缩放（最大边 1280）、再按质量 0.8 转 JPEG，正常能压到 100~300 KB；</li>
 *   <li>模型只关心"字认不认得出来、图里是什么"，这个分辨率完全够用。</li>
 * </ol>
 *
 * <p>另外注意：**只有图片才走视觉识别**，PDF/Word 这类属于文档，该走知识库那条链路（第 20 篇）。</p>
 */
@Service
public class SessionAttachmentService {

    private static final Logger log = LoggerFactory.getLogger(SessionAttachmentService.class);

    /** 聊天图片的对象键前缀 */
    private static final String PREFIX = "chat";
    /** file_meta.biz_type：5-聊天图片（约束里登记的取值，别随手写别的数字） */
    private static final int BIZ_TYPE_CHAT_IMAGE = 5;
    /** 送进模型前缩放到的最大边长（保持比例） */
    private static final int VISION_MAX_EDGE = 1280;
    /** JPEG 质量：0.8 是"肉眼几乎无损、体积砍到 1/10"的常用档 */
    private static final float JPEG_QUALITY = 0.8f;

    private final ObjectStorage objectStorage;
    private final FileMetaMapper fileMetaMapper;
    private final SessionMapper sessionMapper;
    private final SnowflakeIdGenerator idGenerator;
    private final int maxImageMb;
    private final String signingSecret;

    public SessionAttachmentService(
            ObjectStorage objectStorage,
            FileMetaMapper fileMetaMapper,
            SessionMapper sessionMapper,
            SnowflakeIdGenerator idGenerator,
            @Value("${yunti.chat.image-max-mb:8}") int maxImageMb,
            @Value("${yunti.auth.jwt-secret}") String signingSecret
    ) {
        this.objectStorage = objectStorage;
        this.fileMetaMapper = fileMetaMapper;
        this.sessionMapper = sessionMapper;
        this.idGenerator = idGenerator;
        this.maxImageMb = maxImageMb <= 0 ? 8 : maxImageMb;
        this.signingSecret = signingSecret;
    }

    /**
     * 图片预处理线程池：一条消息最多 3 张（AI 侧 vision_max_images 也是 3），所以 3 个线程够用。
     *
     * <p>用固定池 + 守护线程：线程只干"下载 + 压缩"这种短活儿，业务一停就跟着结束，
     * 不会吊住 JVM；也不去占公共 ForkJoinPool（那里面是计算型任务，塞阻塞 IO 会拖累别人）。</p>
     */
    private final ExecutorService visionPool = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "chat-image-prep");
        thread.setDaemon(true);
        return thread;
    });

    /** 应用关闭时收掉线程池，别留悬挂线程 */
    @PreDestroy
    public void shutdownVisionPool() {
        visionPool.shutdown();
    }

    /**
     * 客户上传一张聊天图片。
     *
     * @param tenantCode 租户（由渠道密钥解析出来，不信任前端）
     * @param sessionNo  会话号（只用于拼对象键，便于按会话归档）
     * @return 上传结果（文件 ID、访问地址、文件名、大小）
     */
    @Transactional
    public UploadResult upload(String tenantCode, String sessionNo, MultipartFile file) {
        Session session = sessionMapper.selectOne(Wrappers.<Session>lambdaQuery()
                .eq(Session::getTenantCode, tenantCode)
                .eq(Session::getSessionNo, sessionNo)
                .eq(Session::getDeleted, false).last("LIMIT 1"));
        if (session == null) {
            throw new BizException(40401, "会话不存在");
        }
        if (file == null || file.isEmpty()) {
            throw new BizException(40001, "请选择要发送的图片");
        }
        if (file.getSize() > (long) maxImageMb * 1024 * 1024) {
            throw new BizException(40001, "图片不能超过 " + maxImageMb + "MB");
        }
        String original = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
        String ext = extension(original);
        if (ext == null || !ext.matches("(?i)png|jpe?g|webp|gif|bmp")) {
            throw new BizException(40001, "聊天只支持图片（PNG / JPG / WEBP / GIF / BMP）；"
                    + "PDF、Word 这类文档请到「企业知识库」上传");
        }

        long fileId = idGenerator.nextId();
        String key = String.format("%s/%s/%s/%d_%s", PREFIX, tenantCode, safeSegment(sessionNo), fileId,
                safeSegment(original));
        String mimeType = mimeOf(ext);
        try (InputStream in = file.getInputStream()) {
            objectStorage.upload(key, in, file.getSize(), mimeType);
        } catch (Exception e) {
            log.error("聊天图片上传对象存储失败 tenant={} session={} error={}", tenantCode, sessionNo, e.getMessage());
            throw new BizException(50001, "图片上传失败：" + e.getMessage());
        }

        LocalDateTime now = LocalDateTime.now();
        FileMeta meta = FileMeta.builder()
                .id(fileId)
                .tenantCode(tenantCode)
                .fileNo("IMG" + fileId)
                .fileName(original)
                .objectKey(key)
                .fileSize(file.getSize())
                .mimeType(mimeType)
                // bizType：5-聊天图片（1-头像、2-附件、3-导出、4-发票见 file_meta 的约束与注释）
                .bizType(BIZ_TYPE_CHAT_IMAGE)
                .createTime(now)
                .updateTime(now)
                .creator("VISITOR")
                .deleted(false)
                .build();
        fileMetaMapper.insert(meta);
        log.info("聊天图片已上传 tenant={} session={} fileId={} name={} bytes={}",
                tenantCode, sessionNo, fileId, original, file.getSize());
        return new UploadResult(String.valueOf(fileId), accessUrl(fileId), original, file.getSize(), mimeType);
    }

    /**
     * 读取图片字节（给前端渲染用）。
     *
     * <p>聊天图片同时给访客和坐席看；对外读取由控制器校验带时效的签名地址。</p>
     */
    public FileContent load(long fileId) {
        return load(fileId, null);
    }

    public FileContent load(long fileId, String tenantCode) {
        FileMeta meta = fileMetaMapper.selectOne(Wrappers.<FileMeta>lambdaQuery()
                .eq(FileMeta::getId, fileId)
                .eq(tenantCode != null, FileMeta::getTenantCode, tenantCode)
                .eq(FileMeta::getBizType, BIZ_TYPE_CHAT_IMAGE)
                .eq(FileMeta::getDeleted, false)
                .last("LIMIT 1"));
        if (meta == null) {
            throw new BizException(40401, "图片不存在或已删除");
        }
        try (InputStream in = objectStorage.download(meta.getObjectKey()).getInputStream()) {
            return new FileContent(meta.getFileName(), meta.getMimeType(), in.readAllBytes());
        } catch (Exception e) {
            throw new BizException(50001, "读取图片失败：" + e.getMessage());
        }
    }

    /** 图片消息只能引用当前租户、当前会话上传的附件。 */
    public void verifySessionFile(String tenantCode, String sessionNo, long fileId) {
        FileMeta meta = fileMetaMapper.selectOne(Wrappers.<FileMeta>lambdaQuery()
                .eq(FileMeta::getId, fileId)
                .eq(FileMeta::getTenantCode, tenantCode)
                .eq(FileMeta::getBizType, BIZ_TYPE_CHAT_IMAGE)
                .eq(FileMeta::getDeleted, false).last("LIMIT 1"));
        String expectedPrefix = PREFIX + "/" + tenantCode + "/" + safeSegment(sessionNo) + "/";
        if (meta == null || meta.getObjectKey() == null || !meta.getObjectKey().startsWith(expectedPrefix)) {
            throw new BizException(40301, "图片不属于当前会话");
        }
    }

    /**
     * 把图片转成"可以直接喂给视觉模型"的 data URL 列表。
     *
     * <p>这一步就是参考企业智能招聘系统的做法：**先把图压小、再 base64**，
     * 不然手机照片直传会让模型请求体膨胀到十几 MB。</p>
     *
     * <p><b>多张图并发处理，但顺序不变</b>：每张图都要"对象存储下载 → 解码 → 缩放 → 转 JPEG"，
     * 张与张之间毫无依赖，一张一张串着来就是白白排队（3 张图 ≈ 3 倍等待）。
     * 这里用 {@link ExecutorService#invokeAll} 并发跑——它**按提交顺序返回 Future**，
     * 所以图片清单的顺序仍然是客户选图的顺序，模型看到的第 1 张还是第 1 张。</p>
    */
    public List<String> toVisionImages(String tenantCode, List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            return List.of();
        }
        // 单张不用开线程：一张图的场景最频繁，别为它付线程池的调度开销
        if (fileIds.size() == 1) {
            return List.of(toDataUrl(tenantCode, fileIds.get(0)));
        }
        List<Callable<String>> tasks = new ArrayList<>(fileIds.size());
        for (Long fileId : fileIds) {
            tasks.add(() -> toDataUrl(tenantCode, fileId));
        }
        try {
            List<Future<String>> futures = visionPool.invokeAll(tasks);
            List<String> images = new ArrayList<>(futures.size());
            for (Future<String> future : futures) {
                // 顺序就是客户选图的顺序：第 1 张永远排第 1，模型看到的顺序不乱
                images.add(future.get());
            }
            return images;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("图片预处理被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("图片预处理失败：" + e.getCause().getMessage(), e);
        }
    }

    private String toDataUrl(String tenantCode, long fileId) {
        FileContent content = load(fileId, tenantCode);
        byte[] payload = compress(content.payload(), fileId);
        String mime = payload == content.payload() ? content.mimeType() : "image/jpeg";
        return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(payload);
    }

    /** 缩放 + 转 JPEG；万一解不出来（少见格式）就原样返回，让模型自己去试。 */
    private byte[] compress(byte[] raw, long fileId) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
            if (image == null) {
                log.warn("图片无法解码，按原字节发送 fileId={} bytes={}", fileId, raw.length);
                return raw;
            }
            BufferedImage scaled = scaleDown(image, VISION_MAX_EDGE);
            byte[] jpeg = toJpeg(scaled);
            if (jpeg.length >= raw.length) {
                return raw;
            }
            log.info("图片已压缩 fileId={} {}KB → {}KB（{}×{}）",
                    fileId, raw.length / 1024, jpeg.length / 1024, scaled.getWidth(), scaled.getHeight());
            return jpeg;
        } catch (Exception e) {
            log.warn("图片压缩失败，按原字节发送 fileId={} error={}", fileId, e.getMessage());
            return raw;
        }
    }

    /** 按最大边等比缩放（只缩不放） */
    private BufferedImage scaleDown(BufferedImage source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longer = Math.max(width, height);
        if (longer <= maxEdge) {
            return source;
        }
        double ratio = (double) maxEdge / longer;
        int targetWidth = Math.max(1, (int) Math.round(width * ratio));
        int targetHeight = Math.max(1, (int) Math.round(height * ratio));
        BufferedImage target = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        graphics.dispose();
        return target;
    }

    /** 按指定质量写 JPEG（ImageIO 默认质量偏大，这里显式设 0.8） */
    private byte[] toJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("当前 JVM 没有 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** 带有效期的图片访问地址，避免仅凭顺序文件 ID 读取其他租户图片。 */
    private String accessUrl(long fileId) {
        long expires = java.time.Instant.now().plusSeconds(30L * 24 * 3600).getEpochSecond();
        return "/api/customer/sessions/attachments/" + fileId
                + "?expires=" + expires + "&signature=" + signature(fileId, expires);
    }

    public void verifySignature(long fileId, long expires, String provided) {
        if (expires < java.time.Instant.now().getEpochSecond() || provided == null
                || !java.security.MessageDigest.isEqual(
                        signature(fileId, expires).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        provided.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new BizException(40301, "图片访问链接无效或已过期");
        }
    }

    private String signature(long fileId, long expires) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    signingSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((fileId + ":" + expires)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("图片签名失败", e);
        }
    }

    private String extension(String fileName) {
        int index = fileName == null ? -1 : fileName.lastIndexOf('.');
        return index < 0 ? null : fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String mimeOf(String ext) {
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            default -> "application/octet-stream";
        };
    }

    /** 对象键里只允许安全字符，避免把路径分隔符带进去 */
    private String safeSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** 上传结果 */
    public record UploadResult(String fileId, String url, String name, long size, String mimeType) {
    }

    /** 文件内容 */
    public record FileContent(String fileName, String mimeType, byte[] payload) {
    }
}
