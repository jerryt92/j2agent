package io.github.jerryt92.j2agent.service.rag.query;

import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.llm.LlmSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultQueryTransformersTest {

    @Test
    void shouldBuildFullChainByDefault() {
        LlmSyncService llmSyncService = mock(LlmSyncService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        ChatClient.Builder clientBuilder = ChatClient.builder(mock(ChatModel.class));
        when(llmSyncService.chatClientBuilder()).thenReturn(clientBuilder);
        when(embeddingService.isReady()).thenReturn(true);
        DefaultQueryTransformers factory = new DefaultQueryTransformers(llmSyncService, embeddingService);

        QueryTransformer[] transformers = factory.build(null);

        assertEquals(3, transformers.length);
        assertTrue(transformers[0] instanceof LoggingQueryTransformer);
        assertTrue(transformers[1] instanceof ConditionalQueryTransformer);
        assertTrue(transformers[2] instanceof ConditionalQueryTransformer);
    }

    @Test
    void shouldSkipChainWhenEmbeddingNotReady() {
        LlmSyncService llmSyncService = mock(LlmSyncService.class);
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        when(embeddingService.isReady()).thenReturn(false);
        DefaultQueryTransformers factory = new DefaultQueryTransformers(llmSyncService, embeddingService);

        QueryTransformer[] transformers = factory.build(null);

        assertEquals(0, transformers.length);
    }
}
