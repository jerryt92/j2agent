package io.github.jerryt92.j2agent.tools;

import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.question.TurnAskQuestionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AskQuestionTool 参数校验与发布行为单测。
 */
class AskQuestionToolTest {

    private static final String CONVERSATION_ID = "user:ctx:agent";

    private AskQuestionTool tool;

    @BeforeEach
    void setUp() {
        tool = new AskQuestionTool();
        TurnAskQuestionRegistry.clear(CONVERSATION_ID);
        TurnAskQuestionRegistry.bind(
                CONVERSATION_ID,
                e -> {},
                new Object(),
                "ctx",
                "turn-1",
                new AtomicLong(1),
                new AgentTurnStateMachine(),
                3);
    }

    @AfterEach
    void tearDown() {
        TurnAskQuestionRegistry.clear(CONVERSATION_ID);
    }

    @Test
    void askQuestion_validParams_publishesAndReturnsStopInstruction() {
        ToolContext ctx = new ToolContext(Map.of(
                AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, CONVERSATION_ID,
                AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1"));

        String result = tool.askQuestion("请选择范围", "[\"全部设备\",\"指定设备\"]", ctx);

        assertTrue(result.contains("结束本轮对话"));
        String questionJson = TurnAskQuestionRegistry.drainQuestionJson(CONVERSATION_ID);
        assertTrue(questionJson != null && questionJson.contains("请选择范围"));
        assertTrue(questionJson.contains("全部设备"));
    }

    @Test
    void callbackCall_validParams_publishesAndReturnsStopInstruction() {
        ToolContext ctx = new ToolContext(Map.of(
                AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, CONVERSATION_ID,
                AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1"));

        String result = tool.call("{\"question\":\"请选择范围\",\"optionsJson\":\"[\\\"全部设备\\\",\\\"指定设备\\\"]\"}", ctx);

        assertTrue(result.contains("结束本轮对话"));
        String questionJson = TurnAskQuestionRegistry.drainQuestionJson(CONVERSATION_ID);
        assertTrue(questionJson != null && questionJson.contains("请选择范围"));
        assertTrue(questionJson.contains("全部设备"));
    }

    @Test
    void askQuestion_blankQuestion_returnsError() {
        String result = tool.askQuestion(" ", "[\"A\"]", new ToolContext(Map.of()));
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void askQuestion_invalidOptionsJson_returnsError() {
        String result = tool.askQuestion("请选择范围", "not-json", new ToolContext(Map.of()));
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void askQuestion_emptyOptions_returnsError() {
        String result = tool.askQuestion("请选择范围", "[]", new ToolContext(Map.of()));
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void askQuestion_reservedFallbackOptions_areFiltered() {
        ToolContext ctx = new ToolContext(Map.of(
                AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, CONVERSATION_ID,
                AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1"));

        String result = tool.askQuestion("请选择范围", "[\"全部设备\",\"自定义\",\"其他（请填写）\",\"指定设备\"]", ctx);

        assertTrue(result.contains("结束本轮对话"));
        String questionJson = TurnAskQuestionRegistry.drainQuestionJson(CONVERSATION_ID);
        assertTrue(questionJson.contains("全部设备"));
        assertTrue(questionJson.contains("指定设备"));
        assertFalse(questionJson.contains("自定义"));
        assertFalse(questionJson.contains("其他"));
    }
}
