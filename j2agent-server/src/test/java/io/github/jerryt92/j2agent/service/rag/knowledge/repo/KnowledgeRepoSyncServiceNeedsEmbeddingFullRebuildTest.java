package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.VectorDatabaseInit;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeTextChunkService;
import io.github.jerryt92.j2agent.service.rag.knowledge.MilvusKnowledgeWriteService;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRepoSyncServiceNeedsEmbeddingFullRebuildTest {

    @Mock
    private KnowledgeRepoMetadataService metadataService;
    @Mock
    private KnowledgeRepoHashTreeService hashTreeService;
    @Mock
    private KnowledgeTextChunkParser knowledgeTextChunkParser;
    @Mock
    private KnowledgeMarkdownImageRewriter knowledgeMarkdownImageRewriter;
    @Mock
    private MilvusKnowledgeWriteService milvusKnowledgeWriteService;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private VectorDatabaseService vectorDatabaseService;
    @Mock
    private VectorDatabaseInit vectorDatabaseInit;
    @Mock
    private KnowledgeTextChunkService knowledgeTextChunkService;

    @Test
    void needsEmbeddingFullRebuild_whenEmbeddingNotReady_returnsFalse() {
        when(embeddingService.isReady()).thenReturn(false);

        KnowledgeRepoSyncService syncService = newSyncService();
        assertFalse(syncService.needsEmbeddingFullRebuild());
    }

    @Test
    void needsEmbeddingFullRebuild_whenNoExistingCollections_returnsFalse() {
        when(embeddingService.isReady()).thenReturn(true);
        when(embeddingService.getDimension()).thenReturn(768);
        when(embeddingService.getCheckEmbeddingHash()).thenReturn("current-hash");
        when(metadataService.listConfiguredCollectionNames()).thenReturn(Set.of("kb"));
        when(hashTreeService.loadActiveCollectionCounts()).thenReturn(Map.of());
        when(hashTreeService.loadActiveFileCollections()).thenReturn(Map.of());
        when(vectorDatabaseService.hasCollection("kb")).thenReturn(false);

        KnowledgeRepoSyncService syncService = newSyncService();
        assertFalse(syncService.needsEmbeddingFullRebuild());
    }

    @Test
    void needsEmbeddingFullRebuild_whenSchemaDimensionMismatch_returnsTrue() {
        when(embeddingService.isReady()).thenReturn(true);
        when(embeddingService.getDimension()).thenReturn(1024);
        when(embeddingService.getCheckEmbeddingHash()).thenReturn("current-hash");
        when(metadataService.listConfiguredCollectionNames()).thenReturn(Set.of("kb"));
        when(hashTreeService.loadActiveCollectionCounts()).thenReturn(Map.of());
        when(hashTreeService.loadActiveFileCollections()).thenReturn(Map.of());
        when(vectorDatabaseService.hasCollection("kb")).thenReturn(true);
        when(vectorDatabaseService.resolveCollectionEmbeddingDimension("kb")).thenReturn(768);

        KnowledgeRepoSyncService syncService = newSyncService();
        assertTrue(syncService.needsEmbeddingFullRebuild());
    }

    @Test
    void needsEmbeddingFullRebuild_whenStoredHashMismatch_returnsTrue() {
        when(embeddingService.isReady()).thenReturn(true);
        when(embeddingService.getDimension()).thenReturn(768);
        when(embeddingService.getCheckEmbeddingHash()).thenReturn("current-hash");
        when(metadataService.listConfiguredCollectionNames()).thenReturn(Set.of("kb"));
        when(hashTreeService.loadActiveCollectionCounts()).thenReturn(Map.of());
        when(hashTreeService.loadActiveFileCollections()).thenReturn(Map.of());
        when(vectorDatabaseService.hasCollection("kb")).thenReturn(true);
        when(vectorDatabaseService.resolveCollectionEmbeddingDimension("kb")).thenReturn(768);
        when(vectorDatabaseService.sampleStoredCheckEmbeddingHash("kb")).thenReturn("old-hash");

        KnowledgeRepoSyncService syncService = newSyncService();
        assertTrue(syncService.needsEmbeddingFullRebuild());
    }

    @Test
    void needsEmbeddingFullRebuild_whenDimensionAndHashMatch_returnsFalse() {
        when(embeddingService.isReady()).thenReturn(true);
        when(embeddingService.getDimension()).thenReturn(768);
        when(embeddingService.getCheckEmbeddingHash()).thenReturn("current-hash");
        when(metadataService.listConfiguredCollectionNames()).thenReturn(Set.of("kb"));
        when(hashTreeService.loadActiveCollectionCounts()).thenReturn(Map.of());
        when(hashTreeService.loadActiveFileCollections()).thenReturn(Map.of());
        when(vectorDatabaseService.hasCollection("kb")).thenReturn(true);
        when(vectorDatabaseService.resolveCollectionEmbeddingDimension("kb")).thenReturn(768);
        when(vectorDatabaseService.sampleStoredCheckEmbeddingHash("kb")).thenReturn("current-hash");

        KnowledgeRepoSyncService syncService = newSyncService();
        assertFalse(syncService.needsEmbeddingFullRebuild());
    }

    private KnowledgeRepoSyncService newSyncService() {
        return new KnowledgeRepoSyncService(
                metadataService,
                hashTreeService,
                knowledgeTextChunkParser,
                knowledgeMarkdownImageRewriter,
                milvusKnowledgeWriteService,
                embeddingService,
                vectorDatabaseService,
                vectorDatabaseInit,
                knowledgeTextChunkService,
                new KnowledgeRepoSyncProgressTracker());
    }
}
