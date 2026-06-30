package io.github.jerryt92.j2agent.service.llm.advisor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReactCompatibleMessageChatMemoryAdvisorSubAgentCallTest {

    @AfterEach
    void tearDown() {
        ReactCompatibleMessageChatMemoryAdvisor.clear();
    }

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

    @Test
    void prePersistedUserMessageSkipsMemoryAddButStillReadsHistory() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get(anyString())).thenReturn(List.of());
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);

        UserMessage userMessage = UserMessage.builder()
                .text("query")
                .metadata(Map.of(
                        ChatMemory.CONVERSATION_ID, "user:ctx:universal_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, Boolean.TRUE))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        advisor.before(request, chain);

        verify(chatMemory).get("user:ctx:universal_assistant");
        verify(chatMemory, never()).add(anyString(), any(Message.class));
        verify(chatMemory, never()).add(anyString(), any(List.class));
    }

    @Test
    void subAgentCallRunAfterSkipsPersistWhenFlagInResponseContextOnly() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);

        ReactCompatibleMessageChatMemoryAdvisor.setConversationId("user:ctx:rc_wiki_assistant");
        ChatClientResponse response = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))))
                .context(Map.of(ReactCompatibleMessageChatMemoryAdvisor.META_SUB_AGENT_CALL_RUN, Boolean.TRUE))
                .build();

        advisor.after(response, chain);

        verify(chatMemory, never()).add(anyString(), any(Message.class));
        verify(chatMemory, never()).add(anyString(), any(List.class));
    }

    @Test
    void subAgentCallRunBeforePutsFlagInRequestContextForStreamAfter() {
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

        ChatClientRequest processed = advisor.before(request, chain);

        ReactCompatibleMessageChatMemoryAdvisor.setConversationId("user:ctx:rc_wiki_assistant");
        ChatClientResponse response = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("answer")))))
                .context(processed.context())
                .build();

        advisor.after(response, chain);

        verify(chatMemory, never()).add(anyString(), any(Message.class));
        verify(chatMemory, never()).add(anyString(), any(List.class));
    }
}
