package io.github.jerryt92.j2agent.config;

import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.MinioObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.OssObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.QiniuObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.provider.R2ObjectStorageService;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 根据配置装配对象存储实现，业务侧只依赖 {@link ObjectStorageService}。
 */
@Configuration
@ConditionalOnProperty(prefix = "j2agent.storage", name = "enabled", havingValue = "true")
public class ObjectStorageConfig {

    @Bean(destroyMethod = "close")
    public ObjectStorageService objectStorageService(ObjectStorageProperties properties) {
        requireText(properties.getBucket(), "j2agent.storage.bucket");
        return switch (properties.getType()) {
            case MINIO -> createMinioService(properties);
            case OSS -> createOssService(properties);
            case QINIU -> createQiniuService(properties);
            case R2 -> createR2Service(properties);
        };
    }

    private ObjectStorageService createMinioService(ObjectStorageProperties properties) {
        ObjectStorageProperties.Minio minio = properties.getMinio();
        requireText(minio.getEndpoint(), "j2agent.storage.minio.endpoint");
        requireText(minio.getAccessKey(), "j2agent.storage.minio.access-key");
        requireText(minio.getSecretKey(), "j2agent.storage.minio.secret-key");
        MinioClient client = MinioClient.builder()
                .endpoint(minio.getEndpoint())
                .credentials(minio.getAccessKey(), minio.getSecretKey())
                .build();
        return new MinioObjectStorageService(client, properties.getBucket());
    }

    private ObjectStorageService createOssService(ObjectStorageProperties properties) {
        ObjectStorageProperties.Oss oss = properties.getOss();
        requireText(oss.getEndpoint(), "j2agent.storage.oss.endpoint");
        requireText(oss.getAccessKeyId(), "j2agent.storage.oss.access-key-id");
        requireText(oss.getAccessKeySecret(), "j2agent.storage.oss.access-key-secret");
        OSS client = new OSSClientBuilder().build(
                oss.getEndpoint(),
                oss.getAccessKeyId(),
                oss.getAccessKeySecret()
        );
        return new OssObjectStorageService(client, properties.getBucket());
    }

    private ObjectStorageService createQiniuService(ObjectStorageProperties properties) {
        ObjectStorageProperties.Qiniu qiniu = properties.getQiniu();
        requireText(qiniu.getAccessKey(), "j2agent.storage.qiniu.access-key");
        requireText(qiniu.getSecretKey(), "j2agent.storage.qiniu.secret-key");
        requireText(qiniu.getDomain(), "j2agent.storage.qiniu.domain");
        return new QiniuObjectStorageService(
                qiniu.getAccessKey(),
                qiniu.getSecretKey(),
                qiniu.getDomain(),
                qiniu.isUseHttps(),
                properties.getBucket(),
                new OkHttpClient()
        );
    }

    private ObjectStorageService createR2Service(ObjectStorageProperties properties) {
        ObjectStorageProperties.R2 r2 = properties.getR2();
        requireText(r2.getEndpoint(), "j2agent.storage.r2.endpoint");
        requireText(r2.getAccessKeyId(), "j2agent.storage.r2.access-key-id");
        requireText(r2.getSecretAccessKey(), "j2agent.storage.r2.secret-access-key");
        MinioClient client = MinioClient.builder()
                .endpoint(r2.getEndpoint())
                .credentials(r2.getAccessKeyId(), r2.getSecretAccessKey())
                .build();
        return new R2ObjectStorageService(client, properties.getBucket());
    }

    private void requireText(String value, String propertyName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Missing object storage property: " + propertyName);
        }
    }
}