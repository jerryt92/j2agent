package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.service.file.oss.ObjectFileManagementService;
import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageSyncService;
import io.github.jerryt92.j2agent.service.file.oss.model.DirectUploadInitResult;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectFilePage;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectFileView;
import io.github.jerryt92.j2agent.service.file.oss.model.PresignedUploadCredential;

import io.github.jerryt92.j2agent.config.annotation.RequiredRole;
import io.github.jerryt92.j2agent.model.ObjectBatchResultDto;
import io.github.jerryt92.j2agent.model.ObjectFileBatchDeleteRequest;
import io.github.jerryt92.j2agent.model.ObjectFileItemDto;
import io.github.jerryt92.j2agent.model.ObjectFileListDto;
import io.github.jerryt92.j2agent.model.ObjectFileUploadAbortRequest;
import io.github.jerryt92.j2agent.model.ObjectFileUploadCompleteRequest;
import io.github.jerryt92.j2agent.model.ObjectFileUploadHeartbeatRequest;
import io.github.jerryt92.j2agent.model.ObjectFileUploadInitDto;
import io.github.jerryt92.j2agent.model.ObjectFileUploadInitRequest;
import io.github.jerryt92.j2agent.model.ObjectFileUrlDto;
import io.github.jerryt92.j2agent.model.ObjectStorageResolveRequest;
import io.github.jerryt92.j2agent.model.ObjectStorageSyncDiffDto;
import io.github.jerryt92.j2agent.model.ObjectStorageSyncDiffListDto;
import io.github.jerryt92.j2agent.model.ObjectStorageSyncTaskDto;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import io.github.jerryt92.j2agent.model.po.ObjectStorageSyncDiffPo;
import io.github.jerryt92.j2agent.model.po.ObjectStorageSyncTaskPo;
import io.github.jerryt92.j2agent.server.api.FileManagementApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredRole(RequiredRole.ADMIN)
@ConditionalOnBean(ObjectStorageService.class)
public class FileManagementController implements FileManagementApi {
    private final ObjectFileManagementService fileService;
    private final ObjectStorageSyncService syncService;

    public FileManagementController(
            ObjectFileManagementService fileService,
            ObjectStorageSyncService syncService
    ) {
        this.fileService = fileService;
        this.syncService = syncService;
    }

