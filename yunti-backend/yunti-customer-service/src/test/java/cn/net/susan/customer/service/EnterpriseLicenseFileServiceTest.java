package cn.net.susan.customer.service;

import cn.net.susan.common.auth.LoginUser;
import cn.net.susan.common.exception.BizException;
import cn.net.susan.common.id.SnowflakeIdGenerator;
import cn.net.susan.customer.config.FileStorageProperties;
import cn.net.susan.customer.entity.FileMeta;
import cn.net.susan.customer.mapper.FileMetaMapper;
import cn.net.susan.customer.storage.ObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnterpriseLicenseFileServiceTest {

    private RecordingStorage objectStorage;
    private MapperStub mapperStub;
    private EnterpriseLicenseFileService service;

    @BeforeEach
    void setUp() {
        objectStorage = new RecordingStorage();
        mapperStub = new MapperStub();
        FileStorageProperties properties = new FileStorageProperties();
        properties.setMaxSize(10 * 1024 * 1024L);
        service = new EnterpriseLicenseFileService(
                objectStorage,
                mapperStub.mapper(),
                properties,
                new SnowflakeIdGenerator(3)
        );
    }

    @Test
    void onlyEnterpriseUserCanUploadLicense() {
        BizException error = assertThrows(BizException.class,
                () -> service.uploadLicense(user(7L, 1, "PLATFORM"), pdf()));

        assertEquals(40300, error.getCode());
        assertNull(objectStorage.uploadedKey);
    }

    @Test
    void rejectsExtensionAndMimeMismatch() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "license.pdf", "image/png", "fake".getBytes());

        assertThrows(BizException.class,
                () -> service.uploadLicense(user(42L, 2, "PLATFORM"), file));
        assertNull(objectStorage.uploadedKey);
    }

    @Test
    void isolatesPreTenantObjectByOwnerId() {
        service.uploadLicense(user(42L, 2, "PLATFORM"), pdf());

        assertTrue(objectStorage.uploadedKey.startsWith("enterprise/license/owner/42/"));
        assertEquals("PLATFORM", mapperStub.inserted.getTenantCode());
        assertEquals("42", mapperStub.inserted.getCreator());
    }

    @Test
    void deletesUploadedObjectWhenMetadataInsertFails() {
        mapperStub.insertFailure = new IllegalStateException("database down");

        assertThrows(BizException.class,
                () -> service.uploadLicense(user(42L, 2, "PLATFORM"), pdf()));

        assertEquals(objectStorage.uploadedKey, objectStorage.deletedKey);
    }

    @Test
    void deniesDownloadOwnedByAnotherPreTenantUser() {
        mapperStub.selected.add(meta("PLATFORM", "99"));

        BizException error = assertThrows(BizException.class,
                () -> service.loadLicense(user(42L, 2, "PLATFORM"), 1001L));

        assertEquals(40300, error.getCode());
        assertNull(objectStorage.downloadedKey);
    }

    @Test
    void allowsDownloadForSameTenant() {
        mapperStub.selected.add(meta("TENANT0000000001", "99"));

        service.loadLicense(user(42L, 2, "TENANT0000000001"), 1001L);

        assertEquals("object-key", objectStorage.downloadedKey);
    }

    @Test
    void internalOwnershipRequiresMatchingOwner() {
        mapperStub.selected.add(meta("PLATFORM", "42"));
        mapperStub.selected.add(null);

        assertTrue(service.isEnterpriseLicenseOwnedBy(user(42L, 2, "PLATFORM"), 1001L));
        assertFalse(service.isEnterpriseLicenseOwnedBy(user(99L, 2, "PLATFORM"), 1001L));
    }

    @Test
    void internalOwnershipRejectsNonEnterpriseUser() {
        BizException error = assertThrows(BizException.class,
                () -> service.isEnterpriseLicenseOwnedBy(user(42L, 1, "PLATFORM"), 1001L));

        assertEquals(40300, error.getCode());
        assertEquals(0, mapperStub.selectionIndex);
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile(
                "file", "license.pdf", "application/pdf", "%PDF-test".getBytes());
    }

    private LoginUser user(long userId, int userType, String tenantCode) {
        return new LoginUser(userId, "U" + userId, "user", userType, tenantCode);
    }

    private FileMeta meta(String tenantCode, String creator) {
        return FileMeta.builder()
                .id(1001L)
                .tenantCode(tenantCode)
                .fileName("license.pdf")
                .objectKey("object-key")
                .fileSize(10L)
                .mimeType("application/pdf")
                .bizType(2)
                .creator(creator)
                .deleted(false)
                .build();
    }

    private static class RecordingStorage implements ObjectStorage {

        private String uploadedKey;
        private String downloadedKey;
        private String deletedKey;

        @Override
        public void upload(String key, InputStream input, long size, String mimeType) {
            uploadedKey = key;
        }

        @Override
        public Resource download(String key) {
            downloadedKey = key;
            return new ByteArrayResource("content".getBytes());
        }

        @Override
        public void delete(String key) {
            deletedKey = key;
        }

        @Override
        public boolean exists(String key) {
            return false;
        }
    }

    private static class MapperStub {

        private final List<FileMeta> selected = new ArrayList<>();
        private int selectionIndex;
        private FileMeta inserted;
        private RuntimeException insertFailure;

        private FileMetaMapper mapper() {
            return (FileMetaMapper) Proxy.newProxyInstance(
                    FileMetaMapper.class.getClassLoader(),
                    new Class<?>[]{FileMetaMapper.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "insert" -> insert((FileMeta) args[0]);
                        case "selectOne" -> selected.get(selectionIndex++);
                        case "toString" -> "FileMetaMapperStub";
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
            );
        }

        private int insert(FileMeta meta) {
            if (insertFailure != null) {
                throw insertFailure;
            }
            inserted = meta;
            return 1;
        }
    }
}
