package io.github.jerryt92.j2agent.config.storage;

import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;

import io.github.jerryt92.j2agent.service.file.oss.provider.MinioObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.OssObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.QiniuObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.R2ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.RustfsObjectStorageService;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObjectStorageConfigTest {
    private final ObjectStorageConfig config = new ObjectStorageConfig();

    @Test
    void shouldCreateMinioService() {
        ObjectStorageProperties properties = baseProperties(ObjectStorageProperties.StorageType.MINIO);
        properties.getS3().setEndpoint("http://127.0.0.1:9000");
        properties.getS3().setAccessKeyId("access-key-id");
        properties.getS3().setSecretAccessKey("secret-access-key");

        assertInstanceOf(MinioObjectStorageService.class, config.objectStorageService(properties));
    }

    @Test
    void shouldCreateRustfsService() {
        ObjectStorageProperties properties = baseProperties(ObjectStorageProperties.StorageType.RUSTFS);
        properties.getS3().setEndpoint("http://127.0.0.1:9000");
        properties.getS3().setAccessKeyId("access-key-id");
        properties.getS3().setSecretAccessKey("secret-access-key");

        assertInstanceOf(RustfsObjectStorageService.class, config.objectStorageService(properties));
    }

    @Test
    void shouldCreateAliyunOssService() {
        ObjectStorageProperties properties = baseProperties(ObjectStorageProperties.StorageType.OSS);
        properties.getOss().setEndpoint("https://oss-cn-hangzhou.aliyuncs.com");
        properties.getOss().setAccessKeyId("access-key");
        properties.getOss().setAccessKeySecret("secret-key");

        ObjectStorageService service = config.objectStorageService(properties);
        assertInstanceOf(OssObjectStorageService.class, service);
        service.close();
    }

    @Test
    void shouldCreateQiniuService() {
        ObjectStorageProperties properties = baseProperties(ObjectStorageProperties.StorageType.QINIU);
        properties.getQiniu().setAccessKey("access-key");
        properties.getQiniu().setSecretKey("secret-key");
        properties.getQiniu().setDomain("cdn.example.com");

        assertInstanceOf(QiniuObjectStorageService.class, config.objectStorageService(properties));
    }

    @Test
    void shouldCreateCloudflareR2Service() {
        ObjectStorageProperties properties = baseProperties(ObjectStorageProperties.StorageType.R2);
        properties.getS3().setEndpoint("https://account-id.r2.cloudflarestorage.com");
        properties.getS3().setAccessKeyId("access-key");
        properties.getS3().setSecretAccessKey("secret-key");

        assertInstanceOf(R2ObjectStorageService.class, config.objectStorageService(properties));
    }

    @Test
    void shouldRejectMissingSecretAccessKeyForS3Storage() {
        ObjectStorageProperties properties = baseProperties(ObjectStorageProperties.StorageType.MINIO);
        properties.getS3().setEndpoint("http://127.0.0.1:9000");
        properties.getS3().setAccessKeyId("access-key-id");

        assertThrows(IllegalArgumentException.class, () -> config.objectStorageService(properties));
    }

    @Test
    void shouldRejectIncompleteProviderConfiguration() {
        ObjectStorageProperties properties = baseProperties(ObjectStorageProperties.StorageType.MINIO);

        assertThrows(IllegalArgumentException.class, () -> config.objectStorageService(properties));
    }

    private ObjectStorageProperties baseProperties(ObjectStorageProperties.StorageType type) {
        ObjectStorageProperties properties = new ObjectStorageProperties();
        properties.setType(type);
        properties.setBucket("test-bucket");
        return properties;
    }
}
