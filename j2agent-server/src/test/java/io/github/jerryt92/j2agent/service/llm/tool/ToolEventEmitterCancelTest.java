package io.github.jerryt92.j2agent.service.llm.tool;

import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ToolEventEmitterCancelTest {

    @AfterEach
    void tearDown() {
        ChatTurnCancellationRegistry.clear("turn-1");
    }

    @Test
    void onToolStartSkipsWhenTurnCancelled() {
        ChatTurnCancellationRegistry.cancel("turn-1");
        AtomicBoolean sent = new AtomicBoolean(false);
        ToolEventEmitter emitter = new ToolEventEmitter(
                "ctx-1",
                "turn-1",
                new AtomicLong(0),
                new AgentTurnStateMachine(),
                new Object(),
                envelope -> sent.set(true));

        emitter.onToolStart("call-1", "search", "{}");

        assertFalse(sent.get());
    }
}
