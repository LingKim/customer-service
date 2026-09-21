package cn.net.susan.customer.storage;

import cn.net.susan.customer.config.FileStorageProperties;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RustFsObjectStorageTest {

    @Test
    void requiresExplicitCredentialsWhenRustFsIsEnabled() {
        FileStorageProperties properties = new FileStorageProperties();

        assertThrows(IllegalStateException.class, () -> new RustFsObjectStorage(properties));
    }

    @Test
    void rejectsPublicDefaultCredentials() {
        FileStorageProperties properties = new FileStorageProperties();
        properties.getRustfs().setAccessKey("minioadmin");
        properties.getRustfs().setSecretKey("minioadmin");

        assertThrows(IllegalStateException.class, () -> new RustFsObjectStorage(properties));
    }

    @Test
    void ignoresOnlyExplicitBucketAlreadyExistsResponse() {
        RustFsObjectStorage storage = new RustFsObjectStorage(
                failingClient(s3Exception("BucketAlreadyOwnedByYou", 409)), "yunti");

        assertDoesNotThrow(storage::init);
    }

    @Test
    void propagatesBucketCreationFailure() {
        RustFsObjectStorage storage = new RustFsObjectStorage(
                failingClient(s3Exception("AccessDenied", 403)), "yunti");

        assertThrows(S3Exception.class, storage::init);
    }

    private S3Client failingClient(S3Exception failure) {
        return (S3Client) Proxy.newProxyInstance(
                S3Client.class.getClassLoader(),
                new Class<?>[]{S3Client.class},
                (proxy, method, args) -> {
                    if ("createBucket".equals(method.getName())) {
                        throw failure;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                }
        );
    }

    private S3Exception s3Exception(String code, int statusCode) {
        return (S3Exception) S3Exception.builder()
                .statusCode(statusCode)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build())
                .build();
    }
}
