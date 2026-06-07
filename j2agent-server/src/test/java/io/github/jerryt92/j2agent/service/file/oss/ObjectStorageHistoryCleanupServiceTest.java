package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.config.ObjectStorageProperties;
import io.github.jerryt92.j2agent.mapper.ObjectStorageSyncDiffMapper;
import io.github.jerryt92.j2agent.mapper.ObjectStorageSyncTaskMapper;
import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageHistoryCleanupService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Duration;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ObjectStorageHistoryCleanupServiceTest {
    private final ObjectStorageProperties properties = new ObjectStorageProperties();
    private final ObjectStorageSyncDiffMapper diffMapper = mock(ObjectStorageSyncDiffMapper.class);
    private final ObjectStorageSyncTaskMapper taskMapper = mock(ObjectStorageSyncTaskMapper.class);
    private final ObjectStorageHistoryCleanupService service =
            new ObjectStorageHistoryCleanupService(properties, diffMapper, taskMapper);

    @Test
    void shouldKeepSevenDaysByDefaultAndDeleteDifferencesFirst() {
        long now = 1_800_000_000_000L;
        long cutoff = now - Duration.ofDays(7).toMillis();

        service.cleanupExpiredHistory(now);

        InOrder order = inOrder(diffMapper, taskMapper);
        order.verify(diffMapper).deleteByCompletedTaskBefore(cutoff);
        order.verify(taskMapper).deleteCompletedBefore(cutoff);
    }

    @Test
    void shouldSkipCleanupWhenRetentionDaysIsInvalid() {
        properties.getSync().setRetentionDays(0);

        service.cleanupExpiredHistory(1_800_000_000_000L);

        verify(diffMapper, never()).deleteByCompletedTaskBefore(org.mockito.ArgumentMatchers.anyLong());
        verify(taskMapper, never()).deleteCompletedBefore(org.mockito.ArgumentMatchers.anyLong());
    }
}
