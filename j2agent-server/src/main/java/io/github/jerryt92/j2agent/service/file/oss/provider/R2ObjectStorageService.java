package io.github.jerryt92.j2agent.service.file.oss.provider;

import io.minio.MinioClient;

/**
 * Cloudflare R2 实现。R2 提供 S3 兼容 API，因此复用经过验证的 S3 客户端逻辑。
 */
public class R2ObjectStorageService extends MinioObjectStorageService {
    public R2ObjectStorageService(MinioClient client, String defaultBucket) {
        super(client, defaultBucket, "Cloudflare R2");
    }
}
