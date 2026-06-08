package io.github.jerryt92.j2agent.service.file.oss.provider;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Cloudflare R2 实现。R2 提供 S3 兼容 API，因此复用经过验证的 S3 客户端逻辑。
 */
public class R2ObjectStorageService extends S3CompatibleObjectStorageService {
    public R2ObjectStorageService(S3Client s3Client, S3Presigner presigner, String defaultBucket) {
        super(s3Client, presigner, defaultBucket, "Cloudflare R2", false);
    }
}
