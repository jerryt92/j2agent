package io.github.jerryt92.j2agent.service.llm.rag;

import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.FileDto;
import io.github.jerryt92.j2agent.model.RagInfoDto;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        TurnRagSourceRegistry.shareHolder(RUNTIME_ID, PERSIST_ID);

        FileDto file = new FileDto().fullFileName("a.md");
        RagInfoDto info = new RagInfoDto().textChunkId("chunk-1");
        TurnRagSourceRegistry.publishSources(RUNTIME_ID, List.of(file), List.of(info), false);

        String ragJson = TurnRagSourceRegistry.drainRagInfosJson(PERSIST_ID);
        assertNotNull(ragJson);
        assertEquals(ragJson, TurnRagSourceRegistry.drainRagInfosJson(RUNTIME_ID));

        TurnRagSourceRegistry.unshareHolder(RUNTIME_ID);
        assertEquals(ragJson, TurnRagSourceRegistry.drainRagInfosJson(PERSIST_ID));
        assertEquals(null, TurnRagSourceRegistry.drainRagInfosJson(RUNTIME_ID));
    }
}
