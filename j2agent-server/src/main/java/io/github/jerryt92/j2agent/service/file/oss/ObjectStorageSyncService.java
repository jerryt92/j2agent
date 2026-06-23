package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.service.file.oss.exception.StaleSnapshotException;
import io.github.jerryt92.j2agent.service.file.oss.exception.DifferenceCheckCancelledException;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStorageObject;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStoragePage;
import io.github.jerryt92.j2agent.service.file.oss.util.ObjectKeyUtils;

import io.github.jerryt92.j2agent.config.storage.ObjectStorageTaskConfig;
import io.github.jerryt92.j2agent.mapper.ObjectFileMapper;
import io.github.jerryt92.j2agent.mapper.ObjectStorageSyncDiffMapper;
import io.github.jerryt92.j2agent.mapper.ObjectStorageSyncTaskMapper;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import io.github.jerryt92.j2agent.model.po.ObjectStorageSyncDiffPo;
import io.github.jerryt92.j2agent.model.po.ObjectStorageSyncTaskPo;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnBean(ObjectStorageService.class)
public class ObjectStorageSyncService {
    public static final String IN_SYNC = "IN_SYNC";
    public static final String OSS_ONLY = "OSS_ONLY";
    public static final String DB_ONLY = "DB_ONLY";
    public static final String METADATA_MISMATCH = "METADATA_MISMATCH";
    public static final String IN_PROGRESS = "IN_PROGRESS";

    private static final String UPLOADING = "UPLOADING";
    private static final String DELETING = "DELETING";

    private final ObjectStorageService storageService;
    private final ObjectFileManagementService fileManagementService;
    private final ObjectFileMapper fileMapper;
    private final ObjectFileReferenceService referenceService;
    private final ObjectStorageSyncTaskMapper taskMapper;
    private final ObjectStorageSyncDiffMapper diffMapper;
    private final ObjectStorageDifferenceSnapshotService snapshotService;
    private final TaskExecutor taskExecutor;
    private final Set<String> cancellationRequests = ConcurrentHashMap.newKeySet();

    public ObjectStorageSyncService(
            ObjectStorageService storageService,
            ObjectFileManagementService fileManagementService,
            ObjectFileMapper fileMapper,
            ObjectFileReferenceService referenceService,
            ObjectStorageSyncTaskMapper taskMapper,
            ObjectStorageSyncDiffMapper diffMapper,
            ObjectStorageDifferenceSnapshotService snapshotService,
            @Qualifier(ObjectStorageTaskConfig.OBJECT_STORAGE_TASK_EXECUTOR) TaskExecutor taskExecutor
    ) {
        this.storageService = storageService;
        this.fileManagementService = fileManagementService;
        this.fileMapper = fileMapper;
        this.referenceService = referenceService;
        this.taskMapper = taskMapper;
        this.diffMapper = diffMapper;
        this.snapshotService = snapshotService;
        this.taskExecutor = taskExecutor;
    }

    public synchronized ObjectStorageSyncTaskPo startScan() {
        String bucket = storageService.getDefaultBucket();
        if (taskMapper.countActive(bucket) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a scan task is already running");
        }
        long now = System.currentTimeMillis();
        ObjectStorageSyncTaskPo task = new ObjectStorageSyncTaskPo();
        task.setId(UUIDv7Utils.randomUUIDv7());
        task.setBucketName(bucket);
        task.setProvider(storageService.getProvider());
        task.setTaskStatus("PENDING");
        task.setScannedCount(0L);
        task.setInSyncCount(0L);
        task.setOssOnlyCount(0L);
        task.setDbOnlyCount(0L);
        task.setMismatchCount(0L);
        task.setInProgressCount(0L);
        task.setCreatedAt(now);
        taskMapper.insert(task);
        taskExecutor.execute(() -> runScan(task.getId()));
        return task;
    }

