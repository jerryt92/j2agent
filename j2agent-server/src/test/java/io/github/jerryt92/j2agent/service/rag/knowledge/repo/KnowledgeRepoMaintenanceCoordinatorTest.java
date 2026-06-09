package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.config.VectorDatabaseInit;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.providerconfig.ActiveProviderHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRepoMaintenanceCoordinatorTest {

    @Mock
    private KnowledgeRepoProperties properties;
    @Mock
    private KnowledgeRepoMetadataService metadataService;
    @Mock
    private KnowledgeRepoSyncService syncService;
    @Mock
    private KnowledgeRepoMaintenanceLockService lockService;
    @Mock
    private VectorDatabaseInit vectorDatabaseInit;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private ActiveProviderHolder activeProviderHolder;

    @TempDir
    Path tempRepo;

    private KnowledgeRepoMaintenanceCoordinator coordinator;

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.destroy();
        }
    }

    @Test
    void syncNowAndAwait_whenExclusiveActive_incrementalStillBusy() {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        coordinator = newCoordinator();
        coordinator.requestEmbeddingRuntimeRebuild();
        assertTrue(coordinator.isExclusiveSyncActive());

        KnowledgeRepoSyncOutcome outcome = coordinator.syncNowAndAwait(Duration.ofSeconds(1), false);
        assertFalse(outcome.succeeded());
        assertTrue(outcome.message().contains("exclusive"));
    }

    @Test
    void syncNowAndAwait_fullRebuild_supersedesExclusiveRebuild() throws Exception {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(lockService.repoRootHash(tempRepo)).thenReturn("repo-hash");
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(2);
            action.run();
            return true;
        }).when(lockService).tryWithLock(eq("repo-hash"), any(), any());

        AtomicInteger rebuildCalls = new AtomicInteger();
        CountDownLatch firstRebuildRunning = new CountDownLatch(1);
        when(syncService.executeFullRebuild(any())).thenAnswer(invocation -> {
            KnowledgeRepoSyncGuard guard = invocation.getArgument(0);
            if (rebuildCalls.incrementAndGet() == 1) {
                firstRebuildRunning.countDown();
                while (guard.shouldContinue()) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                return false;
            }
            return true;
        });

        coordinator = newCoordinator();
        coordinator.requestEmbeddingRuntimeRebuild();
        assertTrue(firstRebuildRunning.await(5, TimeUnit.SECONDS));

        KnowledgeRepoSyncOutcome outcome = coordinator.syncNowAndAwait(Duration.ofSeconds(10), true);

        assertTrue(outcome.succeeded());
        assertEquals(2, rebuildCalls.get());
        assertNotEquals(KnowledgeRepoMaintenanceTaskType.FAILED, coordinator.getCurrentTaskType());
    }

    @Test
    void requestEmbeddingRuntimeRebuild_whenSuperseded_doesNotMarkFailed() throws Exception {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(lockService.repoRootHash(tempRepo)).thenReturn("repo-hash");
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(2);
            action.run();
            return true;
        }).when(lockService).tryWithLock(eq("repo-hash"), any(), any());

        CountDownLatch firstRebuildRunning = new CountDownLatch(1);
        when(syncService.executeFullRebuild(any())).thenAnswer(invocation -> {
            KnowledgeRepoSyncGuard guard = invocation.getArgument(0);
            firstRebuildRunning.countDown();
            while (guard.shouldContinue()) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return false;
        }).thenReturn(true);

        coordinator = newCoordinator();
        coordinator.requestEmbeddingRuntimeRebuild();
        assertTrue(firstRebuildRunning.await(5, TimeUnit.SECONDS));

        coordinator.requestEmbeddingRuntimeRebuild();
        Thread.sleep(500);

        assertNotEquals(KnowledgeRepoMaintenanceTaskType.FAILED, coordinator.getCurrentTaskType());
    }

    @Test
    void syncNowAndAwait_whenRepoRootMissing_returnsFail() {
        coordinator = newCoordinator();
        when(metadataService.getRepoRootPath()).thenReturn(null);

        KnowledgeRepoSyncOutcome outcome = coordinator.syncNowAndAwait(Duration.ofSeconds(1), false);
        assertFalse(outcome.succeeded());
        assertTrue(outcome.message().contains("根目录"));
    }

    @Test
    void isExclusiveGenerationActive_tracksLatestGeneration() {
        coordinator = newCoordinator();
        long gen1 = coordinator.claimExclusiveGeneration();
        assertTrue(coordinator.isExclusiveGenerationActive(gen1));

        long gen2 = coordinator.claimExclusiveGeneration();
        assertFalse(coordinator.isExclusiveGenerationActive(gen1));
        assertTrue(coordinator.isExclusiveGenerationActive(gen2));
    }

    private KnowledgeRepoMaintenanceCoordinator newCoordinator() {
        return new KnowledgeRepoMaintenanceCoordinator(
                properties,
                metadataService,
                syncService,
                lockService,
                vectorDatabaseInit,
                embeddingService,
                activeProviderHolder);
    }
}
