package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.mapper.ObjectStorageSyncDiffMapper;
import io.github.jerryt92.j2agent.mapper.ObjectStorageSyncTaskMapper;
import io.github.jerryt92.j2agent.model.po.ObjectStorageSyncTaskPo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ObjectStorageDifferenceSnapshotService {
    private final ObjectStorageSyncDiffMapper diffMapper;
    private final ObjectStorageSyncTaskMapper taskMapper;

    public ObjectStorageDifferenceSnapshotService(
            ObjectStorageSyncDiffMapper diffMapper,
            ObjectStorageSyncTaskMapper taskMapper
    ) {
        this.diffMapper = diffMapper;
        this.taskMapper = taskMapper;
    }

    @Transactional
    public boolean complete(String bucket, ObjectStorageSyncTaskPo task) {
        if (taskMapper.markSuccess(task) == 0) {
            return false;
        }
        diffMapper.deleteByBucketExceptTask(bucket, task.getId());
        return true;
    }

    @Transactional
    public void fail(String taskId, String errorMessage, long completedAt) {
        diffMapper.deleteByTask(taskId);
        taskMapper.markFailed(taskId, errorMessage, completedAt);
    }

    @Transactional
    public void cancel(String taskId, long completedAt) {
        diffMapper.deleteByTask(taskId);
        taskMapper.markCancelled(taskId, completedAt);
    }
}