    public ObjectStorageSyncTaskPo getTask(String taskId) {
        ObjectStorageSyncTaskPo task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "scan task not found");
        }
        return task;
    }

    public ObjectStorageSyncTaskPo getLatestSuccessfulTask() {
        ObjectStorageSyncTaskPo task = taskMapper.selectLatestSuccessful(storageService.getDefaultBucket());
        if (task == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "difference check result not found");
        }
        return task;
    }

    public ObjectStorageSyncTaskPo cancel(String taskId) {
        ObjectStorageSyncTaskPo task = getTask(taskId);
        if (!isActive(task.getTaskStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "difference check is not running");
        }
        if (taskMapper.requestCancellation(taskId) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "difference check is no longer running");
        }
        cancellationRequests.add(taskId);
        return getTask(taskId);
    }

    public List<ObjectStorageSyncDiffPo> listDiffs(
            String taskId,
            String diffType,
            String resolutionStatus,
            int offset,
            int limit
    ) {
        getTask(taskId);
        List<ObjectStorageSyncDiffPo> filtered = diffMapper.selectByTask(taskId).stream()
                .filter(item -> diffType == null || diffType.isBlank() || diffType.equals(item.getDiffType()))
                .filter(item -> resolutionStatus == null || resolutionStatus.isBlank()
                        || resolutionStatus.equals(item.getResolutionStatus()))
                .toList();
        int from = Math.min(Math.max(offset, 0), filtered.size());
        int to = Math.min(from + Math.max(limit, 1), filtered.size());
        return List.copyOf(filtered.subList(from, to));
    }

    public long countDiffs(String taskId, String diffType, String resolutionStatus) {
        return diffMapper.selectByTask(taskId).stream()
                .filter(item -> diffType == null || diffType.isBlank() || diffType.equals(item.getDiffType()))
                .filter(item -> resolutionStatus == null || resolutionStatus.isBlank()
                        || resolutionStatus.equals(item.getResolutionStatus()))
                .count();
    }

    public List<String> resolve(List<String> diffIds, String action) {
        if (diffIds == null || diffIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "diffIds must not be empty");
        }
        List<String> failed = new ArrayList<>();
        for (String diffId : diffIds.stream().distinct().toList()) {
            try {
                resolveOne(diffId, action);
            } catch (RuntimeException e) {
                failed.add(diffId);
            }
        }
        return failed;
    }

    void runScan(String taskId) {
        long startedAt = System.currentTimeMillis();
        if (taskMapper.markRunning(taskId, startedAt) == 0) {
            snapshotService.cancel(taskId, System.currentTimeMillis());
            cancellationRequests.remove(taskId);
            return;
        }
        try {
            String bucket = storageService.getDefaultBucket();
            Map<String, ObjectFilePo> dbFiles = new HashMap<>();
            for (ObjectFilePo po : fileMapper.selectByBucket(bucket)) {
                dbFiles.put(po.getObjectKey(), po);
            }
            Set<String> seen = new HashSet<>();
            ScanCounters counters = new ScanCounters();
            String token = null;
            do {
                ensureNotCancelled(taskId, true);
                String previousToken = token;
                ObjectStoragePage page = storageService.listObjects(bucket, "", token, 500);
                for (ObjectStorageObject object : page.objects()) {
                    ensureNotCancelled(taskId, false);
                    seen.add(object.objectName());
                    ObjectFilePo db = dbFiles.get(object.objectName());
                    String type = classifyDifference(object, db);
                    if (!IN_SYNC.equals(type)) {
                        insertDiff(taskId, bucket, type, object, db);
                    }
                    counters.increment(type);
                }
                counters.scanned += page.objects().size();
                updateProgress(taskId, counters);
                ensureNotCancelled(taskId, true);
                token = page.continuationToken();
                if (token != null && token.equals(previousToken)) {
                    throw new IllegalStateException("object storage returned a repeated continuation token");
                }
            } while (token != null && !token.isBlank());

            for (ObjectFilePo db : dbFiles.values()) {
                ensureNotCancelled(taskId, false);
                if (!seen.contains(db.getObjectKey())) {
                    String type = isInProgress(db) ? IN_PROGRESS : DB_ONLY;
                    insertDiff(taskId, bucket, type, null, db);
                    counters.increment(type);
                }
            }
            ObjectStorageSyncTaskPo completed = counters.toTask(taskId);
            completed.setCompletedAt(System.currentTimeMillis());
            ensureNotCancelled(taskId, true);
            if (!snapshotService.complete(bucket, completed)) {
                throw new DifferenceCheckCancelledException();
            }
        } catch (DifferenceCheckCancelledException e) {
            snapshotService.cancel(taskId, System.currentTimeMillis());
        } catch (Exception e) {
            if (isCancellationRequested(taskId, true)) {
                snapshotService.cancel(taskId, System.currentTimeMillis());
            } else {
                snapshotService.fail(taskId, message(e), System.currentTimeMillis());
            }
        } finally {
            cancellationRequests.remove(taskId);
        }
    }

    boolean metadataMatches(ObjectStorageObject object, ObjectFilePo db) {
        return Objects.equals(ObjectKeyUtils.normalizeEtag(object.etag()), ObjectKeyUtils.normalizeEtag(db.getEtag()))
                && Objects.equals(object.size(), db.getSizeBytes())
                && Objects.equals(
                ObjectKeyUtils.normalizeLastModified(object.lastModified()),
                ObjectKeyUtils.normalizeLastModified(db.getObjectModifiedAt())
        );
    }

    String classifyDifference(ObjectStorageObject object, ObjectFilePo db) {
        if (db == null) {
            return OSS_ONLY;
        }
        if (isInProgress(db)) {
            return IN_PROGRESS;
        }
        return metadataMatches(object, db) ? IN_SYNC : METADATA_MISMATCH;
    }

    static boolean isInProgress(ObjectFilePo db) {
        if (db == null) {
            return false;
        }
        String status = db.getOperationStatus();
        return UPLOADING.equals(status) || DELETING.equals(status);
    }

    private void resolveOne(String diffId, String action) {
        ObjectStorageSyncDiffPo diff = diffMapper.selectById(diffId);
        if (diff == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "sync difference not found");
        }
        if (!"PENDING".equals(diff.getResolutionStatus()) && !"FAILED".equals(diff.getResolutionStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "sync difference is no longer actionable");
        }
        validateAction(diff.getDiffType(), action);
        try {
            assertSnapshotCurrent(diff);
            executeAction(diff, action);
            updateResolution(diffId, "RESOLVED", action, null);
        } catch (StaleSnapshotException e) {
            updateResolution(diffId, "STALE", action, e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        } catch (Exception e) {
            updateResolution(diffId, "FAILED", action, message(e));
            throw e;
        }
    }

    private void updateResolution(String diffId, String status, String action, String error) {
        if (diffMapper.updateResolution(diffId, status, action, error, System.currentTimeMillis()) != 1) {
            throw new IllegalStateException("failed to update difference resolution status: " + diffId);
        }
    }

    private void executeAction(ObjectStorageSyncDiffPo diff, String action) {
        String bucket = diff.getBucketName();
        String key = diff.getObjectKey();
        switch (action) {
            case "REGISTER_DB", "UPDATE_DB" -> {
                ObjectStorageObject current = storageService.getObjectMetadata(bucket, key);
                ObjectFilePo existing = fileMapper.selectByKey(bucket, diff.getObjectKeyHash());
                long createdAt = existing == null ? System.currentTimeMillis() : existing.getCreatedAt();
                String existingId = existing == null ? null : existing.getId();
                fileMapper.upsert(fileManagementService.fromMetadata(existingId, current, createdAt));
            }
            case "DELETE_DB" -> deleteDbRecord(bucket, diff.getObjectKeyHash());
            case "DELETE_OSS" -> {
                if (storageService.objectExists(bucket, key)) {
                    storageService.removeObject(bucket, key);
                }
            }
            case "DELETE_BOTH" -> {
                if (storageService.objectExists(bucket, key)) {
                    storageService.removeObject(bucket, key);
                }
                deleteDbRecord(bucket, diff.getObjectKeyHash());
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported resolution action");
        }
    }

    private void deleteDbRecord(String bucket, String objectKeyHash) {
        ObjectFilePo existing = fileMapper.selectByKey(bucket, objectKeyHash);
        if (existing == null) {
            return;
        }
        referenceService.removeAllReferences(existing.getId());
        fileMapper.deleteByKey(bucket, objectKeyHash);
    }

    private void assertSnapshotCurrent(ObjectStorageSyncDiffPo diff) {
        String bucket = diff.getBucketName();
        String key = diff.getObjectKey();
        ObjectFilePo currentDb = fileMapper.selectByKey(bucket, diff.getObjectKeyHash());
        boolean exists = storageService.objectExists(bucket, key);
        ObjectStorageObject currentOss = exists ? storageService.getObjectMetadata(bucket, key) : null;
        if (!ossSnapshotMatches(diff, currentOss) || !dbSnapshotMatches(diff, currentDb)) {
            throw new StaleSnapshotException("object or database record changed after the scan");
        }
    }

    private boolean ossSnapshotMatches(ObjectStorageSyncDiffPo diff, ObjectStorageObject current) {
        if (diff.getOssSizeBytes() == null) {
            return current == null;
        }
        return current != null
                && Objects.equals(ObjectKeyUtils.normalizeEtag(diff.getOssEtag()),
                ObjectKeyUtils.normalizeEtag(current.etag()))
                && Objects.equals(diff.getOssSizeBytes(), current.size())
                && Objects.equals(
                ObjectKeyUtils.normalizeLastModified(diff.getOssModifiedAt()),
                ObjectKeyUtils.normalizeLastModified(current.lastModified())
        );
    }

    private boolean dbSnapshotMatches(ObjectStorageSyncDiffPo diff, ObjectFilePo current) {
        if (diff.getDbSizeBytes() == null) {
            return current == null;
        }
        return current != null
                && Objects.equals(ObjectKeyUtils.normalizeEtag(diff.getDbEtag()),
                ObjectKeyUtils.normalizeEtag(current.getEtag()))
                && Objects.equals(diff.getDbSizeBytes(), current.getSizeBytes())
                && Objects.equals(
                ObjectKeyUtils.normalizeLastModified(diff.getDbModifiedAt()),
                ObjectKeyUtils.normalizeLastModified(current.getObjectModifiedAt())
        );
    }

    private void validateAction(String type, String action) {
        boolean valid = switch (type) {
            case OSS_ONLY -> "REGISTER_DB".equals(action) || "DELETE_OSS".equals(action);
            case DB_ONLY -> "DELETE_DB".equals(action);
            case METADATA_MISMATCH -> "UPDATE_DB".equals(action) || "DELETE_BOTH".equals(action);
            default -> false;
        };
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "action is not valid for " + type);
        }
    }

    private void insertDiff(
            String taskId,
            String bucket,
            String type,
            ObjectStorageObject object,
            ObjectFilePo db
    ) {
        long now = System.currentTimeMillis();
        String key = object != null ? object.objectName() : db.getObjectKey();
        ObjectStorageSyncDiffPo diff = new ObjectStorageSyncDiffPo();
        diff.setId(UUIDv7Utils.randomUUIDv7());
        diff.setTaskId(taskId);
        diff.setBucketName(bucket);
        diff.setObjectKey(key);
        diff.setObjectKeyHash(ObjectKeyUtils.hash(key));
        diff.setDiffType(type);
        diff.setResolutionStatus("PENDING");
        if (object != null) {
            diff.setOssEtag(ObjectKeyUtils.normalizeEtag(object.etag()));
            diff.setOssSizeBytes(object.size());
            diff.setOssModifiedAt(ObjectKeyUtils.normalizeLastModified(object.lastModified()));
        }
        if (db != null) {
            diff.setDbEtag(ObjectKeyUtils.normalizeEtag(db.getEtag()));
            diff.setDbSizeBytes(db.getSizeBytes());
            diff.setDbModifiedAt(ObjectKeyUtils.normalizeLastModified(db.getObjectModifiedAt()));
        }
        diff.setCreatedAt(now);
        diff.setUpdatedAt(now);
        diffMapper.insert(diff);
    }

    private void updateProgress(String taskId, ScanCounters counters) {
        taskMapper.updateProgress(counters.toTask(taskId));
    }

    private void ensureNotCancelled(String taskId, boolean checkDatabase) {
        if (isCancellationRequested(taskId, checkDatabase)) {
            throw new DifferenceCheckCancelledException();
        }
    }

    private boolean isCancellationRequested(String taskId, boolean checkDatabase) {
        if (cancellationRequests.contains(taskId)) {
            return true;
        }
        if (!checkDatabase) {
            return false;
        }
        ObjectStorageSyncTaskPo task = taskMapper.selectById(taskId);
        return task != null && "CANCEL_REQUESTED".equals(task.getTaskStatus());
    }

    private boolean isActive(String status) {
        return "PENDING".equals(status)
                || "RUNNING".equals(status)
                || "CANCEL_REQUESTED".equals(status);
    }

    private String message(Exception e) {
        String value = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }

    private static class ScanCounters {
        private long scanned;
        private long inSync;
        private long ossOnly;
        private long dbOnly;
        private long mismatch;

        private long inProgress;

        void increment(String type) {
            switch (type) {
                case IN_SYNC -> inSync++;
                case OSS_ONLY -> ossOnly++;
                case DB_ONLY -> dbOnly++;
                case METADATA_MISMATCH -> mismatch++;
                case IN_PROGRESS -> inProgress++;
                default -> throw new IllegalArgumentException("unknown diff type: " + type);
            }
        }

        ObjectStorageSyncTaskPo toTask(String taskId) {
            ObjectStorageSyncTaskPo task = new ObjectStorageSyncTaskPo();
            task.setId(taskId);
            task.setScannedCount(scanned);
            task.setInSyncCount(inSync);
            task.setOssOnlyCount(ossOnly);
            task.setDbOnlyCount(dbOnly);
            task.setMismatchCount(mismatch);
            task.setInProgressCount(inProgress);
            return task;
        }
    }
}
