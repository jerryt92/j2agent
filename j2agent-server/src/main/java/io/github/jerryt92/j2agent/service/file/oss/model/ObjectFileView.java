package io.github.jerryt92.j2agent.service.file.oss.model;

public record ObjectFileView(
        String objectKey,
        String name,
        boolean directory,
        String etag,
        long size,
        String contentType,
        long lastModified,
        String operationStatus,
        String lastError
) {
}
