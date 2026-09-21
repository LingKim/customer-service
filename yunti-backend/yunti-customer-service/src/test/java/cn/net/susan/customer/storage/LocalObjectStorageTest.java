package cn.net.susan.customer.storage;

import cn.net.susan.customer.config.FileStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalObjectStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsObjectKeyThatEscapesStorageRoot() throws Exception {
        LocalObjectStorage storage = storage();

        assertThrows(IllegalArgumentException.class, () -> storage.upload(
                "../outside.pdf",
                new ByteArrayInputStream("unsafe".getBytes(StandardCharsets.UTF_8)),
                6,
                "application/pdf"
        ));
    }

    @Test
    void storesAndStreamsObjectInsideStorageRoot() throws Exception {
        LocalObjectStorage storage = storage();

        storage.upload(
                "enterprise/license/owner/42/license.pdf",
                new ByteArrayInputStream("license".getBytes(StandardCharsets.UTF_8)),
                7,
                "application/pdf"
        );

        assertEquals(
                "license",
                new String(storage.download("enterprise/license/owner/42/license.pdf")
                        .getInputStream().readAllBytes(), StandardCharsets.UTF_8)
        );
        assertEquals("license", Files.readString(
                tempDir.resolve("enterprise/license/owner/42/license.pdf"), StandardCharsets.UTF_8));
    }

    private LocalObjectStorage storage() throws Exception {
        FileStorageProperties properties = new FileStorageProperties();
        properties.setLocalDir(tempDir.toString());
        LocalObjectStorage storage = new LocalObjectStorage(properties);
        storage.init();
        return storage;
    }
}
