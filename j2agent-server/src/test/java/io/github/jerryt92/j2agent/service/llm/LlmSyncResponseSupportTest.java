package io.github.jerryt92.j2agent.service.llm;

import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSyncResponseSupportTest {

    @Test
    void shouldExtractAssistantTextFromOutput() {
        ChatResponse response = new ChatResponse(List.of(new Generation(
                new AssistantMessage("登录报错 500 排查"),
                ChatGenerationMetadata.builder().build())));

        assertEquals("登录报错 500 排查", LlmSyncResponseSupport.extractAssistantText(response));
    }

    @Test
    void shouldDescribeMediaWithoutLoggingContent() {
        Media media = Media.builder()
                .mimeType(MimeTypeUtils.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1, 2, 3, 4}))
                .build();

        String description = LlmSyncResponseSupport.describeMedia(List.of(media));

        assertTrue(description.contains("image/png"));
        assertTrue(description.contains("4B"), description);
    }

    @Test
    void shouldSummarizeEmptyResponseDiagnostics() {
        AssistantMessage output = AssistantMessage.builder()
                .content("")
                .properties(Map.of("type", "text"))
                .build();
        ChatResponse response = new ChatResponse(
                List.of(new Generation(output, ChatGenerationMetadata.builder()
                        .metadata("finishReason", "end_turn")
                        .build())),
                ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(100, 0, 100))
                        .build());

        String summary = LlmSyncResponseSupport.summarizeEmptyResponseDiagnostics(response);

        assertTrue(summary.contains("finishReason=end_turn"));
        assertTrue(summary.contains("outputTokens=0"));
        assertTrue(summary.contains("outputTextLen=0"));
        assertTrue(summary.contains("finishReason"));
    }

    @Test
    void shouldResolveReadTimeoutCauseLabel() {
        RuntimeException wrapped = new RuntimeException("I/O error",
                new ReadTimeoutException());

        assertEquals("ReadTimeout", LlmSyncResponseSupport.resolveFailureCauseLabel(wrapped));
    }
}
