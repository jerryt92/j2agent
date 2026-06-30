package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestrationModelInterceptorTest {

    @AfterEach
    void tearDown() {
        UniversalOrchestrationRunHolder.unbind("turn-1");
    }

    @Test
    void skipsMainLlmWhenDelivered() {
        UniversalOrchestrationRunHolder.bind("turn-1", new UniversalOrchestrationRunHolder.Flags(false, true));
        OrchestrationModelInterceptor interceptor = new OrchestrationModelInterceptor();
        Map<String, Object> context = new HashMap<>();
        context.put(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1");
        ModelRequest request = ModelRequest.builder()
                .systemMessage(new SystemMessage("base"))
                .messages(List.of())
                .context(context)
                .build();
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        ModelCallHandler handler = req -> {
            handlerCalled.set(true);
            return new ModelResponse(null);
        };
        ModelResponse response = interceptor.interceptModel(request, handler);
        assertTrue(!handlerCalled.get());
        assertTrue(response.getMessage() instanceof AssistantMessage);
        assertEquals("", ((AssistantMessage) response.getMessage()).getText());
    }

    @Test
    void injectsSkippedHintWhenNoCandidates() {
        UniversalOrchestrationRunHolder.bind("turn-1", new UniversalOrchestrationRunHolder.Flags(true, false));
        OrchestrationModelInterceptor interceptor = new OrchestrationModelInterceptor();
        Map<String, Object> context = Map.of(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1");
        ModelRequest request = ModelRequest.builder()
                .systemMessage(new SystemMessage("base prompt"))
                .messages(List.of())
                .context(context)
                .build();
        ModelCallHandler handler = req -> {
            assertTrue(req.getSystemMessage().getText().contains("无专业子智能体候选"));
            return new ModelResponse(null);
        };
        interceptor.interceptModel(request, handler);
    }
}
