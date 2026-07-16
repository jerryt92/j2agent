package io.github.jerryt92.j2agent.service.question;

import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.AskQuestionDto;
import io.github.jerryt92.j2agent.model.ChatResponseDto;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TurnAskQuestionRegistry 推送与 drain 行为单测。
 */
class TurnAskQuestionRegistryTest {

    private static final String CONVERSATION_ID = "user:ctx:agent";

    @AfterEach
    void tearDown() {
        TurnAskQuestionRegistry.clear(CONVERSATION_ID);
    }

    @Test
    void publishQuestion_emitsPatchWithPendingQuestion() {
        List<AgentUiEventEnvelope> events = new ArrayList<>();
        TurnAskQuestionRegistry.bind(
                CONVERSATION_ID,
                events::add,
                new Object(),
                "ctx",
                "turn-1",
                new AtomicLong(1),
                new AgentTurnStateMachine(),
                3);

        TurnAskQuestionRegistry.publishQuestion(CONVERSATION_ID, sampleQuestion());

        assertEquals(1, events.size());
        AgentUiEventEnvelope envelope = events.getFirst();
        assertEquals(AgentEventType.MESSAGE, envelope.getEventType());
        assertEquals(AgentEventPhase.PATCH, envelope.getPhase());
        ChatResponseDto payload = (ChatResponseDto) envelope.getPayload();
        assertNotNull(payload.getMessage());
        assertNotNull(payload.getMessage().getPendingQuestion());
        assertEquals("请选择范围", payload.getMessage().getPendingQuestion().getQuestion());
        String json = TurnAskQuestionRegistry.drainQuestionJson(CONVERSATION_ID);
        assertTrue(json.contains("全部设备"));
    }

    private static AskQuestionDto sampleQuestion() {
        return new AskQuestionDto()
                .type(AskQuestionDto.TypeEnum.ASK_QUESTION)
                .version(1)
                .question("请选择范围")
                .options(List.of("全部设备", "指定设备"));
    }
}
