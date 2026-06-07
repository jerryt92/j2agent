package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

@Data
public class ObjectStorageSyncTaskPo {
    private String id;
    private String bucketName;
    private String provider;
    private String taskStatus;
    private Long scannedCount;
    private Long inSyncCount;
    private Long ossOnlyCount;
    private Long dbOnlyCount;
    private Long mismatchCount;
    private Long inProgressCount;
    private String errorMessage;
    private Long createdAt;
    private Long startedAt;
    private Long completedAt;
}
