package cn.net.susan.customer.storage;

import cn.net.susan.customer.config.FileStorageProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * RustFS / MinIO / S3 兼容对象存储。
 */
@Component
@ConditionalOnProperty(name = "yunti.file.storage.type", havingValue = "rustfs")
public class RustFsObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(RustFsObjectStorage.class);
    private static final Set<String> BUCKET_EXISTS_CODES = Set.of(
            "BucketAlreadyExists", "BucketAlreadyOwnedByYou"
    );

    private final S3Client s3Client;
    private final String bucket;

    public RustFsObjectStorage(FileStorageProperties properties) {
        FileStorageProperties.Rustfs config = properties.getRustfs();
        validate(config);
        String protocol = config.isSecure() ? "https" : "http";
        URI endpoint = URI.create(protocol + "://" + config.getEndpoint() + ":" + config.getPort());
        this.bucket = config.getBucket();
        this.s3Client = S3Client.builder()
                .endpointOverride(endpoint)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())))
                .region(Region.US_EAST_1)
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .checksumValidationEnabled(false)
                        .build())
                .overrideConfiguration(builder -> builder
                        .apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(30)))
                .build();
    }

    RustFsObjectStorage(S3Client s3Client, String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @PostConstruct
    public void init() {
        try {
            s3Client.createBucket(builder -> builder.bucket(bucket));
        } catch (S3Exception e) {
            String errorCode = e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
            if (BUCKET_EXISTS_CODES.contains(errorCode)) {
                log.info("RustFS bucket 已存在: {}", bucket);
                return;
            }
            throw e;
        }
    }

    @Override
    public void upload(String key, InputStream input, long size, String mimeType) {
        s3Client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(mimeType)
                        .build(),
                RequestBody.fromInputStream(input, size)
        );
    }

    @Override
    public Resource download(String key) {
        ResponseInputStream<?> stream = s3Client.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build());
        return new InputStreamResource(stream);
    }

    @Override
    public void delete(String key) {
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    private void validate(FileStorageProperties.Rustfs config) {
        if (!StringUtils.hasText(config.getAccessKey()) || !StringUtils.hasText(config.getSecretKey())) {
            throw new IllegalStateException("启用 RustFS 时必须配置 access-key 和 secret-key");
        }
        if ("minioadmin".equals(config.getAccessKey()) || "minioadmin".equals(config.getSecretKey())) {
            throw new IllegalStateException("RustFS 不允许使用公开默认凭据 minioadmin");
        }
        if (!StringUtils.hasText(config.getEndpoint()) || !StringUtils.hasText(config.getBucket())) {
            throw new IllegalStateException("RustFS endpoint 和 bucket 不能为空");
        }
    }
}
