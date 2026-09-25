package cn.net.susan.customer.service;

import cn.net.susan.common.api.ResultCode;
import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.config.FileStorageProperties;
import cn.net.susan.customer.entity.FileMeta;
import cn.net.susan.customer.mapper.FileMetaMapper;
import cn.net.susan.customer.storage.ObjectStorage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

/**
 * 企业营业执照附件服务。
 */
@Service
public class EnterpriseLicenseFileService {

    private static final Logger log = LoggerFactory.getLogger(EnterpriseLicenseFileService.class);
    private static final String LICENSE_PREFIX = "enterprise/license";
    private static final String PRE_TENANT_CODE = "PLATFORM";
    private static final int ENTERPRISE_USER_TYPE = 2;
    private static final int LICENSE_BIZ_TYPE = 2;
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "pdf", "application/pdf"
    );

    private final ObjectStorage objectStorage;
    private final FileMetaMapper fileMetaMapper;
    private final FileStorageProperties properties;
    private final SnowflakeIdGenerator idGenerator;

    public EnterpriseLicenseFileService(
            ObjectStorage objectStorage,
            FileMetaMapper fileMetaMapper,
            FileStorageProperties properties,
            SnowflakeIdGenerator idGenerator
    ) {
        this.objectStorage = objectStorage;
        this.fileMetaMapper = fileMetaMapper;
        this.properties = properties;
        this.idGenerator = idGenerator;
    }

    @Transactional
    public UploadResult uploadLicense(LoginUser user, MultipartFile file) {
        requireEnterpriseUser(user);
        validateFile(file);

        String originalName = cleanFileName(file.getOriginalFilename());
        String extension = extension(originalName);
        String mimeType = file.getContentType().trim().toLowerCase(Locale.ROOT);
        long fileId = idGenerator.nextId();
        String tenantCode = hasActiveTenant(user.tenantCode()) ? user.tenantCode() : PRE_TENANT_CODE;
        String isolationPath = hasActiveTenant(user.tenantCode())
                ? "tenant/" + safePathSegment(user.tenantCode())
                : "owner/" + user.userId();
        String objectKey = LICENSE_PREFIX + "/" + isolationPath + "/" + fileId + "_" + safeFileName(originalName);

        try (InputStream input = file.getInputStream()) {
            objectStorage.upload(objectKey, input, file.getSize(), mimeType);
        } catch (IOException | RuntimeException e) {
            throw new BizException(50001, "营业执照上传失败，请稍后重试");
        }

        LocalDateTime now = LocalDateTime.now();
        try {
            int inserted = fileMetaMapper.insert(FileMeta.builder()
                    .id(fileId)
                    .tenantCode(tenantCode)
                    .fileNo("F" + fileId)
                    .fileName(originalName)
                    .objectKey(objectKey)
                    .fileSize(file.getSize())
                    .mimeType(mimeType)
                    .bizType(LICENSE_BIZ_TYPE)
                    .createTime(now)
                    .creator(String.valueOf(user.userId()))
                    .updateTime(now)
                    .editor(String.valueOf(user.userId()))
                    .deleted(false)
                    .build());
            if (inserted != 1) {
                throw new IllegalStateException("file_meta 写入行数异常: " + inserted);
            }
        } catch (RuntimeException databaseError) {
            compensateDelete(objectKey, databaseError);
            throw new BizException(50001, "文件元数据保存失败，请稍后重试");
        }
        return new UploadResult(String.valueOf(fileId), originalName, file.getSize(), mimeType);
    }

    @Transactional(readOnly = true)
    public FileContent loadLicense(LoginUser user, long fileId) {
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        FileMeta meta = findLicense(fileId);
        if (meta == null) {
            throw new BizException(40401, "营业执照文件不存在");
        }
        boolean creatorOwned = String.valueOf(user.userId()).equals(meta.getCreator());
        boolean tenantOwned = hasActiveTenant(user.tenantCode())
                && user.tenantCode().equals(meta.getTenantCode());
        boolean platformReviewer = user.userType() == 1;
        if (!creatorOwned && !tenantOwned && !platformReviewer) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
        Resource resource;
        try {
            resource = objectStorage.download(meta.getObjectKey());
        } catch (RuntimeException e) {
            throw new BizException(50001, "营业执照文件读取失败");
        }
        return new FileContent(meta.getFileName(), meta.getMimeType(), meta.getFileSize(), resource);
    }

    @Transactional(readOnly = true)
    public boolean isEnterpriseLicenseOwnedBy(LoginUser user, long fileId) {
        requireEnterpriseUser(user);
        FileMeta meta = fileMetaMapper.selectOne(
                Wrappers.<FileMeta>lambdaQuery()
                        .eq(FileMeta::getId, fileId)
                        .eq(FileMeta::getBizType, LICENSE_BIZ_TYPE)
                        .eq(FileMeta::getCreator, String.valueOf(user.userId()))
                        .eq(FileMeta::getDeleted, false)
                        .last("LIMIT 1")
        );
        return meta != null;
    }

    private FileMeta findLicense(long fileId) {
        return fileMetaMapper.selectOne(
                Wrappers.<FileMeta>lambdaQuery()
                        .eq(FileMeta::getId, fileId)
                        .eq(FileMeta::getBizType, LICENSE_BIZ_TYPE)
                        .eq(FileMeta::getDeleted, false)
                        .last("LIMIT 1")
        );
    }

    private void requireEnterpriseUser(LoginUser user) {
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        if (user.userType() != ENTERPRISE_USER_TYPE) {
            throw new BizException(ResultCode.FORBIDDEN);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(40001, "请选择营业执照文件");
        }
        if (file.getSize() > properties.getMaxSize()) {
            throw new BizException(40001, "营业执照文件超过大小限制");
        }
        String fileName = cleanFileName(file.getOriginalFilename());
        String extension = extension(fileName);
        String mimeType = file.getContentType() == null
                ? ""
                : file.getContentType().trim().toLowerCase(Locale.ROOT);
        if (extension == null || !mimeType.equals(ALLOWED_TYPES.get(extension))) {
            throw new BizException(40001, "仅支持 MIME 与扩展名一致的 JPG / PNG / PDF 营业执照");
        }
    }

    private String cleanFileName(String originalName) {
        if (!StringUtils.hasText(originalName)) {
            throw new BizException(40001, "文件名不能为空");
        }
        String cleaned = StringUtils.cleanPath(originalName.trim()).replace('\\', '/');
        String fileName = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        if (!StringUtils.hasText(fileName) || ".".equals(fileName) || "..".equals(fileName)) {
            throw new BizException(40001, "文件名不合法");
        }
        return fileName;
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String safeFileName(String fileName) {
        return fileName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String safePathSegment(String segment) {
        if (!segment.matches("[A-Za-z0-9_-]+")) {
            throw new BizException(40001, "租户编码不合法");
        }
        return segment;
    }

    private boolean hasActiveTenant(String tenantCode) {
        return StringUtils.hasText(tenantCode) && !PRE_TENANT_CODE.equals(tenantCode);
    }

    private void compensateDelete(String objectKey, RuntimeException databaseError) {
        try {
            objectStorage.delete(objectKey);
        } catch (RuntimeException deleteError) {
            databaseError.addSuppressed(deleteError);
            log.error("文件元数据写入失败后补偿删除对象失败 objectKey={}", objectKey, deleteError);
        }
    }

    public record UploadResult(String fileId, String fileName, long fileSize, String mimeType) {
    }

    public record FileContent(String fileName, String mimeType, long fileSize, Resource resource) {
    }
}
