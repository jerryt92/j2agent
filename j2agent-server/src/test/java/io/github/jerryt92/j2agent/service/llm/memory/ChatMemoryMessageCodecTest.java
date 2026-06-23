package io.github.jerryt92.j2agent.service.llm.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMemoryMessageCodecTest {

    @Test
    void shouldPersistAndRestoreUserAttachments() throws Exception {
        ChatMemoryMessageCodec codec = new ChatMemoryMessageCodec(new ObjectMapper());
        ChatAttachmentDto attachment = new ChatAttachmentDto()
                .objectKey("chat/user/ctx-1/uuid_image.png")
                .name("image.png")
                .contentType("image/png")
                .size(128L)
                .url("/v1/rest/j2agent/chat/files/content?objectKey=image");
        UserMessage input = UserMessage.builder()
                .text("describe this")
                .metadata(Map.of("attachments", List.of(attachment)))
                .build();

        ChatMemoryMessageCodec.PersistedRow row = codec.encode(input);
        UserMessage restored = (UserMessage) codec.decode(row.chatRole(), row.content(), row.metaJson());

        assertNotNull(row.metaJson());
        assertEquals("describe this", restored.getText());
        assertEquals(false, row.metaJson().contains("/chat/files/content"));
        List<?> attachments = (List<?>) restored.getMetadata().get("attachments");
        assertEquals(1, attachments.size());
        ChatAttachmentDto restoredAttachment = (ChatAttachmentDto) attachments.get(0);
        assertEquals("chat/user/ctx-1/uuid_image.png", restoredAttachment.getObjectKey());
        assertNull(restoredAttachment.getUrl());
    }

    @Test
    void shouldDecodeUserAttachmentsWithoutLoadingMedia() throws Exception {
        ChatMemoryMessageCodec codec = new ChatMemoryMessageCodec(new ObjectMapper());
        String metaJson = """
                {"attachments":[{"objectKey":"chat/user/ctx-1/missing.png","name":"missing.png","contentType":"image/png","size":1}]}
                """;

        UserMessage restored = (UserMessage) codec.decode(1, "hello", metaJson);

        assertEquals("hello", restored.getText());
        assertTrue(restored.getMedia().isEmpty());
        List<?> attachments = (List<?>) restored.getMetadata().get("attachments");
        assertEquals(1, attachments.size());
        ChatAttachmentDto restoredAttachment = (ChatAttachmentDto) attachments.get(0);
        assertEquals("chat/user/ctx-1/missing.png", restoredAttachment.getObjectKey());
    }
}
