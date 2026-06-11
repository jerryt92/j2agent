package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.VectorDatabaseInit;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeTextChunkService;
import io.github.jerryt92.j2agent.service.rag.knowledge.MilvusKnowledgeWriteService;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRepoSyncServiceFullRebuildTest {

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

    @TempDir
    Path tempRepo;

    @Test
    void executeFullRebuild_dropsKnowledgeCollectionsBeforeProbe() {
        when(metadataService.getRepoRootPath()).thenReturn(tempRepo);
        when(metadataService.listConfiguredCollectionNames()).thenReturn(Set.of("current_collection"));
        when(metadataService.hasMetadata()).thenReturn(false);
        when(hashTreeService.loadActiveCollectionCounts()).thenReturn(Map.of("old_count_collection", 1L));
        when(hashTreeService.loadActiveFileCollections()).thenReturn(Map.of("doc.md", "old_file_collection"));
        when(vectorDatabaseService.listCollections()).thenReturn(List.of(
                "current_collection", "old_count_collection", "old_file_collection", "foreign_collection"));
        when(vectorDatabaseInit.probeAndConfigure()).thenReturn(true);

        KnowledgeRepoSyncService syncService = new KnowledgeRepoSyncService(
                metadataService,
                hashTreeService,
                knowledgeTextChunkParser,
                knowledgeMarkdownImageRewriter,
                milvusKnowledgeWriteService,
                embeddingService,
                vectorDatabaseService,
                vectorDatabaseInit,
                knowledgeTextChunkService);

        assertTrue(syncService.executeFullRebuild(() -> true));

        InOrder order = inOrder(milvusKnowledgeWriteService, hashTreeService, knowledgeTextChunkService, vectorDatabaseService, vectorDatabaseInit);
        order.verify(milvusKnowledgeWriteService).dropCollection("current_collection");
        order.verify(milvusKnowledgeWriteService).dropCollection("old_count_collection");
        order.verify(milvusKnowledgeWriteService).dropCollection("old_file_collection");
        order.verify(hashTreeService).deleteAll();
        order.verify(knowledgeTextChunkService).deleteAll();
        order.verify(vectorDatabaseService).resetClient();
        order.verify(vectorDatabaseInit).probeAndConfigure();
        verify(milvusKnowledgeWriteService, never()).dropAllCollections();
    }
}
