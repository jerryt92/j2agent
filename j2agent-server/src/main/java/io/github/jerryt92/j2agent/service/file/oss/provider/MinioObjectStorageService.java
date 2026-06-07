package io.github.jerryt92.j2agent.service.file.oss.provider;

import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.exception.ObjectStorageException;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStorageObject;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStoragePage;
import io.github.jerryt92.j2agent.service.file.oss.model.PresignedUploadCredential;
import io.github.jerryt92.j2agent.service.file.oss.util.ObjectKeyUtils;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import io.minio.messages.Item;

import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MinIO 对象存储实现。
 */
public class MinioObjectStorageService implements ObjectStorageService {
    private final MinioClient client;
    private final String defaultBucket;
    private final String providerName;
    private final boolean autoCreateBucket;
    private volatile boolean defaultBucketReady;

    public MinioObjectStorageService(MinioClient client, String defaultBucket) {
        this(client, defaultBucket, "MinIO", true);
    }

    protected MinioObjectStorageService(MinioClient client, String defaultBucket, String providerName) {
        this(client, defaultBucket, providerName, false);
    }

    protected MinioObjectStorageService(
            MinioClient client,
            String defaultBucket,
            String providerName,
            boolean autoCreateBucket
    ) {
        this.client = client;
        this.defaultBucket = ObjectStorageSupport.requireName(defaultBucket, "defaultBucket");
        this.providerName = ObjectStorageSupport.requireName(providerName, "providerName");
        this.autoCreateBucket = autoCreateBucket;
    }

    @Override
    public String getProvider() {
        return providerName;
    }

    @Override
    public String getDefaultBucket() {
        return defaultBucket;
    }

