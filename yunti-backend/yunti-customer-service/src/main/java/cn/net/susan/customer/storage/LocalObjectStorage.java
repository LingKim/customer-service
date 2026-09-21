package cn.net.susan.customer.storage;

import cn.net.susan.customer.config.FileStorageProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地磁盘存储，仅用于开发与联调。
 */
@Component
@ConditionalOnProperty(name = "yunti.file.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalObjectStorage implements ObjectStorage {

    private final Path root;

    public LocalObjectStorage(FileStorageProperties properties) {
        this.root = Paths.get(properties.getLocalDir()).toAbsolutePath().normalize();
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(root);
    }

    @Override
    public void upload(String key, InputStream input, long size, String mimeType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("本地文件保存失败: " + key, e);
        }
    }

    @Override
    public Resource download(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            throw new IllegalStateException("本地文件不存在: " + key);
        }
        return new FileSystemResource(target);
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new IllegalStateException("本地文件删除失败: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(resolve(key));
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("对象键不能为空");
        }
        Path relative = Paths.get(key);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("对象键不能是绝对路径");
        }
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("对象键不能越过存储根目录");
        }
        return resolved;
    }
}
