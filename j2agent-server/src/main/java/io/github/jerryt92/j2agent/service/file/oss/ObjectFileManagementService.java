package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.service.file.oss.exception.ObjectStorageException;
import io.github.jerryt92.j2agent.service.file.oss.model.DeleteReconcileOutcome;
import io.github.jerryt92.j2agent.service.file.oss.model.DirectUploadInitResult;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectFilePage;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectFileView;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStorageObject;
import io.github.jerryt92.j2agent.service.file.oss.model.PresignedUploadCredential;
import io.github.jerryt92.j2agent.service.file.oss.model.UploadReconcileOutcome;
import io.github.jerryt92.j2agent.service.file.oss.reconcile.ObjectDeleteReconcileQueueService;
import io.github.jerryt92.j2agent.service.file.oss.reconcile.ObjectFileLockService;
import io.github.jerryt92.j2agent.service.file.oss.reconcile.ObjectUploadReconcileQueueService;
import io.github.jerryt92.j2agent.service.file.oss.util.ObjectKeyUtils;

import io.github.jerryt92.j2agent.mapper.ObjectFileMapper;
import io.github.jerryt92.j2agent.config.ObjectStorageProperties;
import io.github.jerryt92.j2agent.controller.FileManagementController;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import org.redisson.api.RLock;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

@Service
@ConditionalOnBean(ObjectStorageService.class)
public class ObjectFileManagementService {
    private static final String UPLOADING = "UPLOADING";
    private static final String READY = "READY";
    private static final String ERROR = "ERROR";
    private static final String DELETING = "DELETING";
    private static final Duration DIRECT_UPLOAD_EXPIRY = Duration.ofMinutes(15);

    private final ObjectStorageService storageService;
    private final ObjectFileMapper fileMapper;
    private final ObjectUploadReconcileQueueService reconcileQueueService;
    private final ObjectDeleteReconcileQueueService deleteReconcileQueueService;
    private final ObjectFileLockService lockService;
    private final ObjectFileReferenceService referenceService;
    private final ObjectStorageProperties storageProperties;

    public ObjectFileManagementService(
            ObjectStorageService storageService,
            ObjectFileMapper fileMapper,
            ObjectUploadReconcileQueueService reconcileQueueService,
            ObjectDeleteReconcileQueueService deleteReconcileQueueService,
            ObjectFileLockService lockService,
            ObjectFileReferenceService referenceService,
            ObjectStorageProperties storageProperties
    ) {
        this.storageService = storageService;
        this.fileMapper = fileMapper;
        this.reconcileQueueService = reconcileQueueService;
        this.deleteReconcileQueueService = deleteReconcileQueueService;
        this.lockService = lockService;
        this.referenceService = referenceService;
        this.storageProperties = storageProperties;
    }

    public ObjectFilePage list(String prefix, String keyword, String status, int offset, int limit) {
        String normalizedPrefix = ObjectKeyUtils.normalizePrefix(prefix);
        String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String normalizedStatus = status == null ? "" : status.trim();
        Map<String, ObjectFileView> directories = new LinkedHashMap<>();
        List<ObjectFileView> files = new ArrayList<>();
        for (ObjectFilePo po : fileMapper.selectByBucket(storageService.getDefaultBucket())) {
            if (!po.getObjectKey().startsWith(normalizedPrefix)
                    || (!normalizedStatus.isBlank() && !normalizedStatus.equals(po.getOperationStatus()))
                    || (!normalizedKeyword.isBlank()
                    && !po.getObjectKey().toLowerCase(Locale.ROOT).contains(normalizedKeyword))) {
                continue;
            }
            String remainder = po.getObjectKey().substring(normalizedPrefix.length());
            int slash = remainder.indexOf('/');
            if (slash >= 0) {
                String name = remainder.substring(0, slash);
                String directoryKey = normalizedPrefix + name + "/";
                directories.putIfAbsent(directoryKey, new ObjectFileView(
                        directoryKey, name, true, null, 0, null, 0, READY, null
                ));
            } else {
                files.add(toView(po, remainder));
            }
        }
        List<ObjectFileView> combined = new ArrayList<>(directories.values());
        combined.sort(Comparator.comparing(ObjectFileView::name));
        files.sort(Comparator.comparing(ObjectFileView::name));
        combined.addAll(files);
        int from = Math.min(Math.max(offset, 0), combined.size());
        int to = Math.min(from + Math.max(limit, 1), combined.size());
        return new ObjectFilePage(List.copyOf(combined.subList(from, to)), combined.size());
    }

