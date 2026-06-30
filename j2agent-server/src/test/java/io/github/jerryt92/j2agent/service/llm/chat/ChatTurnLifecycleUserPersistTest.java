package io.github.jerryt92.j2agent.service.llm.chat;

import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.service.llm.advisor.ReactCompatibleMessageChatMemoryAdvisor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatTurnLifecycleUserPersistTest {

    @Test
    void persistTurnUserMessageWritesUserWithPrePersistedFlag() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        String conversationId = "user:ctx:universal_assistant";

        ChatTurnLifecycle.persistTurnUserMessage(chatMemory, conversationId, "hello", List.of());

        verify(chatMemory).add(eq(conversationId), any(Message.class));
        var captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(chatMemory).add(eq(conversationId), captor.capture());
        Message saved = captor.getValue();
        assertTrue(saved instanceof UserMessage);
        Map<String, Object> metadata = saved.getMetadata();
        assertTrue(Boolean.TRUE.equals(metadata.get(ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED)));
        assertTrue(metadata.containsKey(ChatMemory.CONVERSATION_ID));
    }

    @Test
    void persistTurnUserMessageIncludesAttachmentsMetadata() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ChatAttachmentDto attachment = new ChatAttachmentDto();
        attachment.setUrl("oss://a.png");

        ChatTurnLifecycle.persistTurnUserMessage(
                chatMemory, "user:ctx:universal_assistant", "看图", List.of(attachment));

        var captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(chatMemory).add(eq("user:ctx:universal_assistant"), captor.capture());
        assertTrue(captor.getValue().getMetadata().containsKey("attachments"));
    }
}
