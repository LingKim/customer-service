package cn.net.susan.customer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置。RustFS 凭据不提供公开默认值，启用时必须显式配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "yunti.file.storage")
public class FileStorageProperties {

    private String type = "local";
    private String localDir = "/tmp/yunti-files";
    private long maxSize = 10L * 1024 * 1024;
    private Rustfs rustfs = new Rustfs();

    @Data
    public static class Rustfs {
        private String endpoint = "127.0.0.1";
        private int port = 9000;
        private String accessKey;
        private String secretKey;
        private String bucket = "yunti";
        private boolean secure;
    }
}
