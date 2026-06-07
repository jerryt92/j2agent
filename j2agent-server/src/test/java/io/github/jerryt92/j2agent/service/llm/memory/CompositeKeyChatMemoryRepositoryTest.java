package io.github.jerryt92.j2agent.service.llm.memory;

import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompositeKeyChatMemoryRepositoryTest {

    @Test
    void autoTitleShouldTruncateFirstUserTextForLegacyDetection() {
        String text = "a".repeat(100);
        assertEquals(64, CompositeKeyChatMemoryRepository.autoTitle(text, List.of()).length());
        assertEquals("hello", CompositeKeyChatMemoryRepository.autoTitle("hello", List.of()));
    }

    @Test
    void autoTitleShouldUseImageOnlyTitleForAttachments() {
        List<ChatAttachmentDto> attachments = List.of(new ChatAttachmentDto().objectKey("chat/u/a.png"));
        assertEquals(
                CompositeKeyChatMemoryRepository.IMAGE_ONLY_TITLE,
                CompositeKeyChatMemoryRepository.autoTitle("", attachments));
    }
}
