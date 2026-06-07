package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

@Data
public class ObjectFilePo {
    private String id;
    private String provider;
    private String bucketName;
    private String objectKey;
    private String objectKeyHash;
    private String etag;
    private Long sizeBytes;
    private String contentType;
    private Long objectModifiedAt;
    private String operationStatus;
    private String lastError;
    private Long createdAt;
    private Long updatedAt;
}
