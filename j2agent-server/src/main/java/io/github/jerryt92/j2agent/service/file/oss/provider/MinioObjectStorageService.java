package io.github.jerryt92.j2agent.service.file.oss.provider;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * MinIO 对象存储实现。
 */
public class MinioObjectStorageService extends S3CompatibleObjectStorageService {
    public MinioObjectStorageService(S3Client s3Client, S3Presigner presigner, String defaultBucket) {
        super(s3Client, presigner, defaultBucket, "MinIO", true);
    }
}
