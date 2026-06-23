package io.github.jerryt92.j2agent.service.llm.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ReactCompatibleMessageChatMemoryAdvisorSubAgentCallTest {

    @Test
    void subAgentCallRunSkipsMemoryReadAndWrite() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);

        UserMessage userMessage = UserMessage.builder()
                .text("query")
                .metadata(Map.of(
                        ChatMemory.CONVERSATION_ID, "user:ctx:rc_wiki_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_SUB_AGENT_CALL_RUN, Boolean.TRUE))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        advisor.before(request, chain);

        verify(chatMemory, never()).get(anyString());
        verify(chatMemory, never()).add(anyString(), any(Message.class));
        verify(chatMemory, never()).add(anyString(), any(List.class));
    }

    @Test
    void legacyDelegateRunMetadataStillSkipsMemoryReadAndWrite() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);

        UserMessage userMessage = UserMessage.builder()
                .text("query")
                .metadata(Map.of(
                        ChatMemory.CONVERSATION_ID, "user:ctx:rc_wiki_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_DELEGATE_RUN, Boolean.TRUE))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        advisor.before(request, chain);

        verify(chatMemory, never()).get(anyString());
        verify(chatMemory, never()).add(anyString(), any(Message.class));
        verify(chatMemory, never()).add(anyString(), any(List.class));
    }
}
