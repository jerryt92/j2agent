package io.github.jerryt92.j2agent.service.rag.retrieval;

import io.github.jerryt92.j2agent.service.PropertiesService;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeTextChunkService;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RetrieverEmbeddingFailureTest {

    private static final String COLLECTION = "test-collection";
    private static final String PROBE_ERROR = "Embedding 服务连接失败: Connection refused";

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private VectorDatabaseService vectorDatabaseService;
    @Mock
    private PropertiesService propertiesService;
    @Mock
    private QueryChunker queryChunker;
    @Mock
    private KnowledgeTextChunkService knowledgeTextChunkService;

    private Retriever retriever;

    @BeforeEach
    void setUp() {
        retriever = new Retriever(
                embeddingService,
                vectorDatabaseService,
                propertiesService,
                queryChunker,
                knowledgeTextChunkService);
        stubRetrieverParams();
        when(queryChunker.chunk("hello")).thenReturn(java.util.List.of("hello"));
    }

    @Test
    void retrieveRagChunksResult_whenEmbeddingNotReady_returnsFailedWithoutHttp() {
        when(embeddingService.isReady()).thenReturn(false);
        when(embeddingService.getLastProbeError()).thenReturn(PROBE_ERROR);

        Retriever.RagChunksResult result = retriever.retrieveRagChunksResult("hello", COLLECTION, null);

        assertEquals(Retriever.RetrievalStatus.FAILED, result.status());
        assertEquals(PROBE_ERROR, result.failureMessage());
        assertTrue(result.items().isEmpty());
        verify(embeddingService, never()).embed(any());
        verify(vectorDatabaseService, never()).hybridRetrieval(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void retrieveRagChunksResult_whenEmbedThrows_returnsFailed() {
        when(embeddingService.isReady()).thenReturn(true);
        when(embeddingService.embed(any())).thenThrow(new WebClientRequestException(
                new RuntimeException("Connection refused"),
                null,
                null,
                null));

        Retriever.RagChunksResult result = retriever.retrieveRagChunksResult("hello", COLLECTION, null);

        assertEquals(Retriever.RetrievalStatus.FAILED, result.status());
        assertTrue(result.failureMessage().contains("Connection refused"));
        assertTrue(result.items().isEmpty());
    }

    private void stubRetrieverParams() {
        when(propertiesService.getProperty(PropertiesService.RETRIEVE_TOP_K)).thenReturn("5");
        when(propertiesService.getProperty(PropertiesService.RETRIEVE_METRIC_TYPE)).thenReturn("COSINE");
        when(propertiesService.getProperty(PropertiesService.RETRIEVE_METRIC_SCORE_COMPARE_EXPR)).thenReturn(">0");
        when(propertiesService.getProperty(PropertiesService.RETRIEVE_DENSE_WEIGHT)).thenReturn("0.5");
        when(propertiesService.getProperty(PropertiesService.RETRIEVE_SPARSE_WEIGHT)).thenReturn("0.5");
    }
}
