package io.github.jerryt92.j2agent.service.rag;

import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatResponseDto;
import io.github.jerryt92.j2agent.model.FileDto;
import io.github.jerryt92.j2agent.model.MessageDto;
import io.github.jerryt92.j2agent.model.RagInfoDto;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.rag.TurnRagSourceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagSourcePublicationServiceTest {

    private static final String CONVERSATION_ID = "user:ctx:chat_assistant";

    private RagSourceFileService ragSourceFileService;
    private AgentRouter agentRouter;
    private RagSourcePublicationService publicationService;

    @BeforeEach
    void setUp() {
        ragSourceFileService = mock(RagSourceFileService.class);
        agentRouter = mock(AgentRouter.class);
        publicationService = new RagSourcePublicationService(ragSourceFileService, agentRouter);
        TurnRagSourceRegistry.bind(
                CONVERSATION_ID,
                envelope -> {
                },
                new Object(),
                "ctx",
                "turn",
                new AtomicLong(),
                new AgentTurnStateMachine(),
                0);
    }

    @AfterEach
    void tearDown() {
        TurnRagSourceRegistry.clear(CONVERSATION_ID);
    }

    @Test
    void tryPublishCollectsSourcesWhenDisplayDisabled() {
        String path = "docs/a.md";
        FileDto file = new FileDto().fullFileName("a.md").relativePath(path);
        RagInfoDto info = new RagInfoDto().textChunkId("id-1").srcFile(file);
        when(ragSourceFileService.resolveUniqueMdSources(any()))
                .thenReturn(new RagSourceFileService.ResolvedRagSources(
                        List.of(file), List.of(info), RagSourceFileService.ResolveStats.empty()));
        AiAgent agent = mock(AiAgent.class);
        when(agent.isRagSourceDisplayEnabled()).thenReturn(false);
        when(agentRouter.route("chat_assistant")).thenReturn(agent);

        Document doc = Document.builder()
                .text("chunk")
                .metadata(Map.of("sourceFile", path))
                .build();
        publicationService.tryPublish(CONVERSATION_ID, null, List.of(doc));

        assertNotNull(TurnRagSourceRegistry.drainRagInfosJson(CONVERSATION_ID));
    }

    @Test
    void tryPublishPushesPatchWhenDisplayEnabled() {
        String path = "docs/a.md";
        FileDto file = new FileDto().fullFileName("a.md").relativePath(path);
        RagInfoDto info = new RagInfoDto().textChunkId("id-1").srcFile(file);
        when(ragSourceFileService.resolveUniqueMdSources(any()))
                .thenReturn(new RagSourceFileService.ResolvedRagSources(
                        List.of(file), List.of(info),
                        new RagSourceFileService.ResolveStats(1, 1, 0, 0, 0, 0, 0)));
        AiAgent agent = mock(AiAgent.class);
        when(agent.isRagSourceDisplayEnabled()).thenReturn(true);
        when(agentRouter.route("chat_assistant")).thenReturn(agent);

        List<AgentUiEventEnvelope> events = new ArrayList<>();
        TurnRagSourceRegistry.clear(CONVERSATION_ID);
        TurnRagSourceRegistry.bind(
                CONVERSATION_ID,
                events::add,
                new Object(),
                "ctx",
                "turn",
                new AtomicLong(0L),
                new AgentTurnStateMachine(),
                1);

        Document doc = Document.builder()
                .text("chunk")
                .metadata(Map.of("sourceFile", path, "textChunkId", "id-1"))
                .build();
        publicationService.tryPublish(CONVERSATION_ID, null, List.of(doc));

        assertEquals(1, events.size());
        assertEquals(AgentEventType.MESSAGE, events.getFirst().getEventType());
        assertEquals(AgentEventPhase.PATCH, events.getFirst().getPhase());
        MessageDto message = ((ChatResponseDto) events.getFirst().getPayload()).getMessage();
        assertEquals(1, message.getSrcFile().size());
        assertTrue(TurnRagSourceRegistry.drainRagInfosJson(CONVERSATION_ID).contains("a.md"));
    }
}
