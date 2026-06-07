package io.github.jerryt92.j2agent.service.file.oss.model;

/**
 * 供应商无关的对象元数据。
 */
public record ObjectStorageObject(
        String objectName,
        String etag,
        long size,
        String contentType,
        long lastModified
) {
}
