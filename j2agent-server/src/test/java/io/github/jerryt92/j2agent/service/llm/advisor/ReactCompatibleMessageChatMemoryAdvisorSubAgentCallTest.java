package io.github.jerryt92.j2agent.service.llm.advisor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
    void subAgentCallRunSkipsMemoryOnReactFollowUpRoundWithoutUserMessage() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);

        UserMessage firstRoundUser = UserMessage.builder()
                .text("query")
                .metadata(Map.of(
                        ChatMemory.CONVERSATION_ID, "user:ctx:rc_wiki_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_SUB_AGENT_CALL_RUN, Boolean.TRUE))
                .build();
        advisor.before(ChatClientRequest.builder()
                .prompt(new Prompt(List.of(firstRoundUser)))
                .build(), chain);

        ReactCompatibleMessageChatMemoryAdvisor.setConversationId("user:ctx:rc_wiki_assistant");
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "tool", "{}", "")))
                .build();
        ToolResponseMessage toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "tool", "ok")))
                .build();
        ChatClientRequest followUp = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(toolCall, toolResponse)))
                .build();

        advisor.before(followUp, chain);

        verify(chatMemory, never()).get(anyString());
        verify(chatMemory, never()).add(anyString(), any(Message.class));
        verify(chatMemory, never()).add(anyString(), any(List.class));

        ChatClientResponse response = ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("done")))))
                .build();
        advisor.after(response, chain);

        verify(chatMemory, never()).add(anyString(), any(List.class));
    }

    @Test
    void subAgentCallRunSkipsMemoryWhenFlagOnlyInRequestContext() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);

        ReactCompatibleMessageChatMemoryAdvisor.setConversationId("user:ctx:rc_wiki_assistant");
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(new AssistantMessage("tool round"))))
                .context(Map.of(ReactCompatibleMessageChatMemoryAdvisor.META_SUB_AGENT_CALL_RUN, Boolean.TRUE))
                .build();

        advisor.before(request, chain);

        verify(chatMemory, never()).get(anyString());
        verify(chatMemory, never()).add(anyString(), any(Message.class));
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
    void prePersistedUserMessagePreservesSystemMessageFromInstructions() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("user:ctx:universal_assistant")).thenReturn(List.of(
                new UserMessage("hello")));
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);

        SystemMessage systemMessage = new SystemMessage("你是 J2Agent AI 通用助手");
        UserMessage userMessage = UserMessage.builder()
                .text("hello")
                .metadata(Map.of(
                        ChatMemory.CONVERSATION_ID, "user:ctx:universal_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, Boolean.TRUE))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(systemMessage, userMessage)))
                .build();

        ChatClientRequest processed = advisor.before(request, chain);
        List<Message> messages = processed.prompt().getInstructions();

        assertEquals(2, messages.size());
        assertInstanceOf(SystemMessage.class, messages.get(0));
        assertTrue(messages.get(0).getText().contains("J2Agent"));
        verify(chatMemory, never()).add(anyString(), any(Message.class));
    }

    @Test
    void prePersistedUserMessageDoesNotDuplicateUserInPrompt() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("user:ctx:universal_assistant")).thenReturn(List.of(
                new UserMessage("上一轮"),
                new AssistantMessage("上一轮回答"),
                new UserMessage("hello")));
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);

        UserMessage userMessage = UserMessage.builder()
                .text("hello")
                .metadata(Map.of(
                        ChatMemory.CONVERSATION_ID, "user:ctx:universal_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, Boolean.TRUE))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        ChatClientRequest processed = advisor.before(request, chain);
        List<Message> messages = processed.prompt().getInstructions();

        assertEquals(3, messages.size());
        long userCount = messages.stream().filter(UserMessage.class::isInstance).count();
        assertEquals(2, userCount);
        assertEquals("hello", ((UserMessage) messages.get(2)).getText());
        verify(chatMemory, never()).add(anyString(), any(Message.class));
    }

    @Test
    void prePersistedUserMessageReplacesTrailingUserWithLiveMedia() {
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.get("user:ctx:universal_assistant")).thenReturn(List.of(new UserMessage("")));
        ReactCompatibleMessageChatMemoryAdvisor advisor =
                ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        AdvisorChain chain = mock(AdvisorChain.class);
        Media media = Media.builder()
                .mimeType(MediaType.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1}))
                .build();

        UserMessage userMessage = UserMessage.builder()
                .text("")
                .media(media)
                .metadata(Map.of(
                        ChatMemory.CONVERSATION_ID, "user:ctx:universal_assistant",
                        ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, Boolean.TRUE))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        ChatClientRequest processed = advisor.before(request, chain);
        UserMessage lastUser = processed.prompt().getUserMessage();

        assertEquals(1, processed.prompt().getInstructions().size());
        assertFalse(lastUser.getMedia().isEmpty());
        verify(chatMemory, never()).add(anyString(), any(Message.class));
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
