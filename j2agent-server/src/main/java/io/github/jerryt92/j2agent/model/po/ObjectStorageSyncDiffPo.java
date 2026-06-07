package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

@Data
public class ObjectStorageSyncDiffPo {
    private String id;
    private String taskId;
    private String bucketName;
    private String objectKey;
    private String objectKeyHash;
    private String diffType;
    private String resolutionStatus;
    private String ossEtag;
    private Long ossSizeBytes;
    private Long ossModifiedAt;
    private String dbEtag;
    private Long dbSizeBytes;
    private Long dbModifiedAt;
    private String resolutionAction;
    private String resolutionError;
    private Long createdAt;
    private Long updatedAt;
}
