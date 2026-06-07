package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStorageObject;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStoragePage;
import io.github.jerryt92.j2agent.service.file.oss.model.PresignedUploadCredential;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;

/**
 * 供应商无关的对象存储服务。
 */
public interface ObjectStorageService extends AutoCloseable {

    String getProvider();

    String getDefaultBucket();

    void putObject(String bucket, String objectName, InputStream inputStream, long size, String contentType);

    InputStream getObject(String bucket, String objectName);

    void removeObject(String bucket, String objectName);

    boolean objectExists(String bucket, String objectName);

    ObjectStorageObject getObjectMetadata(String bucket, String objectName);

    ObjectStoragePage listObjects(String bucket, String prefix, String continuationToken, int pageSize);

    URL generatePresignedUrl(String bucket, String objectName, Duration expiry);

    PresignedUploadCredential generatePresignedUploadUrl(
            String bucket,
            String objectName,
            Duration expiry,
            String contentType,
            long contentLength
    );

    default void putObject(String objectName, InputStream inputStream, long size, String contentType) {
        putObject(getDefaultBucket(), objectName, inputStream, size, contentType);
    }

    default InputStream getObject(String objectName) {
        return getObject(getDefaultBucket(), objectName);
    }

    default void removeObject(String objectName) {
        removeObject(getDefaultBucket(), objectName);
    }

    default boolean objectExists(String objectName) {
        return objectExists(getDefaultBucket(), objectName);
    }

    default ObjectStorageObject getObjectMetadata(String objectName) {
        return getObjectMetadata(getDefaultBucket(), objectName);
    }

    default ObjectStoragePage listObjects(String prefix, String continuationToken, int pageSize) {
        return listObjects(getDefaultBucket(), prefix, continuationToken, pageSize);
    }

    default URL generatePresignedUrl(String objectName, Duration expiry) {
        return generatePresignedUrl(getDefaultBucket(), objectName, expiry);
    }

    default PresignedUploadCredential generatePresignedUploadUrl(
            String objectName,
            Duration expiry,
            String contentType,
            long contentLength
    ) {
        return generatePresignedUploadUrl(
                getDefaultBucket(), objectName, expiry, contentType, contentLength
        );
    }

    @Override
    default void close() {
    }
}