package io.github.jerryt92.j2agent.model;

import io.github.jerryt92.j2agent.service.llm.ChatContextBo;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TranslatorTest {

    @Test
    void shouldMapUserAttachmentsInHistoryDto() {
        ChatAttachmentDto attachment = new ChatAttachmentDto()
                .objectKey("chat/user/ctx-1/uuid_image.png")
                .name("image.png")
                .contentType("image/png")
                .size(128L);
        UserMessage userMessage = UserMessage.builder()
                .text("describe this")
                .metadata(Map.of("attachments", List.of(attachment)))
                .build();
        ChatContextBo contextBo = new ChatContextBo(
                "ctx-1",
                "user-1",
                "chat_assistant",
                "New Chat",
                1,
                1,
                System.currentTimeMillis(),
                List.of(userMessage)
        );

        ChatContextDto dto = Translator.translateToChatContextDto(contextBo);

        assertEquals(1, dto.getMessages().size());
        MessageDto messageDto = dto.getMessages().getFirst();
        assertNotNull(messageDto.getAttachments());
        assertEquals(1, messageDto.getAttachments().size());
        assertEquals("chat/user/ctx-1/uuid_image.png", messageDto.getAttachments().getFirst().getObjectKey());
        assertEquals(
                "/v1/rest/j2agent/chat/files/content?objectKey=chat%2Fuser%2Fctx-1%2Fuuid_image.png",
                messageDto.getAttachments().getFirst().getUrl()
        );
    }
}
