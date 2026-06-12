package io.github.jerryt92.j2agent.service.llm.advisor;

import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.ChatResponseDto;
import io.github.jerryt92.j2agent.model.MessageDto;
import io.github.jerryt92.j2agent.service.file.StaticFileService;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.rag.TurnRagSourceRegistry;
import io.github.jerryt92.j2agent.service.rag.RagSourceFileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EmptyQuerySkippingRetrievalAugmentationAdvisorTest {

    private static final String CONVERSATION_ID = "user-1:ctx-1:chat_assistant";

    private RagSourceFileService ragSourceFileService;

    @BeforeEach
    void setUp() {
        StaticFileService staticFileService = mock(StaticFileService.class);
        org.mockito.Mockito.when(staticFileService.getKnowledgeRepoFile(org.mockito.ArgumentMatchers.eq("docs/a.md")))
                .thenReturn(new ByteArrayResource("x".getBytes()));
        ragSourceFileService = new RagSourceFileService(staticFileService);
    }

    @AfterEach
    void tearDown() {
        TurnRagSourceRegistry.clear(CONVERSATION_ID);
    }

    @Test
    void shouldSkipRagWhenUserInputCompletelyEmpty() {
        DocumentRetriever retriever = mock(DocumentRetriever.class);
        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
        EmptyQuerySkippingRetrievalAugmentationAdvisor advisor =
                EmptyQuerySkippingRetrievalAugmentationAdvisor.wrap(ragAdvisor, ragSourceFileService, true);
        AdvisorChain chain = mock(AdvisorChain.class);

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(UserMessage.builder().text("").build())))
                .build();

        ChatClientRequest processed = advisor.before(request, chain);

        assertTrue(Boolean.TRUE.equals(processed.context().get(EmptyQuerySkippingRetrievalAugmentationAdvisor.SKIP_RAG_CONTEXT_KEY)));
        verify(retriever, never()).retrieve(any());
    }

    @Test
    void shouldNotSkipRagWhenImagePresentWithoutText() {
        Media media = Media.builder()
                .mimeType(MediaType.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1}))
                .build();
        UserMessage userMessage = UserMessage.builder().text("").media(media).build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        assertFalse(EmptyQuerySkippingRetrievalAugmentationAdvisor.shouldSkipRag(request));
    }

    @Test
    void shouldNotThrowForImageOnlyRequest() {
        DocumentRetriever retriever = mock(DocumentRetriever.class);
        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
        EmptyQuerySkippingRetrievalAugmentationAdvisor advisor =
                EmptyQuerySkippingRetrievalAugmentationAdvisor.wrap(ragAdvisor, ragSourceFileService, true);
        AdvisorChain chain = mock(AdvisorChain.class);
        Media media = Media.builder()
                .mimeType(MediaType.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1}))
                .build();
        UserMessage userMessage = UserMessage.builder().text("").media(media).build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        ChatClientRequest processed = advisor.before(request, chain);

        assertFalse(Boolean.TRUE.equals(processed.context().get(EmptyQuerySkippingRetrievalAugmentationAdvisor.SKIP_RAG_CONTEXT_KEY)));
    }

    @Test
    void shouldNotSkipRagWhenAttachmentsMetadataPresent() {
        ChatAttachmentDto attachment = new ChatAttachmentDto();
        attachment.setObjectKey("chat/u/c/x.png");
        UserMessage userMessage = UserMessage.builder()
                .text("")
                .metadata(Map.of("attachments", List.of(attachment)))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        assertFalse(EmptyQuerySkippingRetrievalAugmentationAdvisor.shouldSkipRag(request));
    }

    @Test
    void shouldSkipRagAfterWhenMarkedInContext() {
        DocumentRetriever retriever = mock(DocumentRetriever.class);
        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
        EmptyQuerySkippingRetrievalAugmentationAdvisor advisor =
                EmptyQuerySkippingRetrievalAugmentationAdvisor.wrap(ragAdvisor, ragSourceFileService, true);
        AdvisorChain chain = mock(AdvisorChain.class);
        ChatClientResponse response = ChatClientResponse.builder()
                .context(Map.of(EmptyQuerySkippingRetrievalAugmentationAdvisor.SKIP_RAG_CONTEXT_KEY, true))
                .build();

        ChatClientResponse processed = advisor.after(response, chain);

        assertSame(response, processed);
    }

    @Test
    void shouldPublishRagSourcesWhenRetrievalReturnsMdDocuments() {
        DocumentRetriever retriever = query -> List.of(
                Document.builder()
                        .text("chunk")
                        .metadata(Map.of("sourceFile", "docs/a.md", "textChunkId", "chunk-1"))
                        .build()
        );
        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
        EmptyQuerySkippingRetrievalAugmentationAdvisor advisor =
                EmptyQuerySkippingRetrievalAugmentationAdvisor.wrap(ragAdvisor, ragSourceFileService, true);
        AdvisorChain chain = mock(AdvisorChain.class);

        List<AgentUiEventEnvelope> events = new ArrayList<>();
        TurnRagSourceRegistry.bind(
                CONVERSATION_ID,
                events::add,
                new Object(),
                "ctx-1",
                "turn-1",
                new AtomicLong(0L),
                new AgentTurnStateMachine(),
                1);

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(UserMessage.builder()
                        .text("hello")
                        .metadata(Map.of(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                        .build())))
                .build();

        advisor.before(request, chain);

        assertEquals(1, events.size());
        AgentUiEventEnvelope envelope = events.getFirst();
        assertEquals(AgentEventType.MESSAGE, envelope.getEventType());
        assertEquals(AgentEventPhase.PATCH, envelope.getPhase());
        ChatResponseDto payload = (ChatResponseDto) envelope.getPayload();
        MessageDto message = payload.getMessage();
        assertEquals(MessageDto.RoleEnum.ASSISTANT, message.getRole());
        assertEquals(1, message.getSrcFile().size());
        assertEquals("a.md", message.getSrcFile().getFirst().getFullFileName());
        String ragInfosJson = TurnRagSourceRegistry.drainRagInfosJson(CONVERSATION_ID);
        assertNotNull(ragInfosJson);
        assertTrue(ragInfosJson.contains("a.md"));
    }

    @Test
    void shouldNotPublishRagSourcesWhenDisplayDisabled() {
        DocumentRetriever retriever = query -> List.of(
                Document.builder()
                        .text("chunk")
                        .metadata(Map.of("sourceFile", "docs/a.md", "textChunkId", "chunk-1"))
                        .build()
        );
        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
        EmptyQuerySkippingRetrievalAugmentationAdvisor advisor =
                EmptyQuerySkippingRetrievalAugmentationAdvisor.wrap(ragAdvisor, ragSourceFileService, false);
        AdvisorChain chain = mock(AdvisorChain.class);

        List<AgentUiEventEnvelope> events = new ArrayList<>();
        TurnRagSourceRegistry.bind(
                CONVERSATION_ID,
                events::add,
                new Object(),
                "ctx-1",
                "turn-1",
                new AtomicLong(0L),
                new AgentTurnStateMachine(),
                1);

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(UserMessage.builder()
                        .text("hello")
                        .metadata(Map.of(ChatMemory.CONVERSATION_ID, CONVERSATION_ID))
                        .build())))
                .build();

        advisor.before(request, chain);

        assertTrue(events.isEmpty());
        String ragInfosJson = TurnRagSourceRegistry.drainRagInfosJson(CONVERSATION_ID);
        assertNotNull(ragInfosJson);
        assertTrue(ragInfosJson.contains("a.md"));
    }
}
