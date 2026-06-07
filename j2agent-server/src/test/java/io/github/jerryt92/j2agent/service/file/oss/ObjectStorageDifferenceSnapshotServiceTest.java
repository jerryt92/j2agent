package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.mapper.ObjectStorageSyncDiffMapper;
import io.github.jerryt92.j2agent.mapper.ObjectStorageSyncTaskMapper;
import io.github.jerryt92.j2agent.model.po.ObjectStorageSyncTaskPo;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObjectStorageDifferenceSnapshotServiceTest {
    private final ObjectStorageSyncDiffMapper diffMapper = mock(ObjectStorageSyncDiffMapper.class);
    private final ObjectStorageSyncTaskMapper taskMapper = mock(ObjectStorageSyncTaskMapper.class);
    private final ObjectStorageDifferenceSnapshotService service =
            new ObjectStorageDifferenceSnapshotService(diffMapper, taskMapper);

    @Test
    void shouldReplacePreviousSnapshotAfterTaskCompletes() {
        ObjectStorageSyncTaskPo task = new ObjectStorageSyncTaskPo();
        task.setId("new-task");
        when(taskMapper.markSuccess(task)).thenReturn(1);

        assertTrue(service.complete("bucket", task));

        InOrder order = inOrder(taskMapper, diffMapper);
        order.verify(taskMapper).markSuccess(task);
        order.verify(diffMapper).deleteByBucketExceptTask("bucket", "new-task");
    }

    @Test
    void shouldNotReplaceSnapshotWhenCancellationWonTheRace() {
        ObjectStorageSyncTaskPo task = new ObjectStorageSyncTaskPo();
        task.setId("cancelled-task");
        when(taskMapper.markSuccess(task)).thenReturn(0);

        assertFalse(service.complete("bucket", task));
        verify(diffMapper, never()).deleteByBucketExceptTask("bucket", "cancelled-task");
    }
}
