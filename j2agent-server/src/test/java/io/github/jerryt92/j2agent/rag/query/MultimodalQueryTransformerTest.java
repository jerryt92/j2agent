package io.github.jerryt92.j2agent.service.rag.query;

import io.github.jerryt92.j2agent.service.llm.LlmSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.rag.Query;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultimodalQueryTransformerTest {

    @Test
    void shouldPassThroughWhenNoMedia() {
        MultimodalQueryTransformer transformer = MultimodalQueryTransformer.builder()
                .llmSyncService(mock(LlmSyncService.class))
                .build();
        Query query = Query.builder()
                .text("如何登录")
                .history(List.of(UserMessage.builder().text("如何登录").build()))
                .build();

        Query result = transformer.transform(query);

        assertEquals("如何登录", result.text());
    }

    @Test
    void shouldSkipRetrievalWhenVlmFailsForImageOnly() {
        LlmSyncService llmSyncService = mock(LlmSyncService.class);
        when(llmSyncService.callUserMultimodal(anyString(), anyList(), anyInt())).thenReturn(null);
        MultimodalQueryTransformer transformer = MultimodalQueryTransformer.builder()
                .llmSyncService(llmSyncService)
                .build();
        Media image = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1, 2, 3}))
                .build();
        UserMessage userMessage = UserMessage.builder()
                .text(QueryUserMessageSupport.IMAGE_ONLY_QUERY_PLACEHOLDER)
                .media(image)
                .build();
        Query query = Query.builder()
                .text(QueryUserMessageSupport.IMAGE_ONLY_QUERY_PLACEHOLDER)
                .history(List.of(userMessage))
                .build();

        Query result = transformer.transform(query);

        assertTrue(Boolean.TRUE.equals(result.context().get(QueryTransformContextKeys.SKIP_RETRIEVAL)));
    }

    @Test
    void shouldFallbackToUserTextWhenVlmFailsWithTextAndImage() {
        LlmSyncService llmSyncService = mock(LlmSyncService.class);
        when(llmSyncService.callUserMultimodal(anyString(), anyList(), anyInt())).thenReturn(null);
        MultimodalQueryTransformer transformer = MultimodalQueryTransformer.builder()
                .llmSyncService(llmSyncService)
                .build();
        Media image = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1, 2, 3}))
                .build();
        UserMessage userMessage = UserMessage.builder()
                .text("这个报错什么意思")
                .media(image)
                .build();
        Query query = Query.builder()
                .text("这个报错什么意思")
                .history(List.of(userMessage))
                .build();

        Query result = transformer.transform(query);

        assertEquals("这个报错什么意思", result.text());
    }
}
