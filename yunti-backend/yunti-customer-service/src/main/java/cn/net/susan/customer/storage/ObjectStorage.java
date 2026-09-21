package cn.net.susan.customer.storage;

import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * 对象存储抽象，业务层不感知本地磁盘或 S3 兼容存储。
 */
public interface ObjectStorage {

    void upload(String key, InputStream input, long size, String mimeType);

    Resource download(String key);

    void delete(String key);

    boolean exists(String key);
}
