package io.github.jerryt92.j2agent.service.llm.chat;

import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatCallback;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatTurnLifecycleCancelTest {

    @AfterEach
    void tearDown() {
        ChatTurnCancellationRegistry.clear("turn-1");
    }

    @Test
    void emitAnswerDeltaSkipsWhenTurnCancelled() {
        ChatTurnCancellationRegistry.cancel("turn-1");
        ChatCallback<AgentUiEventEnvelope> callback = new ChatCallback<>("sub-1");
        AtomicBoolean sent = new AtomicBoolean(false);
        callback.responseCall = envelope -> sent.set(true);
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Object lock = new Object();

        ChatTurnLifecycle.emitAnswerDelta(
                callback,
                "ctx-1",
                "turn-1",
                new AtomicLong(0),
                new AgentTurnStateMachine(),
                lock,
                content,
                reasoning,
                lock,
                0,
                "hello",
                null);

        assertFalse(sent.get());
        assertFalse(content.toString().contains("hello"));
    }
}