    public ObjectFilePo upload(String prefix, MultipartFile file) {
        return upload(prefix, file.getOriginalFilename(), file);
    }

    public ObjectFilePo upload(String prefix, String storedFileName, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must not be empty");
        }
        try {
            return uploadBytes(prefix, storedFileName, file.getInputStream().readAllBytes(), file.getContentType(), file.getSize());
        } catch (IOException e) {
            throw new ObjectStorageException("Failed to read upload stream", e);
        }
    }

    public ObjectFilePo uploadBytes(String prefix, String storedFileName, byte[] content, String contentType) {
        return uploadBytes(prefix, storedFileName, content, contentType, content == null ? 0L : content.length);
    }

    public ObjectFilePo uploadBytes(String prefix, String storedFileName, byte[] content, String contentType, long sizeBytes) {
        if (content == null || content.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must not be empty");
        }
        String objectKey = ObjectKeyUtils.objectKey(prefix, storedFileName);
        String bucket = storageService.getDefaultBucket();
        String hash = ObjectKeyUtils.hash(objectKey);
        if (fileMapper.selectByKey(bucket, hash) != null || storageService.objectExists(bucket, objectKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "object already exists");
        }
        long now = System.currentTimeMillis();
        ObjectFilePo po = basePo(objectKey, hash, now);
        po.setSizeBytes(sizeBytes);
        po.setContentType(contentType);
        po.setOperationStatus(UPLOADING);
        try {
            fileMapper.insert(po);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "object already exists", e);
        }
        try {
            storageService.putObject(bucket, objectKey, new java.io.ByteArrayInputStream(content), sizeBytes, contentType);
            ObjectStorageObject metadata = storageService.getObjectMetadata(bucket, objectKey);
            ObjectFilePo ready = fromMetadata(po.getId(), metadata, now);
            fileMapper.upsert(ready);
            return ready;
        } catch (Exception e) {
            fileMapper.updateStatus(bucket, hash, ERROR, message(e), System.currentTimeMillis());
            throw new ObjectStorageException("Failed to upload " + objectKey, e);
        }
    }

    public DirectUploadInitResult initDirectUpload(
            String prefix,
            String filename,
            String contentType,
            long sizeBytes
    ) {
        if (!StringUtils.hasText(filename)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "filename must not be blank");
        }
        if (sizeBytes <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sizeBytes must be positive");
        }
        String objectKey = ObjectKeyUtils.objectKey(prefix, filename);
        String bucket = storageService.getDefaultBucket();
        String hash = ObjectKeyUtils.hash(objectKey);
        if (fileMapper.selectByKey(bucket, hash) != null || storageService.objectExists(bucket, objectKey)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "object already exists");
        }
        long now = System.currentTimeMillis();
        ObjectFilePo po = basePo(objectKey, hash, now);
        po.setSizeBytes(sizeBytes);
        po.setContentType(contentType);
        po.setOperationStatus(UPLOADING);
        try {
            fileMapper.insert(po);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "object already exists", e);
        }
        PresignedUploadCredential credential = buildDirectUploadCredential(
                objectKey,
                contentType,
                sizeBytes
        );
        reconcileQueueService.scheduleFirst(bucket, objectKey);
        return new DirectUploadInitResult(objectKey, credential);
    }

    public void writeProxiedDirectUpload(String objectKey, InputStream input, long sizeBytes, String contentType) {
        withLock(objectKey, () -> {
            writeProxiedDirectUploadUnderLock(objectKey, input, sizeBytes, contentType);
            return null;
        });
    }

    private void writeProxiedDirectUploadUnderLock(
            String objectKey,
            InputStream input,
            long sizeBytes,
            String contentType
    ) {
        if (input == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "upload body must not be empty");
        }
        if (sizeBytes <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Content-Length must be positive");
        }
        String bucket = storageService.getDefaultBucket();
        String hash = ObjectKeyUtils.hash(objectKey);
        ObjectFilePo po = fileMapper.selectByKey(bucket, hash);
        if (po == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file record not found");
        }
        if (!UPLOADING.equals(po.getOperationStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "file is not in uploading state: " + po.getOperationStatus()
            );
        }
        if (po.getSizeBytes() != null && po.getSizeBytes() > 0 && po.getSizeBytes() != sizeBytes) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "uploaded size does not match expected size");
        }
        try {
            storageService.putObject(bucket, objectKey, input, sizeBytes, contentType);
        } catch (Exception e) {
            fileMapper.updateStatus(bucket, hash, ERROR, message(e), System.currentTimeMillis());
            throw new ObjectStorageException("Failed to upload " + objectKey, e);
        }
    }

    private PresignedUploadCredential buildDirectUploadCredential(
            String objectKey,
            String contentType,
            long sizeBytes
    ) {
        if (storageProperties.getChatAttachmentDisplay()
                == ObjectStorageProperties.ChatAttachmentDisplayMode.DIRECT) {
            String bucket = storageService.getDefaultBucket();
            return storageService.generatePresignedUploadUrl(
                    bucket,
                    objectKey,
                    DIRECT_UPLOAD_EXPIRY,
                    contentType,
                    sizeBytes
            );
        }
        Map<String, String> headers = new HashMap<>();
        if (StringUtils.hasText(contentType)) {
            headers.put("Content-Type", contentType);
        }
        return new PresignedUploadCredential(
                storageService.getProvider(),
                FileManagementController.stableUploadContentUrl(objectKey),
                "PUT",
                Map.copyOf(headers),
                System.currentTimeMillis() + DIRECT_UPLOAD_EXPIRY.toMillis(),
                Map.of()
        );
    }

    public UploadReconcileOutcome reconcileDirectUpload(String objectKey) {
        return withLock(objectKey, () -> reconcileDirectUploadUnderLock(objectKey));
    }

    private UploadReconcileOutcome reconcileDirectUploadUnderLock(String objectKey) {
        String bucket = storageService.getDefaultBucket();
        String hash = ObjectKeyUtils.hash(objectKey);
        ObjectFilePo po = fileMapper.selectByKey(bucket, hash);
        if (po == null || !UPLOADING.equals(po.getOperationStatus())) {
            return UploadReconcileOutcome.SKIPPED;
        }
        if (!storageService.objectExists(bucket, objectKey)) {
            return UploadReconcileOutcome.NOT_READY;
        }
        try {
            ObjectStorageObject metadata = storageService.getObjectMetadata(bucket, objectKey);
            if (po.getSizeBytes() != null && po.getSizeBytes() > 0 && metadata.size() != po.getSizeBytes()) {
                return UploadReconcileOutcome.SIZE_MISMATCH;
            }
            ObjectFilePo ready = fromMetadata(po.getId(), metadata, po.getCreatedAt());
            fileMapper.upsert(ready);
            reconcileQueueService.clearHeartbeat(objectKey);
            return UploadReconcileOutcome.COMPLETED;
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to reconcile upload for " + objectKey, e);
        }
    }

    public void forceCleanupUpload(String objectKey) {
        withLock(objectKey, () -> {
            forceCleanupUploadUnderLock(objectKey);
            return null;
        });
    }

    private void forceCleanupUploadUnderLock(String objectKey) {
        String bucket = storageService.getDefaultBucket();
        String hash = ObjectKeyUtils.hash(objectKey);
        try {
            reconcileQueueService.clearHeartbeat(objectKey);
            if (storageService.objectExists(bucket, objectKey)) {
                storageService.removeObject(bucket, objectKey);
            }
            fileMapper.deleteByKey(bucket, hash);
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to cleanup upload for " + objectKey, e);
        }
    }

    public void touchUploadHeartbeat(String objectKey) {
        withLock(objectKey, () -> {
            touchUploadHeartbeatUnderLock(objectKey);
            return null;
        });
    }

    private void touchUploadHeartbeatUnderLock(String objectKey) {
        String bucket = storageService.getDefaultBucket();
        String hash = ObjectKeyUtils.hash(objectKey);
        ObjectFilePo po = fileMapper.selectByKey(bucket, hash);
        if (po == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file record not found");
        }
        if (!UPLOADING.equals(po.getOperationStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "file is not in uploading state: " + po.getOperationStatus()
            );
        }
        reconcileQueueService.touchHeartbeat(objectKey);
    }

    public ObjectFilePo completeDirectUpload(String objectKey) {
        return withLock(objectKey, () -> completeDirectUploadUnderLock(objectKey));
    }

    private ObjectFilePo completeDirectUploadUnderLock(String objectKey) {
        String bucket = storageService.getDefaultBucket();
        String hash = ObjectKeyUtils.hash(objectKey);
        ObjectFilePo po = fileMapper.selectByKey(bucket, hash);
        if (po == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file record not found");
        }
        if (READY.equals(po.getOperationStatus())) {
            reconcileQueueService.clearHeartbeat(objectKey);
            return po;
        }
        if (!UPLOADING.equals(po.getOperationStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "file is not waiting for upload completion: " + po.getOperationStatus()
            );
        }
        try {
            if (!storageService.objectExists(bucket, objectKey)) {
                fileMapper.updateStatus(
                        bucket,
                        hash,
                        ERROR,
                        "object not found in storage after direct upload",
                        System.currentTimeMillis()
                );
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "object not found in storage after direct upload"
                );
            }
            ObjectStorageObject metadata = storageService.getObjectMetadata(bucket, objectKey);
            if (po.getSizeBytes() != null && po.getSizeBytes() > 0 && metadata.size() != po.getSizeBytes()) {
                fileMapper.updateStatus(
                        bucket,
                        hash,
                        ERROR,
                        "uploaded size does not match expected size",
                        System.currentTimeMillis()
                );
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "uploaded size does not match expected size"
                );
            }
            ObjectFilePo ready = fromMetadata(po.getId(), metadata, po.getCreatedAt());
            fileMapper.upsert(ready);
            reconcileQueueService.clearHeartbeat(objectKey);
            return ready;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            fileMapper.updateStatus(bucket, hash, ERROR, message(e), System.currentTimeMillis());
            throw new ObjectStorageException("Failed to complete upload for " + objectKey, e);
        }
    }

    public void abortDirectUpload(String objectKey) {
        withLock(objectKey, () -> {
            abortDirectUploadUnderLock(objectKey);
            return null;
        });
    }

    private void abortDirectUploadUnderLock(String objectKey) {
        String bucket = storageService.getDefaultBucket();
        String hash = ObjectKeyUtils.hash(objectKey);
        ObjectFilePo po = fileMapper.selectByKey(bucket, hash);
        if (po == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file record not found");
        }
        if (!UPLOADING.equals(po.getOperationStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "file is not in uploading state: " + po.getOperationStatus()
            );
        }
        reconcileQueueService.markCancelled(objectKey);
        reconcileQueueService.clearHeartbeat(objectKey);
        try {
            if (storageService.objectExists(bucket, objectKey)) {
                storageService.removeObject(bucket, objectKey);
            }
            fileMapper.deleteByKey(bucket, hash);
        } catch (Exception e) {
            fileMapper.updateStatus(bucket, hash, ERROR, message(e), System.currentTimeMillis());
            throw new ObjectStorageException("Failed to abort upload for " + objectKey, e);
        }
    }

    public DeleteReconcileOutcome reconcileDelete(String objectKey) {
        return withLock(objectKey, () -> reconcileDeleteUnderLock(objectKey));
    }

    private DeleteReconcileOutcome reconcileDeleteUnderLock(String objectKey) {
        String bucket = storageService.getDefaultBucket();
        String hash = ObjectKeyUtils.hash(objectKey);
        ObjectFilePo po = fileMapper.selectByKey(bucket, hash);
        if (po == null) {
            return DeleteReconcileOutcome.COMPLETED;
        }
        if (!DELETING.equals(po.getOperationStatus()) && !ERROR.equals(po.getOperationStatus())) {
            return DeleteReconcileOutcome.SKIPPED;
        }
        try {
            if (storageService.objectExists(bucket, objectKey)) {
                storageService.removeObject(bucket, objectKey);
            }
            fileMapper.deleteByKey(bucket, hash);
            return DeleteReconcileOutcome.COMPLETED;
        } catch (Exception e) {
            throw new ObjectStorageException("Failed to reconcile delete for " + objectKey, e);
        }
    }

    public void delete(String objectKey) {
        withLock(objectKey, () -> {
            deleteUnderLock(objectKey);
            return null;
        });
    }

    private void deleteUnderLock(String objectKey) {
        String bucket = storageService.getDefaultBucket();
        String hash = ObjectKeyUtils.hash(objectKey);
        ObjectFilePo po = fileMapper.selectByKey(bucket, hash);
        if (po == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file record not found");
        }
        if (referenceService.isReferenced(po.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "file is referenced by business data");
        }
        fileMapper.updateStatus(bucket, hash, DELETING, null, System.currentTimeMillis());
        try {
            if (storageService.objectExists(bucket, objectKey)) {
                storageService.removeObject(bucket, objectKey);
            }
            fileMapper.deleteByKey(bucket, hash);
        } catch (Exception e) {
            fileMapper.updateStatus(bucket, hash, ERROR, message(e), System.currentTimeMillis());
            deleteReconcileQueueService.scheduleFirst(bucket, objectKey);
            throw new ObjectStorageException("Failed to delete " + objectKey, e);
        }
    }

    public List<String> deleteBatch(List<String> objectKeys) {
        List<String> failed = new ArrayList<>();
        if (objectKeys == null) {
            return failed;
        }
        for (String objectKey : objectKeys.stream().distinct().toList()) {
            try {
                delete(objectKey);
            } catch (RuntimeException e) {
                failed.add(objectKey);
            }
        }
        return failed;
    }

    public URL previewUrl(String objectKey) {
        return previewUrl(objectKey, Duration.ofMinutes(15));
    }

    public URL previewUrl(String objectKey, Duration ttl) {
        if (fileMapper.selectByKey(storageService.getDefaultBucket(), ObjectKeyUtils.hash(objectKey)) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file record not found");
        }
        return storageService.generatePresignedUrl(objectKey, ttl);
    }

    public ObjectFilePo requireReadyObjectFile(String objectKey) {
        String bucket = storageService.getDefaultBucket();
        ObjectFilePo po = fileMapper.selectByKey(bucket, ObjectKeyUtils.hash(objectKey));
        if (po == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "file record not found");
        }
        if (!READY.equals(po.getOperationStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file is not ready");
        }
        return po;
    }

    public InputStream openObjectStream(String objectKey) {
        requireReadyObjectFile(objectKey);
        return storageService.getObject(objectKey);
    }

    ObjectFilePo fromMetadata(String existingId, ObjectStorageObject metadata, long createdAt) {
        ObjectFilePo po = basePo(metadata.objectName(), ObjectKeyUtils.hash(metadata.objectName()), createdAt);
        if (existingId != null) {
            po.setId(existingId);
        }
        po.setEtag(ObjectKeyUtils.normalizeEtag(metadata.etag()));
        po.setSizeBytes(metadata.size());
        po.setContentType(metadata.contentType());
        po.setObjectModifiedAt(ObjectKeyUtils.normalizeLastModified(metadata.lastModified()));
        po.setOperationStatus(READY);
        return po;
    }

    private <T> T withLock(String objectKey, Supplier<T> action) {
        RLock lock = lockService.lock(objectKey);
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private ObjectFilePo basePo(String objectKey, String hash, long createdAt) {
        ObjectFilePo po = new ObjectFilePo();
        po.setId(UUIDv7Utils.randomUUIDv7());
        po.setProvider(storageService.getProvider());
        po.setBucketName(storageService.getDefaultBucket());
        po.setObjectKey(objectKey);
        po.setObjectKeyHash(hash);
        po.setSizeBytes(0L);
        po.setObjectModifiedAt(0L);
        po.setCreatedAt(createdAt);
        po.setUpdatedAt(System.currentTimeMillis());
        return po;
    }

    private ObjectFileView toView(ObjectFilePo po, String name) {
        return new ObjectFileView(
                po.getObjectKey(),
                name,
                false,
                po.getEtag(),
                po.getSizeBytes(),
                po.getContentType(),
                po.getObjectModifiedAt(),
                po.getOperationStatus(),
                po.getLastError()
        );
    }

    private String message(Exception e) {
        String value = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return value.length() <= 2000 ? value : value.substring(0, 2000);
    }
}
