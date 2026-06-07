package io.github.jerryt92.j2agent.service.file.oss.reconcile;

import io.github.jerryt92.j2agent.config.ObjectStorageProperties;
import io.github.jerryt92.j2agent.mapper.ObjectFileMapper;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import io.github.jerryt92.j2agent.service.file.oss.ObjectFileManagementService;
import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.model.DeleteReconcileOutcome;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectDeleteReconcileTask;
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
class ObjectDeleteReconcileWorkerTest {
    @Mock
    private ObjectDeleteReconcileQueueService queueService;
    @Mock
    private ObjectFileManagementService fileService;
    @Mock
    private ObjectStorageService storageService;
    @Mock
    private ObjectFileMapper fileMapper;
    @Mock
    private ObjectFileLockService lockService;

    private ObjectStorageProperties properties;
    private ObjectDeleteReconcileWorker worker;

    @BeforeEach
    void setUp() {
        properties = new ObjectStorageProperties();
        properties.getDelete().getReconcile().setMaxAttempts(20);
        properties.getDelete().getReconcile().setInitialDelaySeconds(10);
        properties.getDelete().getReconcile().setMaxDelaySeconds(300);
        worker = new ObjectDeleteReconcileWorker(
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
    void shouldCompleteWhenDeleteReconcileSucceeds() {
        ObjectDeleteReconcileTask task = new ObjectDeleteReconcileTask("bucket", "a.txt", 1);
        ObjectFilePo deleting = deleting("a.txt");
        when(fileMapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(deleting);
        when(fileService.reconcileDelete("a.txt")).thenReturn(DeleteReconcileOutcome.COMPLETED);

        worker.process(task);

        verify(fileService).reconcileDelete("a.txt");
        verify(queueService, never()).schedule(any(), anyInt());
    }

    @Test
    void shouldRequeueWhenDeleteReconcileFails() {
        ObjectDeleteReconcileTask task = new ObjectDeleteReconcileTask("bucket", "a.txt", 3);
        ObjectFilePo deleting = deleting("a.txt");
        when(fileMapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(deleting);
        when(fileService.reconcileDelete("a.txt")).thenThrow(new RuntimeException("failed"));

        worker.process(task);

        ArgumentCaptor<ObjectDeleteReconcileTask> captor = ArgumentCaptor.forClass(ObjectDeleteReconcileTask.class);
        verify(queueService).schedule(captor.capture(), eq(80));
        assertEquals(4, captor.getValue().attempt());
    }

    @Test
    void shouldStopWhenRecordIsReady() {
        ObjectDeleteReconcileTask task = new ObjectDeleteReconcileTask("bucket", "a.txt", 1);
        ObjectFilePo ready = deleting("a.txt");
        ready.setOperationStatus("READY");
        when(fileMapper.selectByKey("bucket", ObjectKeyUtils.hash("a.txt"))).thenReturn(ready);

        worker.process(task);

        verify(fileService, never()).reconcileDelete(any());
        verify(queueService, never()).schedule(any(), anyInt());
    }

    private ObjectFilePo deleting(String key) {
        ObjectFilePo po = new ObjectFilePo();
        po.setObjectKey(key);
        po.setObjectKeyHash(ObjectKeyUtils.hash(key));
        po.setOperationStatus("DELETING");
        return po;
    }
}
