package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamOptions;
import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamSession;
import io.github.jerryt92.j2agent.service.llm.agent.StreamingTextParts;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunContext;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.tool.AgentUiToolEventInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class UniversalSubAgentCallServiceTest {

    private AgentRouter agentRouter;
    private AgentStreamSession agentStreamSession;
    private UniversalSubAgentCallService subAgentCallService;

    @BeforeEach
    void setUp() {
        agentRouter = Mockito.mock(AgentRouter.class);
        agentStreamSession = Mockito.mock(AgentStreamSession.class);
        subAgentCallService = new UniversalSubAgentCallService(agentRouter, agentStreamSession);
    }

    @AfterEach
    void tearDown() {
        SubAgentStreamBridge.unbind("turn-1");
    }

    @Test
    void callUsesSpecialistConversationIdAndFullMemory() {
        AiAgent wikiAgent = Mockito.mock(AiAgent.class);
        when(wikiAgent.getAgentId()).thenReturn("j2agent-qa-assistant");
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(wikiAgent));
        when(agentRouter.route("j2agent-qa-assistant")).thenReturn(wikiAgent);

        ArgumentCaptor<AgentStreamOptions> optionsCaptor = ArgumentCaptor.forClass(AgentStreamOptions.class);
        when(agentStreamSession.stream(optionsCaptor.capture()))
                .thenReturn(Flux.just(new StreamingTextParts("wiki answer", null)));

        StringBuilder streamedContent = new StringBuilder();
        AgentTurnStateMachine stateMachine = new AgentTurnStateMachine();
        Object turnLock = new Object();
        SubAgentStreamBridge.bind("turn-1", new SubAgentStreamBridge.Target(
                null,
                "ctx-1",
                "turn-1",
                "user-1",
                "user-1:ctx-1:universal_assistant",
                null,
                new AtomicLong(0),
                stateMachine, turnLock, streamedContent, new StringBuilder(), new Object(), 0));

        Map<String, Object> ctx = new HashMap<>();
        ctx.put(AgentRunnableContextKeys.CONTEXT_KEY_CONTEXT_ID, "ctx-1");
        ctx.put(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1");
        ctx.put(AgentRunnableContextKeys.CONTEXT_KEY_USER_ID, "user-1");
        ctx.put(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, "user-1:ctx-1:universal_assistant");
        ctx.put(AgentUiToolEventInterceptor.CONTEXT_KEY_TOOL_EVENT_EMITTER, null);

        String result = subAgentCallService.call(
                "j2agent-qa-assistant",
                "查文档",
                new UniversalSubAgentCallService.SubAgentCallRequest(
                        "ctx-1", "turn-1", "user-1", "user-1:ctx-1:universal_assistant", null));

        assertEquals("wiki answer", result);
        AgentRunContext runContext = optionsCaptor.getValue().agentRunContext();
        assertEquals("user-1:ctx-1:j2agent-qa-assistant", runContext.conversationId());
        assertTrue(runContext.subAgentCallRun());
    }

    @Test
    void callRejectsUnknownAgent() {
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of());
        String result = subAgentCallService.call(
                "missing",
                "q",
                new UniversalSubAgentCallService.SubAgentCallRequest(
                        "ctx-1", "turn-1", "user-1", "user-1:ctx-1:universal_assistant", null));
        assertTrue(result.startsWith("Error:"));
    }
}
