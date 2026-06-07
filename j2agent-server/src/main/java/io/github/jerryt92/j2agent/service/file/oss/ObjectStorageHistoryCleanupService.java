package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.config.ObjectStorageProperties;
import io.github.jerryt92.j2agent.mapper.ObjectStorageSyncDiffMapper;
import io.github.jerryt92.j2agent.mapper.ObjectStorageSyncTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Service
@ConditionalOnBean(ObjectStorageService.class)
public class ObjectStorageHistoryCleanupService {
    private static final Logger log = LoggerFactory.getLogger(ObjectStorageHistoryCleanupService.class);

    private final ObjectStorageProperties properties;
    private final ObjectStorageSyncDiffMapper diffMapper;
    private final ObjectStorageSyncTaskMapper taskMapper;

    public ObjectStorageHistoryCleanupService(
            ObjectStorageProperties properties,
            ObjectStorageSyncDiffMapper diffMapper,
            ObjectStorageSyncTaskMapper taskMapper
    ) {
        this.properties = properties;
        this.diffMapper = diffMapper;
        this.taskMapper = taskMapper;
    }

    @Scheduled(cron = "${j2agent.storage.sync.cleanup-cron:0 30 2 * * *}")
    @Transactional
    public void cleanupExpiredHistory() {
        cleanupExpiredHistory(System.currentTimeMillis());
    }

    void cleanupExpiredHistory(long now) {
        int retentionDays = properties.getSync().getRetentionDays();
        if (retentionDays < 1) {
            log.warn("Skip object storage history cleanup because retention-days is less than 1");
            return;
        }
        long cutoff = now - Duration.ofDays(retentionDays).toMillis();
        int deletedDiffs = diffMapper.deleteByCompletedTaskBefore(cutoff);
        int deletedTasks = taskMapper.deleteCompletedBefore(cutoff);
        if (deletedDiffs > 0 || deletedTasks > 0) {
            log.info(
                    "Cleaned object storage sync history before {}, deleted {} differences and {} tasks",
                    cutoff,
                    deletedDiffs,
                    deletedTasks
            );
        }
    }
}
