package io.github.jerryt92.j2agent.service.rag.query;

import io.github.jerryt92.j2agent.service.llm.LlmSyncService;
import io.github.jerryt92.j2agent.service.rag.query.ConditionalQueryTransformer;
import io.github.jerryt92.j2agent.service.rag.query.LoggingQueryTransformer;
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
        ChatClient.Builder clientBuilder = ChatClient.builder(mock(ChatModel.class));
        when(llmSyncService.chatClientBuilder()).thenReturn(clientBuilder);
        DefaultQueryTransformers factory = new DefaultQueryTransformers(llmSyncService);

        QueryTransformer[] transformers = factory.build(null);

        assertEquals(3, transformers.length);
        assertTrue(transformers[0] instanceof LoggingQueryTransformer);
        assertTrue(transformers[1] instanceof ConditionalQueryTransformer);
        assertTrue(transformers[2] instanceof ConditionalQueryTransformer);
    }
}
