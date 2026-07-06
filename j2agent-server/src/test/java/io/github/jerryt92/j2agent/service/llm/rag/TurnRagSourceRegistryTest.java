package io.github.jerryt92.j2agent.service.llm.rag;

import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatResponseDto;
import io.github.jerryt92.j2agent.model.FileDto;
import io.github.jerryt92.j2agent.model.MessageDto;
import io.github.jerryt92.j2agent.model.RagInfoDto;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnRagSourceRegistryTest {

    private static final String PERSIST_ID = "user:ctx:universal_assistant";
    private static final String RUNTIME_ID = "user:ctx:rc_wiki_assistant";

    @AfterEach
    void tearDown() {
        TurnRagSourceRegistry.clear(PERSIST_ID);
        TurnRagSourceRegistry.unshareHolder(RUNTIME_ID);
    }

    @Test
    void shareHolderRoutesPublishAndDrainToPersistKey() {
        TurnRagSourceRegistry.bind(
                PERSIST_ID,
                (Consumer<AgentUiEventEnvelope>) envelope -> {
                },
                new Object(),
                "ctx",
                "turn",
                new AtomicLong(),
                new AgentTurnStateMachine(),
                0);
        assertTrue(TurnRagSourceRegistry.shareHolder(RUNTIME_ID, PERSIST_ID));

        FileDto file = new FileDto().fullFileName("a.md").relativePath("docs/a.md");
        RagInfoDto info = new RagInfoDto().textChunkId("chunk-1").srcFile(file);
        TurnRagSourceRegistry.publishSources(RUNTIME_ID, List.of(file), List.of(info), false);

        String ragJson = TurnRagSourceRegistry.drainRagInfosJson(PERSIST_ID);
        assertNotNull(ragJson);
        assertEquals(ragJson, TurnRagSourceRegistry.drainRagInfosJson(RUNTIME_ID));

        TurnRagSourceRegistry.unshareHolder(RUNTIME_ID);
        assertEquals(ragJson, TurnRagSourceRegistry.drainRagInfosJson(PERSIST_ID));
        assertNull(TurnRagSourceRegistry.drainRagInfosJson(RUNTIME_ID));
    }

    @Test
    void publishSourcesMergesMultipleCallsWithinTurn() {
        List<AgentUiEventEnvelope> events = new ArrayList<>();
        TurnRagSourceRegistry.bind(
                PERSIST_ID,
                events::add,
                new Object(),
                "ctx",
                "turn",
                new AtomicLong(0L),
                new AgentTurnStateMachine(),
                1);

        FileDto fileA = new FileDto().fullFileName("a.md").relativePath("docs/a.md");
        RagInfoDto infoA = new RagInfoDto().textChunkId("chunk-a").srcFile(fileA);
        TurnRagSourceRegistry.publishSources(PERSIST_ID, List.of(fileA), List.of(infoA), true);

        FileDto fileB = new FileDto().fullFileName("b.md").relativePath("docs/b.md");
        RagInfoDto infoB = new RagInfoDto().textChunkId("chunk-b").srcFile(fileB);
        TurnRagSourceRegistry.publishSources(PERSIST_ID, List.of(fileB), List.of(infoB), true);

        assertEquals(2, events.size());
        MessageDto lastMessage = ((ChatResponseDto) events.getLast().getPayload()).getMessage();
        assertEquals(2, lastMessage.getSrcFile().size());

        String ragJson = TurnRagSourceRegistry.drainRagInfosJson(PERSIST_ID);
        assertNotNull(ragJson);
        assertTrue(ragJson.contains("a.md"));
        assertTrue(ragJson.contains("b.md"));
    }

    @Test
    void shareHolderReturnsFalseWhenParentMissing() {
        assertTrue(!TurnRagSourceRegistry.shareHolder(RUNTIME_ID, PERSIST_ID));
    }

    @Test
    void displayFalseThenDisplayTruePushesPatch() {
        List<AgentUiEventEnvelope> events = new ArrayList<>();
        TurnRagSourceRegistry.bind(
                PERSIST_ID,
                events::add,
                new Object(),
                "ctx",
                "turn",
                new AtomicLong(0L),
                new AgentTurnStateMachine(),
                1);

        FileDto file = new FileDto().fullFileName("a.md").relativePath("docs/a.md");
        RagInfoDto info = new RagInfoDto().textChunkId("chunk-1").srcFile(file);
        TurnRagSourceRegistry.publishSources(PERSIST_ID, List.of(file), List.of(info), false);
        assertEquals(0, events.size());

        TurnRagSourceRegistry.publishSources(PERSIST_ID, List.of(file), List.of(info), true);
        assertEquals(1, events.size());
        assertEquals(AgentEventPhase.PATCH, events.getFirst().getPhase());
        assertEquals(AgentEventType.MESSAGE, events.getFirst().getEventType());
        MessageDto message = ((ChatResponseDto) events.getFirst().getPayload()).getMessage();
        assertEquals(1, message.getSrcFile().size());
    }

    @Test
    void clearRemovesAllAliasKeys() {
        TurnRagSourceRegistry.bind(
                PERSIST_ID,
                (Consumer<AgentUiEventEnvelope>) envelope -> {
                },
                new Object(),
                "ctx",
                "turn",
                new AtomicLong(),
                new AgentTurnStateMachine(),
                0);
        assertTrue(TurnRagSourceRegistry.shareHolder(RUNTIME_ID, PERSIST_ID));

        TurnRagSourceRegistry.clear(PERSIST_ID);

        assertTrue(!TurnRagSourceRegistry.hasHolder(PERSIST_ID));
        assertTrue(!TurnRagSourceRegistry.hasHolder(RUNTIME_ID));
    }
}