    @Override
    public void putObject(String bucket, String objectName, InputStream inputStream, long size, String contentType) {
        validateUpload(bucket, objectName, inputStream, size);
        ensureBucket(bucket);
        try {
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectName)
                    .stream(inputStream, size, -1);
            if (contentType != null && !contentType.isBlank()) {
                builder.contentType(contentType);
            }
            client.putObject(builder.build());
        } catch (Exception e) {
            throw failure("upload", bucket, objectName, e);
        }
    }

    @Override
    public InputStream getObject(String bucket, String objectName) {
        validateObject(bucket, objectName);
        ensureBucket(bucket);
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectName).build());
        } catch (Exception e) {
            throw failure("download", bucket, objectName, e);
        }
    }

    @Override
    public void removeObject(String bucket, String objectName) {
        validateObject(bucket, objectName);
        ensureBucket(bucket);
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectName).build());
            removeEmptyParentDirectoryMarkers(bucket, objectName);
        } catch (Exception e) {
            throw failure("delete", bucket, objectName, e);
        }
    }

    @Override
    public boolean objectExists(String bucket, String objectName) {
        validateObject(bucket, objectName);
        ensureBucket(bucket);
        try {
            client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectName).build());
            return true;
        } catch (io.minio.errors.ErrorResponseException e) {
            String code = e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code) || "NoSuchBucket".equals(code)) {
                return false;
            }
            throw failure("check existence of", bucket, objectName, e);
        } catch (Exception e) {
            throw failure("check existence of", bucket, objectName, e);
        }
    }

    @Override
    public ObjectStorageObject getObjectMetadata(String bucket, String objectName) {
        validateObject(bucket, objectName);
        ensureBucket(bucket);
        try {
            StatObjectResponse stat = client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectName).build()
            );
            return new ObjectStorageObject(
                    objectName,
                    stat.etag(),
                    stat.size(),
                    stat.contentType(),
                    stat.lastModified().toInstant().toEpochMilli()
            );
        } catch (Exception e) {
            throw failure("read metadata of", bucket, objectName, e);
        }
    }

    @Override
    public ObjectStoragePage listObjects(
            String bucket,
            String prefix,
            String continuationToken,
            int pageSize
    ) {
        ObjectStorageSupport.requireName(bucket, "bucket");
        ensureBucket(bucket);
        int normalizedPageSize = ObjectStorageSupport.pageSize(pageSize);
        try {
            ListObjectsArgs.Builder builder = ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix == null ? "" : prefix)
                    .recursive(true)
                    .maxKeys(normalizedPageSize);
            if (continuationToken != null && !continuationToken.isBlank()) {
                builder.startAfter(continuationToken);
            }
            List<ObjectStorageObject> objects = new ArrayList<>(normalizedPageSize);
            for (io.minio.Result<Item> result : client.listObjects(builder.build())) {
                Item item = result.get();
                if (item.isDir() || ObjectKeyUtils.isDirectoryMarker(item.objectName())) {
                    continue;
                }
                objects.add(new ObjectStorageObject(
                        item.objectName(),
                        item.etag(),
                        item.size(),
                        null,
                        item.lastModified().toInstant().toEpochMilli()
                ));
                if (objects.size() >= normalizedPageSize) {
                    break;
                }
            }
            String nextToken = objects.size() == normalizedPageSize
                    ? objects.get(objects.size() - 1).objectName()
                    : null;
            return new ObjectStoragePage(List.copyOf(objects), nextToken);
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to list objects in " + bucket + " with " + providerName, e);
        }
    }

    @Override
    public URL generatePresignedUrl(String bucket, String objectName, Duration expiry) {
        validateObject(bucket, objectName);
        ensureBucket(bucket);
        try {
            String url = client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(ObjectStorageSupport.expirySeconds(expiry))
                    .build());
            return new URL(url);
        } catch (Exception e) {
            throw failure("generate URL for", bucket, objectName, e);
        }
    }

    @Override
    public PresignedUploadCredential generatePresignedUploadUrl(
            String bucket,
            String objectName,
            Duration expiry,
            String contentType,
            long contentLength
    ) {
        validateObject(bucket, objectName);
        validateContentLength(contentLength);
        ensureBucket(bucket);
        try {
            int expirySeconds = ObjectStorageSupport.expirySeconds(expiry);
            GetPresignedObjectUrlArgs.Builder builder = GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(bucket)
                    .object(objectName)
                    .expiry(expirySeconds);
            Map<String, String> headers = new HashMap<>();
            if (contentType != null && !contentType.isBlank()) {
                headers.put("Content-Type", contentType);
                builder.extraHeaders(headers);
            }
            String url = client.getPresignedObjectUrl(builder.build());
            return new PresignedUploadCredential(
                    providerName,
                    url,
                    "PUT",
                    Map.copyOf(headers),
                    System.currentTimeMillis() + expirySeconds * 1000L,
                    Map.of()
            );
        } catch (Exception e) {
            throw failure("generate upload URL for", bucket, objectName, e);
        }
    }

    private void validateContentLength(long contentLength) {
        if (contentLength <= 0) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
    }

    private void validateUpload(String bucket, String objectName, InputStream inputStream, long size) {
        validateObject(bucket, objectName);
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream must not be null");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
    }

    private void validateObject(String bucket, String objectName) {
        ObjectStorageSupport.requireName(bucket, "bucket");
        ObjectStorageSupport.requireName(objectName, "objectName");
    }

    private void ensureBucket(String bucket) {
        if (!autoCreateBucket || !defaultBucket.equals(bucket) || defaultBucketReady) {
            return;
        }
        synchronized (this) {
            if (defaultBucketReady) {
                return;
            }
            try {
                boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                }
                defaultBucketReady = true;
            } catch (Exception e) {
                throw new ObjectStorageException(
                        "Failed to initialize bucket " + bucket + " with " + providerName,
                        e
                );
            }
        }
    }

    private void removeEmptyParentDirectoryMarkers(String bucket, String deletedObjectName) {
        for (String prefix : ObjectKeyUtils.parentDirectoryPrefixes(deletedObjectName)) {
            if (prefixHasRemainingObjects(bucket, prefix)) {
                break;
            }
            removeDirectoryMarkerIfPresent(bucket, prefix);
        }
    }

    private boolean prefixHasRemainingObjects(String bucket, String prefix) {
        ListObjectsArgs args = ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(prefix)
                .recursive(true)
                .build();
        try {
            for (io.minio.Result<Item> result : client.listObjects(args)) {
                if (!prefix.equals(result.get().objectName())) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            throw failure("inspect directory", bucket, prefix, e);
        }
    }

    private void removeDirectoryMarkerIfPresent(String bucket, String prefix) {
        if (!objectExists(bucket, prefix)) {
            return;
        }
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(prefix).build());
        } catch (Exception e) {
            throw failure("delete directory marker", bucket, prefix, e);
        }
    }

    private ObjectStorageException failure(String operation, String bucket, String objectName, Exception cause) {
        return new ObjectStorageException(
                "Failed to " + operation + " object " + bucket + "/" + objectName + " with " + providerName,
                cause
        );
    }
}
