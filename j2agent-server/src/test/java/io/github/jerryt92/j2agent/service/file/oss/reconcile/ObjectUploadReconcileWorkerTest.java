package io.github.jerryt92.j2agent.service.file.oss.reconcile;

import io.github.jerryt92.j2agent.config.ObjectStorageProperties;
import io.github.jerryt92.j2agent.mapper.ObjectFileMapper;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import io.github.jerryt92.j2agent.service.file.oss.ObjectFileManagementService;
import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectUploadReconcileTask;
import io.github.jerryt92.j2agent.service.file.oss.model.UploadReconcileOutcome;
import io.github.jerryt92.j2agent.service.file.oss.util.ObjectKeyUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ObjectUploadReconcileWorkerTest {
    @Mock
    private ObjectUploadReconcileQueueService queueService;
    @Mock
    private ObjectFileManagementService fileService;
    @Mock
    private ObjectStorageService storageService;
    @Mock
    private ObjectFileMapper fileMapper;
    @Mock
    private ObjectFileLockService lockService;

    private ObjectStorageProperties properties;
    private ObjectUploadReconcileWorker worker;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        properties.getUpload().getReconcile().setMaxAttempts(10);
        properties.getUpload().getReconcile().setRetryDelaySeconds(30);
        properties.getUpload().getReconcile().setMaxTotalSeconds(300);
        worker = new ObjectUploadReconcileWorker(
                queueService,
                fileService,
                storageService,
                fileMapper,
                lockService,
                properties
        );
        RLock lock = mock(RLock.class);
        when(lockService.lock(any())).thenReturn(lock);
    }

    @Test
    void shouldStopWhenCancelled() {
        ObjectUploadReconcileTask task = new ObjectUploadReconcileTask("bucket", "a.txt", 1);
        when(queueService.isCancelled("a.txt")).thenReturn(true);

        worker.process(task);

        verify(queueService).clearCancelled("a.txt");
        verify(fileService, never()).reconcileDirectUpload(any());
        verify(fileService, never()).forceCleanupUpload(any());
    }

    @Test
    void shouldCompleteWhenObjectExists() {
        ObjectUploadReconcileTask task = new ObjectUploadReconcileTask("bucket", "a.txt", 1);
        ObjectFilePo uploading = uploading("a.txt");
        when(queueService.isCancelled("a.txt")).thenReturn(false);
        when(fileMapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(uploading);
        when(storageService.objectExists("bucket", "a.txt")).thenReturn(true);
        when(fileService.reconcileDirectUpload("a.txt")).thenReturn(UploadReconcileOutcome.COMPLETED);

        worker.process(task);

        verify(fileService).reconcileDirectUpload("a.txt");
        verify(queueService, never()).schedule(any(), anyInt());
    }

    @Test
    void shouldRescheduleAttemptOneWhenHeartbeatActive() {
        ObjectUploadReconcileTask task = new ObjectUploadReconcileTask("bucket", "a.txt", 20);
        ObjectFilePo uploading = uploading("a.txt");
        when(queueService.isCancelled("a.txt")).thenReturn(false);
        when(fileMapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(uploading);
        when(storageService.objectExists("bucket", "a.txt")).thenReturn(false);
        when(queueService.isUploadInProgress("a.txt")).thenReturn(true);
        when(queueService.inProgressDelaySeconds()).thenReturn(10);

        worker.process(task);

        ArgumentCaptor<ObjectUploadReconcileTask> captor = ArgumentCaptor.forClass(ObjectUploadReconcileTask.class);
        verify(queueService).schedule(captor.capture(), eq(10));
        assertEquals(1, captor.getValue().attempt());
        verify(fileService, never()).forceCleanupUpload(any());
    }

    @Test
    void shouldRequeueWhenObjectNotReady() {
        ObjectUploadReconcileTask task = new ObjectUploadReconcileTask("bucket", "a.txt", 3);
        ObjectFilePo uploading = uploading("a.txt");
        when(queueService.isCancelled("a.txt")).thenReturn(false);
        when(fileMapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(uploading);
        when(storageService.objectExists("bucket", "a.txt")).thenReturn(false);
        when(queueService.isUploadInProgress("a.txt")).thenReturn(false);

        worker.process(task);

        ArgumentCaptor<ObjectUploadReconcileTask> captor = ArgumentCaptor.forClass(ObjectUploadReconcileTask.class);
        verify(queueService).schedule(captor.capture(), eq(30));
        assertEquals(4, captor.getValue().attempt());
    }

    @Test
    void shouldCleanupWhenMaxAttemptsReached() {
        ObjectUploadReconcileTask task = new ObjectUploadReconcileTask("bucket", "a.txt", 10);
        ObjectFilePo uploading = uploading("a.txt");
        when(queueService.isCancelled("a.txt")).thenReturn(false);
        when(fileMapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(uploading);
        when(storageService.objectExists("bucket", "a.txt")).thenReturn(false);
        when(queueService.isUploadInProgress("a.txt")).thenReturn(false);

        worker.process(task);

        verify(fileService).forceCleanupUpload("a.txt");
        verify(queueService, never()).schedule(any(), anyInt());
    }

    @Test
    void shouldStopWhenRecordIsNotUploading() {
        ObjectUploadReconcileTask task = new ObjectUploadReconcileTask("bucket", "a.txt", 1);
        ObjectFilePo ready = uploading("a.txt");
        ready.setOperationStatus("READY");
        when(queueService.isCancelled("a.txt")).thenReturn(false);
        when(fileMapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(ready);

        worker.process(task);

        verify(fileService, never()).reconcileDirectUpload(any());
        verify(queueService, never()).schedule(any(), anyInt());
    }

    private ObjectFilePo uploading(String key) {
        ObjectFilePo po = new ObjectFilePo();
        po.setObjectKey(key);
        po.setObjectKeyHash(ObjectKeyUtils.hash(key));
        po.setOperationStatus("UPLOADING");
        return po;
    }
}
