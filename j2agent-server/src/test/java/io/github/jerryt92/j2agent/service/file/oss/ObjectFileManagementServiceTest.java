package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.service.file.oss.exception.ObjectStorageException;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectFilePage;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectStorageObject;
import io.github.jerryt92.j2agent.service.file.oss.model.PresignedUploadCredential;
import io.github.jerryt92.j2agent.service.file.oss.model.UploadReconcileOutcome;
import io.github.jerryt92.j2agent.service.file.oss.reconcile.ObjectDeleteReconcileQueueService;
import io.github.jerryt92.j2agent.service.file.oss.reconcile.ObjectFileLockService;
import io.github.jerryt92.j2agent.service.file.oss.reconcile.ObjectUploadReconcileQueueService;
import io.github.jerryt92.j2agent.service.file.oss.util.ObjectKeyUtils;

import io.github.jerryt92.j2agent.mapper.ObjectFileMapper;
import io.github.jerryt92.j2agent.config.storage.ObjectStorageProperties;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectFileManagementServiceTest {
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final ObjectFileMapper mapper = mock(ObjectFileMapper.class);
    private final ObjectUploadReconcileQueueService reconcileQueue = mock(ObjectUploadReconcileQueueService.class);
    private final ObjectDeleteReconcileQueueService deleteReconcileQueue =
            mock(ObjectDeleteReconcileQueueService.class);
    private final ObjectFileLockService lockService = mock(ObjectFileLockService.class);
    private final ObjectFileReferenceService referenceService = mock(ObjectFileReferenceService.class);
    private final ObjectStorageProperties storageProperties = new ObjectStorageProperties();
    private ObjectFileManagementService service;

    @BeforeEach
    void setUp() {
        RLock lock = mock(RLock.class);
        when(lockService.lock(any())).thenReturn(lock);
        storageProperties.setChatAttachmentDisplay(
                ObjectStorageProperties.ChatAttachmentDisplayMode.DIRECT);
        service = new ObjectFileManagementService(
                storage, mapper, reconcileQueue, deleteReconcileQueue, lockService, referenceService,
                storageProperties
        );
    }

    @Test
    void shouldBuildVirtualDirectories() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(mapper.selectByBucket("bucket")).thenReturn(List.of(
                file("docs/a.txt"),
                file("docs/sub/b.txt"),
                file("root.txt")
        ));

        ObjectFilePage root = service.list("", null, null, 0, 20);
        assertEquals(2, root.total());
        assertTrue(root.items().stream().anyMatch(item -> item.directory() && "docs/".equals(item.objectKey())));
    }

    @Test
    void shouldRejectExistingObjectOnUpload() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(storage.objectExists("bucket", "a.txt")).thenReturn(true);

        assertThrows(ResponseStatusException.class, () -> service.upload(
                "",
                new MockMultipartFile("file", "a.txt", "text/plain", "a".getBytes())
        ));
    }

    @Test
    void shouldPersistErrorStatusWhenUploadFails() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(storage.getProvider()).thenReturn("MinIO");
        when(storage.objectExists("bucket", "a.txt")).thenReturn(false);
        doThrow(new ObjectStorageException("failed", new RuntimeException()))
                .when(storage).putObject(eq("bucket"), eq("a.txt"), any(), eq(1L), eq("text/plain"));

        assertThrows(ObjectStorageException.class, () -> service.upload(
                "",
                new MockMultipartFile("file", "a.txt", "text/plain", "a".getBytes())
        ));
        verify(mapper).updateStatus(
                eq("bucket"), eq(ObjectKeyUtils.hash("a.txt")), eq("ERROR"), any(), any(Long.class)
        );
    }

    @Test
    void shouldScheduleDeleteReconcileWhenDeleteFails() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(mapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(file("a.txt"));
        when(storage.objectExists("bucket", "a.txt")).thenReturn(true);
        doThrow(new ObjectStorageException("failed", new RuntimeException()))
                .when(storage).removeObject("bucket", "a.txt");

        assertThrows(ObjectStorageException.class, () -> service.delete("a.txt"));
        verify(mapper).updateStatus(
                eq("bucket"), eq(ObjectKeyUtils.hash("a.txt")), eq("ERROR"), any(), any(Long.class)
        );
        verify(deleteReconcileQueue).scheduleFirst("bucket", "a.txt");
    }

    @Test
    void shouldRejectDeleteWhenFileIsReferenced() {
        ObjectFilePo file = file("a.txt");
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(mapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(file);
        when(referenceService.isReferenced(file.getId())).thenReturn(true);

        assertThrows(ResponseStatusException.class, () -> service.delete("a.txt"));

        verify(storage, never()).removeObject(any(), any());
        verify(mapper, never()).deleteByKey(any(), any());
    }

    @Test
    void shouldRejectExistingObjectOnDirectUploadInit() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(storage.objectExists("bucket", "a.txt")).thenReturn(true);

        assertThrows(ResponseStatusException.class, () -> service.initDirectUpload(
                "",
                "a.txt",
                "text/plain",
                1L
        ));
    }

    @Test
    void shouldReturnUploadCredentialOnDirectUploadInit() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(storage.getProvider()).thenReturn("MinIO");
        when(storage.objectExists("bucket", "a.txt")).thenReturn(false);
        when(storage.generatePresignedUploadUrl(
                eq("bucket"),
                eq("a.txt"),
                any(Duration.class),
                eq("text/plain"),
                eq(1L)
        )).thenReturn(new PresignedUploadCredential(
                "MinIO",
                "https://example.com/a.txt",
                "PUT",
                Map.of("Content-Type", "text/plain"),
                System.currentTimeMillis() + 60_000,
                Map.of()
        ));

        var result = service.initDirectUpload("", "a.txt", "text/plain", 1L);
        assertEquals("a.txt", result.objectKey());
        assertEquals("PUT", result.credential().method());
        verify(mapper).insert(any(ObjectFilePo.class));
        verify(reconcileQueue).scheduleFirst("bucket", "a.txt");
    }

    @Test
    void shouldReturnProxyUploadUrlOnDirectUploadInitWhenDisplayModeIsProxy() {
        storageProperties.setChatAttachmentDisplay(
                ObjectStorageProperties.ChatAttachmentDisplayMode.PROXY);
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(storage.getProvider()).thenReturn("MinIO");
        when(storage.objectExists("bucket", "a.txt")).thenReturn(false);

        var result = service.initDirectUpload("", "a.txt", "text/plain", 1L);

        assertEquals("a.txt", result.objectKey());
        assertEquals("PUT", result.credential().method());
        assertTrue(result.credential().uploadUrl().contains("/files/upload/content?object-key="));
        verify(storage, never()).generatePresignedUploadUrl(
                any(), any(), any(Duration.class), any(), any(Long.class));
    }

    @Test
    void shouldWriteProxiedDirectUploadToStorage() throws Exception {
        ObjectFilePo uploading = file("a.txt");
        uploading.setOperationStatus("UPLOADING");
        uploading.setSizeBytes(5L);
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(mapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(uploading);

        service.writeProxiedDirectUpload(
                "a.txt",
                new java.io.ByteArrayInputStream("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                5L,
                "text/plain"
        );

        verify(storage).putObject(eq("bucket"), eq("a.txt"), any(), eq(5L), eq("text/plain"));
    }

    @Test
    void shouldCompleteDirectUploadWhenObjectExists() {
        ObjectFilePo uploading = file("a.txt");
        uploading.setOperationStatus("UPLOADING");
        uploading.setSizeBytes(1L);
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(mapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(uploading);
        when(storage.objectExists("bucket", "a.txt")).thenReturn(true);
        when(storage.getObjectMetadata("bucket", "a.txt")).thenReturn(
                new ObjectStorageObject("a.txt", "etag", 1L, "text/plain", 100L)
        );

        ObjectFilePo ready = service.completeDirectUpload("a.txt");
        assertEquals("READY", ready.getOperationStatus());
        verify(mapper).upsert(any(ObjectFilePo.class));
        verify(reconcileQueue).clearHeartbeat("a.txt");
    }

    @Test
    void shouldMarkErrorWhenDirectUploadCompleteWithoutObject() {
        ObjectFilePo uploading = file("a.txt");
        uploading.setOperationStatus("UPLOADING");
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(mapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(uploading);
        when(storage.objectExists("bucket", "a.txt")).thenReturn(false);

        assertThrows(ResponseStatusException.class, () -> service.completeDirectUpload("a.txt"));
        verify(mapper).updateStatus(
                eq("bucket"), eq(ObjectKeyUtils.hash("a.txt")), eq("ERROR"), any(), any(Long.class)
        );
    }

    @Test
    void shouldAbortDirectUploadAndDeleteRecord() {
        ObjectFilePo uploading = file("a.txt");
        uploading.setOperationStatus("UPLOADING");
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(mapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(uploading);
        when(storage.objectExists("bucket", "a.txt")).thenReturn(true);

        service.abortDirectUpload("a.txt");
        verify(reconcileQueue).markCancelled("a.txt");
        verify(reconcileQueue).clearHeartbeat("a.txt");
        verify(storage).removeObject("bucket", "a.txt");
        verify(mapper).deleteByKey("bucket", ObjectKeyUtils.hash("a.txt"));
    }

    @Test
    void shouldReconcileWithoutMarkingErrorWhenObjectMissing() {
        ObjectFilePo uploading = file("a.txt");
        uploading.setOperationStatus("UPLOADING");
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(mapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(uploading);
        when(storage.objectExists("bucket", "a.txt")).thenReturn(false);

        UploadReconcileOutcome outcome = service.reconcileDirectUpload("a.txt");
        assertEquals(UploadReconcileOutcome.NOT_READY, outcome);
        verify(mapper, never()).updateStatus(any(), any(), eq("ERROR"), any(), any(Long.class));
    }

    @Test
    void shouldForceCleanupUpload() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(storage.objectExists("bucket", "a.txt")).thenReturn(true);

        service.forceCleanupUpload("a.txt");

        verify(storage).removeObject("bucket", "a.txt");
        verify(mapper).deleteByKey("bucket", ObjectKeyUtils.hash("a.txt"));
    }

    @Test
    void shouldReturnExistingRecordWhenDirectUploadAlreadyReady() {
        ObjectFilePo ready = file("a.txt");
        ready.setOperationStatus("READY");
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(mapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(ready);

        ObjectFilePo result = service.completeDirectUpload("a.txt");
        assertEquals("READY", result.getOperationStatus());
    }

    @Test
    void shouldTouchHeartbeatWhenUploading() {
        ObjectFilePo uploading = file("a.txt");
        uploading.setOperationStatus("UPLOADING");
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(mapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(uploading);

        service.touchUploadHeartbeat("a.txt");

        verify(reconcileQueue).touchHeartbeat("a.txt");
    }

    @Test
    void shouldRejectHeartbeatWhenNotUploading() {
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(mapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(file("a.txt"));

        assertThrows(ResponseStatusException.class, () -> service.touchUploadHeartbeat("a.txt"));
        verify(reconcileQueue, never()).touchHeartbeat(any());
    }

    @Test
    void shouldReconcileDeleteWhenRecordIsDeleting() {
        ObjectFilePo deleting = file("a.txt");
        deleting.setOperationStatus("DELETING");
        when(storage.getDefaultBucket()).thenReturn("bucket");
        when(mapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(deleting);
        when(storage.objectExists("bucket", "a.txt")).thenReturn(true);

        var outcome = service.reconcileDelete("a.txt");

        assertEquals(io.github.jerryt92.j2agent.service.file.oss.model.DeleteReconcileOutcome.COMPLETED, outcome);
        verify(storage).removeObject("bucket", "a.txt");
        verify(mapper).deleteByKey("bucket", ObjectKeyUtils.hash("a.txt"));
    }

    private ObjectFilePo file(String key) {
        ObjectFilePo po = new ObjectFilePo();
        po.setId(key);
        po.setObjectKey(key);
        po.setObjectKeyHash(ObjectKeyUtils.hash(key));
        po.setSizeBytes(1L);
        po.setObjectModifiedAt(1L);
        po.setOperationStatus("READY");
        po.setCreatedAt(1L);
        return po;
    }
}
