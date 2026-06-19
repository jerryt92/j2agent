package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgeRepoSyncProgressTrackerTest {

    @Test
    void tracksDiffFileStatusesThroughCompletion() {
        KnowledgeRepoSyncProgressTracker tracker = new KnowledgeRepoSyncProgressTracker();
        tracker.registerDiff(
                Set.of("old.md"),
                Set.of("new.md"),
                Set.of("changed.md"),
                Map.of("new.md", "kb", "changed.md", "kb"),
                Map.of("old.md", "kb"));

        assertEquals(KnowledgeRepoSyncPhase.DELETING, tracker.snapshot().phase());
        assertEquals(3, tracker.snapshot().totalCount());

        tracker.markFileInProgress("old.md");
        tracker.markFileDeleted("old.md");
        assertEquals(KnowledgeRepoSyncFileStatus.DELETED, tracker.snapshot().files().getFirst().status());

        tracker.setPhase(KnowledgeRepoSyncPhase.UPSERTING);
        tracker.markFileInProgress("new.md");
        tracker.markFileSynced("new.md", "kb", 4);
        tracker.markFileInProgress("changed.md");
        tracker.markFileSkipped("changed.md", "kb", "Embedding 不可用");

        tracker.complete();
        KnowledgeRepoSyncProgressTracker.Snapshot snapshot = tracker.snapshot();
        assertEquals(KnowledgeRepoSyncPhase.COMPLETED, snapshot.phase());
        assertEquals(3, snapshot.processedCount());
        assertEquals(KnowledgeRepoSyncFileStatus.SYNCED, snapshot.files().get(1).status());
        assertEquals(4, snapshot.files().get(1).knowledgeCount());
        assertEquals(KnowledgeRepoSyncFileStatus.SKIPPED, snapshot.files().get(2).status());
    }
}
