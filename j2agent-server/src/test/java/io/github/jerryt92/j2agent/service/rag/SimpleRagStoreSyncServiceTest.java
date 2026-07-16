package io.github.jerryt92.j2agent.service.rag;

import io.github.jerryt92.j2agent.mapper.SimpleRagCollectionStateMapper;
import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.model.po.SimpleRagCollectionStatePo;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.rag.inf.AbstractSimpleRagRetriever;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeTextChunkParser;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SimpleRag 同步服务：完成态跳过、失败态重建、后台提交与陈旧状态清理。
 */
class SimpleRagStoreSyncServiceTest {

    private ApplicationContext applicationContext;
    private EmbeddingService embeddingService;
    private VectorDatabaseService vectorDatabaseService;
    private SimpleRagCollectionStateMapper stateMapper;
    private SimpleRagStoreSyncService service;

    @BeforeEach
    void setUp() {
        applicationContext = mock(ApplicationContext.class);
        embeddingService = mock(EmbeddingService.class);
        vectorDatabaseService = mock(VectorDatabaseService.class);
        stateMapper = mock(SimpleRagCollectionStateMapper.class);
        service = new SimpleRagStoreSyncService(
                applicationContext,
                embeddingService,
                vectorDatabaseService,
                new KnowledgeTextChunkParser(),
                stateMapper,
                Runnable::run);
    }

    @Test
    void completedStateSkipsEmbeddingAndCollectionRewrite() {
        SimpleRagCollectionStatePo state = state("simple_rag_test_store", "COMPLETED", 3);
        when(stateMapper.selectByCollectionName("simple_rag_test_store")).thenReturn(state);

        SimpleRagStoreSyncService.SimpleRagRefreshResult result = service.refresh(new TestRetriever(), "agent-1");

        assertTrue(result.success());
        assertEquals(3, result.documentCount());
        verify(embeddingService, never()).embed(any());
        verify(vectorDatabaseService, never()).dropCollection(any());
        verify(vectorDatabaseService, never()).createCollectionIfAbsent(any());
        verify(stateMapper, never()).upsert(any());
    }

    @Test
    void incompleteStateClearsCollectionAndRebuilds() {
        when(stateMapper.selectByCollectionName("simple_rag_test_store"))
                .thenReturn(state("simple_rag_test_store", "IN_PROGRESS", 0));
        when(embeddingService.isReady()).thenReturn(true);
        when(embeddingService.getDimension()).thenReturn(2);
        when(embeddingService.embed(any())).thenReturn(embedResponse(1));

        SimpleRagStoreSyncService.SimpleRagRefreshResult result = service.refresh(new TestRetriever(), "agent-1");

        assertTrue(result.success());
        assertEquals(1, result.documentCount());
        verify(vectorDatabaseService).dropCollection("simple_rag_test_store");
        verify(vectorDatabaseService).createCollectionIfAbsent("simple_rag_test_store");
        verify(vectorDatabaseService).putData(any(), any());

        ArgumentCaptor<SimpleRagCollectionStatePo> captor =
                ArgumentCaptor.forClass(SimpleRagCollectionStatePo.class);
        verify(stateMapper, org.mockito.Mockito.times(2)).upsert(captor.capture());
        List<String> statuses = captor.getAllValues().stream()
                .map(SimpleRagCollectionStatePo::getSyncStatus)
                .toList();
        assertEquals(List.of("IN_PROGRESS", "COMPLETED"), statuses);
        assertEquals(1, captor.getAllValues().get(1).getDocumentCount());
    }

    @Test
    void embeddingMismatchMarksFailed() {
        when(stateMapper.selectByCollectionName("simple_rag_test_store")).thenReturn(null);
        when(embeddingService.isReady()).thenReturn(true);
        when(embeddingService.getDimension()).thenReturn(2);
        when(embeddingService.embed(any())).thenReturn(embedResponse(0));

        SimpleRagStoreSyncService.SimpleRagRefreshResult result = service.refresh(new TestRetriever(), "agent-1");

        assertFalse(result.success());
        ArgumentCaptor<SimpleRagCollectionStatePo> captor =
                ArgumentCaptor.forClass(SimpleRagCollectionStatePo.class);
        verify(stateMapper, org.mockito.Mockito.times(2)).upsert(captor.capture());
        SimpleRagCollectionStatePo failed = captor.getAllValues().get(1);
        assertEquals("FAILED", failed.getSyncStatus());
        assertTrue(failed.getErrorMessage().contains("Embedding response size mismatch"));
    }

    @Test
    void synchronizeSubmitsBackgroundTaskAndReturnsImmediately() {
        RecordingTaskExecutor executor = new RecordingTaskExecutor();
        SimpleRagStoreSyncService asyncService = new SimpleRagStoreSyncService(
                applicationContext,
                embeddingService,
                vectorDatabaseService,
                new KnowledgeTextChunkParser(),
                stateMapper,
                executor);

        asyncService.synchronizeSimpleRagRetrievers();

        assertEquals(1, executor.submitted);
        verifyNoInteractions(vectorDatabaseService);
    }

    @Test
    void backgroundSynchronizationDeletesStaleCollectionState() {
        when(applicationContext.getBeansOfType(AiAgent.class, true, false))
                .thenReturn(Map.of());
        when(vectorDatabaseService.listCollections())
                .thenReturn(List.of("simple_rag_old_store"));
        when(stateMapper.selectAllCollectionNames())
                .thenReturn(List.of("simple_rag_old_store"));

        service.synchronizeSimpleRagRetrieversInBackground();

        verify(vectorDatabaseService).dropCollection("simple_rag_old_store");
        verify(stateMapper).deleteByCollectionName("simple_rag_old_store");
    }

    private SimpleRagCollectionStatePo state(String collectionName, String syncStatus, int documentCount) {
        SimpleRagCollectionStatePo state = new SimpleRagCollectionStatePo();
        state.setCollectionName(collectionName);
        state.setSyncStatus(syncStatus);
        state.setDocumentCount(documentCount);
        return state;
    }

    private EmbeddingModel.EmbeddingsResponse embedResponse(int count) {
        EmbeddingModel.EmbeddingsResponse response = new EmbeddingModel.EmbeddingsResponse();
        java.util.ArrayList<EmbeddingModel.EmbeddingsItem> items = new java.util.ArrayList<>();
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

    private static class TestRetriever extends AbstractSimpleRagRetriever {
        @Override
        public String ragStoreName() {
            return "test_store";
        }

        @Override
        public String simpleRagResourcePath() {
            return "simple-rag-test";
        }
    }

    private static class RecordingTaskExecutor implements TaskExecutor {
        private int submitted;

        @Override
        public void execute(Runnable task) {
            submitted++;
        }
    }
}
