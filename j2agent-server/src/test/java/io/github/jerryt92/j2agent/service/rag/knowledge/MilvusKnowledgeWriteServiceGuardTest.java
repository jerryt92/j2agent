package io.github.jerryt92.j2agent.service.rag.knowledge;

import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.ContentSegmentChunker;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoSyncGuard;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeTextChunkParser;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MilvusKnowledgeWriteServiceGuardTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private VectorDatabaseService vectorDatabaseService;
    @Mock
    private KnowledgeTextChunkService knowledgeTextChunkService;
    @Mock
    private ContentSegmentChunker contentSegmentChunker;

    @InjectMocks
    private MilvusKnowledgeWriteService writeService;

    @Test
    void upsertTextChunks_whenGuardStopsBeforePutData_doesNotWrite() {
        List<KnowledgeTextChunkParser.TextChunk> chunks = List.of(
                new KnowledgeTextChunkParser.TextChunk("id1", "title", "body", "doc.md", false));
        when(embeddingService.resolveEmbeddingBatchSize()).thenReturn(1);
        when(contentSegmentChunker.chunk("body")).thenReturn(List.of("body"));

        KnowledgeRepoSyncGuard guard = () -> false;

        assertThrows(MilvusKnowledgeWriteService.SyncInterruptedException.class,
                () -> writeService.upsertTextChunks(chunks, "doc.md", "hash", "col", List.of(), guard));

        verify(vectorDatabaseService, never()).putData(any(), any(), any());
    }

    @Test
    void upsertTextChunks_whenGuardStopsBetweenBatches_doesNotWriteSecondBatch() {
        List<KnowledgeTextChunkParser.TextChunk> chunks = List.of(
                new KnowledgeTextChunkParser.TextChunk("id1", "t1", "a1", "doc.md", false),
                new KnowledgeTextChunkParser.TextChunk("id2", "t2", "a2", "doc.md", false));
        when(embeddingService.resolveEmbeddingBatchSize()).thenReturn(1);
        when(contentSegmentChunker.chunk(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> List.of(invocation.getArgument(0, String.class)));
        when(embeddingService.embed(any())).thenReturn(embedResponse(1));

        AtomicInteger batchChecks = new AtomicInteger();
        KnowledgeRepoSyncGuard guard = () -> batchChecks.incrementAndGet() <= 2;

        assertThrows(MilvusKnowledgeWriteService.SyncInterruptedException.class,
                () -> writeService.upsertTextChunks(chunks, "doc.md", "hash", "col", List.of(), guard));

        verify(vectorDatabaseService).putData(any(), any(), any());
        verify(embeddingService).embed(any());
    }

    private EmbeddingModel.EmbeddingsResponse embedResponse(int count) {
        EmbeddingModel.EmbeddingsResponse response = new EmbeddingModel.EmbeddingsResponse();
        List<EmbeddingModel.EmbeddingsItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(new EmbeddingModel.EmbeddingsItem()
                    .setEmbeddings(new float[]{0.1f, 0.2f})
                    .setEmbeddingModel("test-model")
                    .setEmbeddingProvider("test-provider")
                    .setCheckEmbeddingHash("hash"));
        }
        response.setData(items);
        return response;
    }
}