    @Override
    public ResponseEntity<ObjectFileListDto> getObjectFiles(
            String prefix,
            String keyword,
            String status,
            Integer offset,
            Integer limit
    ) {
        ObjectFilePage page = fileService.list(prefix, keyword, status, value(offset, 0), value(limit, 20));
        ObjectFileListDto response = new ObjectFileListDto();
        response.setData(page.items().stream().map(this::toDto).toList());
        response.setTotal(page.total());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<ObjectFileItemDto> uploadObjectFile(MultipartFile file, String prefix) {
        return ResponseEntity.ok(toDto(fileService.upload(prefix, file)));
    }

    @Override
    public ResponseEntity<Void> deleteObjectFile(String objectKey) {
        fileService.delete(objectKey);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<ObjectBatchResultDto> deleteObjectFiles(ObjectFileBatchDeleteRequest request) {
        return ResponseEntity.ok(batchResult(fileService.deleteBatch(request.getObjectKeys())));
    }

    @Override
    public ResponseEntity<ObjectFileUrlDto> getObjectFilePreviewUrl(String objectKey) {
        ObjectFileUrlDto result = new ObjectFileUrlDto();
        result.setUrl(fileService.previewUrl(objectKey).toString());
        return ResponseEntity.ok(result);
    }

    @Override
    public ResponseEntity<ObjectFileUploadInitDto> initObjectFileDirectUpload(
            ObjectFileUploadInitRequest request
    ) {
        DirectUploadInitResult result = fileService.initDirectUpload(
                request.getPrefix(),
                request.getFilename(),
                request.getContentType(),
                request.getSizeBytes()
        );
        return ResponseEntity.ok(toUploadInitDto(result));
    }

    @Override
    public ResponseEntity<ObjectFileItemDto> completeObjectFileDirectUpload(
            ObjectFileUploadCompleteRequest request
    ) {
        return ResponseEntity.ok(toDto(fileService.completeDirectUpload(request.getObjectKey())));
    }

    @Override
    public ResponseEntity<Void> abortObjectFileDirectUpload(ObjectFileUploadAbortRequest request) {
        fileService.abortDirectUpload(request.getObjectKey());
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> touchObjectFileUploadHeartbeat(ObjectFileUploadHeartbeatRequest request) {
        fileService.touchUploadHeartbeat(request.getObjectKey());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ObjectStorageSyncTaskDto> createObjectStorageScanTask() {
        return ResponseEntity.ok(toDto(syncService.startScan()));
    }

    @Override
    public ResponseEntity<ObjectStorageSyncTaskDto> getObjectStorageScanTask(String taskId) {
        return ResponseEntity.ok(toDto(syncService.getTask(taskId)));
    }

    @Override
    public ResponseEntity<ObjectStorageSyncTaskDto> getLatestObjectStorageDifferenceCheck() {
        return ResponseEntity.ok(toDto(syncService.getLatestSuccessfulTask()));
    }

    @Override
    public ResponseEntity<ObjectStorageSyncTaskDto> cancelObjectStorageDifferenceCheck(String taskId) {
        return ResponseEntity.ok(toDto(syncService.cancel(taskId)));
    }

    @Override
    public ResponseEntity<ObjectStorageSyncDiffListDto> getObjectStorageScanDiffs(
            String taskId,
            String diffType,
            String resolutionStatus,
            Integer offset,
            Integer limit
    ) {
        int normalizedOffset = value(offset, 0);
        int normalizedLimit = value(limit, 20);
        ObjectStorageSyncDiffListDto response = new ObjectStorageSyncDiffListDto();
        response.setData(syncService.listDiffs(
                taskId, diffType, resolutionStatus, normalizedOffset, normalizedLimit
        ).stream().map(this::toDto).toList());
        response.setTotal(syncService.countDiffs(taskId, diffType, resolutionStatus));
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<ObjectBatchResultDto> resolveObjectStorageDiffs(ObjectStorageResolveRequest request) {
        return ResponseEntity.ok(batchResult(syncService.resolve(request.getDiffIds(), request.getAction())));
    }

    private ObjectFileItemDto toDto(ObjectFileView view) {
        ObjectFileItemDto dto = new ObjectFileItemDto();
        dto.setObjectKey(view.objectKey());
        dto.setName(view.name());
        dto.setDirectory(view.directory());
        dto.setEtag(view.etag());
        dto.setSize(view.size());
        dto.setContentType(view.contentType());
        dto.setLastModified(view.lastModified());
        dto.setOperationStatus(view.operationStatus());
        dto.setLastError(view.lastError());
        return dto;
    }

    private ObjectFileItemDto toDto(ObjectFilePo po) {
        String name = po.getObjectKey().substring(po.getObjectKey().lastIndexOf('/') + 1);
        return toDto(new ObjectFileView(
                po.getObjectKey(),
                name,
                false,
                po.getEtag(),
                po.getSizeBytes(),
                po.getContentType(),
                po.getObjectModifiedAt(),
                po.getOperationStatus(),
                po.getLastError()
        ));
    }

    private ObjectFileUploadInitDto toUploadInitDto(DirectUploadInitResult result) {
        PresignedUploadCredential credential = result.credential();
        ObjectFileUploadInitDto dto = new ObjectFileUploadInitDto();
        dto.setObjectKey(result.objectKey());
        dto.setProvider(credential.provider());
        dto.setUploadUrl(credential.uploadUrl());
        dto.setMethod(credential.method());
        dto.setHeaders(credential.headers());
        dto.setExpiresAt(credential.expiresAt());
        dto.setProviderExtras(credential.providerExtras());
        return dto;
    }

    private ObjectStorageSyncTaskDto toDto(ObjectStorageSyncTaskPo po) {
        ObjectStorageSyncTaskDto dto = new ObjectStorageSyncTaskDto();
        dto.setId(po.getId());
        dto.setBucket(po.getBucketName());
        dto.setProvider(po.getProvider());
        dto.setStatus(po.getTaskStatus());
        dto.setScannedCount(po.getScannedCount());
        dto.setInSyncCount(po.getInSyncCount());
        dto.setOssOnlyCount(po.getOssOnlyCount());
        dto.setDbOnlyCount(po.getDbOnlyCount());
        dto.setMismatchCount(po.getMismatchCount());
        dto.setInProgressCount(po.getInProgressCount());
        dto.setErrorMessage(po.getErrorMessage());
        dto.setCreatedAt(po.getCreatedAt());
        dto.setStartedAt(po.getStartedAt());
        dto.setCompletedAt(po.getCompletedAt());
        return dto;
    }

    private ObjectStorageSyncDiffDto toDto(ObjectStorageSyncDiffPo po) {
        ObjectStorageSyncDiffDto dto = new ObjectStorageSyncDiffDto();
        dto.setId(po.getId());
        dto.setObjectKey(po.getObjectKey());
        dto.setDiffType(po.getDiffType());
        dto.setResolutionStatus(po.getResolutionStatus());
        dto.setOssEtag(po.getOssEtag());
        dto.setOssSize(po.getOssSizeBytes());
        dto.setOssModifiedAt(po.getOssModifiedAt());
        dto.setDbEtag(po.getDbEtag());
        dto.setDbSize(po.getDbSizeBytes());
        dto.setDbModifiedAt(po.getDbModifiedAt());
        dto.setResolutionAction(po.getResolutionAction());
        dto.setResolutionError(po.getResolutionError());
        return dto;
    }

    private ObjectBatchResultDto batchResult(List<String> failedIds) {
        ObjectBatchResultDto result = new ObjectBatchResultDto();
        result.setSuccess(failedIds.isEmpty());
        result.setFailedIds(failedIds);
        return result;
    }

    private int value(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
