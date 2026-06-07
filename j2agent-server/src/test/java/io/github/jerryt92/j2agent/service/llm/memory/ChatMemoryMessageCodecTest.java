package io.github.jerryt92.j2agent.service.llm.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        List<?> attachments = (List<?>) restored.getMetadata().get("attachments");
        assertEquals(1, attachments.size());
        assertEquals("chat/user/ctx-1/uuid_image.png", ((ChatAttachmentDto) attachments.get(0)).getObjectKey());
    }
}
