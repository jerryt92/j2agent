package io.github.jerryt92.j2agent.service.file.oss.provider;

import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.exception.ObjectStorageException;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStorageObject;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStoragePage;
import io.github.jerryt92.j2agent.service.file.oss.model.PresignedUploadCredential;

import com.qiniu.common.QiniuException;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.DownloadUrl;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.FileInfo;
import com.qiniu.storage.model.FileListing;
import com.qiniu.util.Auth;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 七牛云 Kodo 对象存储实现。
 */
public class QiniuObjectStorageService implements ObjectStorageService {
    private final Auth auth;
    private final UploadManager uploadManager;
    private final BucketManager bucketManager;
    private final OkHttpClient httpClient;
    private final String domain;
    private final boolean useHttps;
    private final String defaultBucket;

    public QiniuObjectStorageService(
            String accessKey,
            String secretKey,
            String domain,
            boolean useHttps,
            String defaultBucket,
            OkHttpClient httpClient
    ) {
        this.auth = Auth.create(
                ObjectStorageSupport.requireName(accessKey, "accessKey"),
                ObjectStorageSupport.requireName(secretKey, "secretKey")
        );
        Configuration configuration = Configuration.create(Region.autoRegion());
        configuration.resumableUploadAPIVersion = Configuration.ResumableUploadAPIVersion.V2;
        this.uploadManager = new UploadManager(configuration);
        this.bucketManager = new BucketManager(auth, configuration);
        this.httpClient = httpClient;
        this.domain = normalizeDomain(domain);
        this.useHttps = useHttps;
        this.defaultBucket = ObjectStorageSupport.requireName(defaultBucket, "defaultBucket");
    }

    @Override
    public String getProvider() {
        return "Qiniu Kodo";
    }

    @Override
    public String getDefaultBucket() {
        return defaultBucket;
    }

    @Override
    public void putObject(String bucket, String objectName, InputStream inputStream, long size, String contentType) {
        validateUpload(bucket, objectName, inputStream, size);
        try {
            String uploadToken = auth.uploadToken(bucket, objectName);
            uploadManager.put(inputStream, objectName, uploadToken, null, contentType);
        } catch (Exception e) {
            throw failure("upload", bucket, objectName, e);
        }
    }

    @Override
    public InputStream getObject(String bucket, String objectName) {
        validateObject(bucket, objectName);
        URL url = generatePresignedUrl(bucket, objectName, Duration.ofMinutes(5));
        try {
            Response response = httpClient.newCall(new Request.Builder().url(url).get().build()).execute();
            if (!response.isSuccessful()) {
                response.close();
                throw new IOException("Unexpected HTTP status " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                response.close();
                throw new IOException("Empty response body");
            }
            return body.byteStream();
        } catch (Exception e) {
            throw failure("download", bucket, objectName, e);
        }
    }

    @Override
    public void removeObject(String bucket, String objectName) {
        validateObject(bucket, objectName);
        try {
            bucketManager.delete(bucket, objectName);
        } catch (Exception e) {
            throw failure("delete", bucket, objectName, e);
        }
    }

    @Override
    public boolean objectExists(String bucket, String objectName) {
        validateObject(bucket, objectName);
        try {
            bucketManager.stat(bucket, objectName);
            return true;
        } catch (QiniuException e) {
            if (e.code() == 612 || e.code() == 631) {
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
        try {
            return toObject(bucketManager.stat(bucket, objectName));
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
        try {
            FileListing listing = bucketManager.listFilesV2(
                    bucket,
                    prefix == null ? "" : prefix,
                    continuationToken,
                    ObjectStorageSupport.pageSize(pageSize),
                    null
            );
            List<ObjectStorageObject> objects = listing.items == null
                    ? List.of()
                    : Arrays.stream(listing.items).map(this::toObject).toList();
            return new ObjectStoragePage(objects, listing.isEOF() ? null : listing.marker);
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to list objects in " + bucket + " with Qiniu Kodo", e);
        }
    }

    @Override
    public URL generatePresignedUrl(String bucket, String objectName, Duration expiry) {
        validateObject(bucket, objectName);
        if (!defaultBucket.equals(bucket)) {
            throw new IllegalArgumentException(
                    "Qiniu download domain is configured for the default bucket only: " + defaultBucket
            );
        }
        try {
            long deadline = Math.addExact(
                    System.currentTimeMillis() / 1000,
                    ObjectStorageSupport.expirySeconds(expiry)
            );
            String url = new DownloadUrl(domain, useHttps, objectName).buildURL(auth, deadline);
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
        try {
            int expirySeconds = ObjectStorageSupport.expirySeconds(expiry);
            String uploadToken = auth.uploadToken(bucket, objectName);
            return new PresignedUploadCredential(
                    getProvider(),
                    "https://upload.qiniup.com",
                    "POST",
                    Map.of(),
                    System.currentTimeMillis() + expirySeconds * 1000L,
                    Map.of("uploadToken", uploadToken, "objectKey", objectName)
            );
        } catch (Exception e) {
            throw failure("generate upload URL for", bucket, objectName, e);
        }
    }

    private String normalizeDomain(String value) {
        String normalized = ObjectStorageSupport.requireName(value, "domain").trim();
        return normalized.replaceFirst("^https?://", "").replaceFirst("/+$", "");
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

    private void validateContentLength(long contentLength) {
        if (contentLength <= 0) {
            throw new IllegalArgumentException("contentLength must be positive");
        }
    }

    private void validateObject(String bucket, String objectName) {
        ObjectStorageSupport.requireName(bucket, "bucket");
        ObjectStorageSupport.requireName(objectName, "objectName");
    }

    private ObjectStorageException failure(String operation, String bucket, String objectName, Exception cause) {
        return new ObjectStorageException(
                "Failed to " + operation + " object " + bucket + "/" + objectName + " with Qiniu Kodo",
                cause
        );
    }

    private ObjectStorageObject toObject(FileInfo info) {
        return new ObjectStorageObject(
                info.key,
                info.hash,
                info.fsize,
                info.mimeType,
                info.putTime / 10_000
        );
    }
}