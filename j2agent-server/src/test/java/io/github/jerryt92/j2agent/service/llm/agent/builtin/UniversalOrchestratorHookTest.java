package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UniversalOrchestratorHookTest {

    @AfterEach
    void tearDown() {
        UniversalOrchestrationRunHolder.unbind("turn-1");
    }

    @Test
    void fastPathSkipsDispatchWhenNoCandidates() throws Exception {
        UniversalIntentQueryService intentQueryService = Mockito.mock(UniversalIntentQueryService.class);
        UniversalDispatchDecisionService dispatchDecisionService =
                Mockito.mock(UniversalDispatchDecisionService.class);
        UniversalSubAgentCallService subAgentCallService = Mockito.mock(UniversalSubAgentCallService.class);
        AgentRouter agentRouter = Mockito.mock(AgentRouter.class);
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(Mockito.mock(AiAgent.class)));
        when(intentQueryService.buildRoutingQueryFromMessages(anyList(), anyString())).thenReturn("routing");
        when(intentQueryService.queryIntentAgents(anyString(), eq("routing"))).thenReturn("[]");

        UniversalAssistantOrchestratorHook hook = new UniversalAssistantOrchestratorHook(
                intentQueryService, dispatchDecisionService, subAgentCallService, agentRouter);

        OverAllState state = new OverAllState(Map.of("messages", List.of(new UserMessage("hello"))));
        RunnableConfig config = RunnableConfig.builder().build();
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CONTEXT_ID, "ctx-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_USER_ID, "user-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, "user-1:ctx-1:universal_assistant");

        hook.beforeAgent(state, config).join();

        verify(dispatchDecisionService, never()).decide(anyString(), anyString(), anyList(), anySet(), anyBoolean());
        verify(subAgentCallService, never()).call(anyString(), anyString(), any());
        assertTrue(Boolean.TRUE.equals(config.context().get(UniversalOrchestrationContextKeys.ORCHESTRATION_SKIPPED)));

        OrchestrationModelInterceptor interceptor = new OrchestrationModelInterceptor();
        Map<String, Object> context = Map.of(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1");
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        interceptor.interceptModel(
                ModelRequest.builder().systemMessage(null).messages(List.of()).context(context).build(),
                req -> {
                    handlerCalled.set(true);
                    return new ModelResponse(null);
                });
        assertTrue(handlerCalled.get());
    }

    @Test
    void deliveredPathDoesNotPersistUserMessageInHook() throws Exception {
        UniversalIntentQueryService intentQueryService = Mockito.mock(UniversalIntentQueryService.class);
        UniversalDispatchDecisionService dispatchDecisionService =
                Mockito.mock(UniversalDispatchDecisionService.class);
        UniversalSubAgentCallService subAgentCallService = Mockito.mock(UniversalSubAgentCallService.class);
        AgentRouter agentRouter = Mockito.mock(AgentRouter.class);
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(Mockito.mock(AiAgent.class)));
        when(intentQueryService.buildRoutingQueryFromMessages(anyList(), anyString())).thenReturn("routing");
        when(intentQueryService.queryIntentAgents(anyString(), anyString())).thenReturn("[{\"agentId\":\"wiki\"}]");
        when(dispatchDecisionService.decide(anyString(), anyString(), anyList(), anySet(), anyBoolean()))
                .thenReturn(UniversalDispatchDecisionService.DispatchDecision.invoke("wiki", "查文档", "ok"))
                .thenReturn(UniversalDispatchDecisionService.DispatchDecision.complete("done"));
        when(subAgentCallService.call(anyString(), anyString(), any())).thenReturn("answer");

        UniversalAssistantOrchestratorHook hook = new UniversalAssistantOrchestratorHook(
                intentQueryService, dispatchDecisionService, subAgentCallService, agentRouter);

        UserMessage userMessage = new UserMessage("用户原问题");
        OverAllState state = new OverAllState(Map.of("messages", List.of(userMessage)));
        RunnableConfig config = RunnableConfig.builder().build();
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CONTEXT_ID, "ctx-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_USER_ID, "user-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, "user-1:ctx-1:universal_assistant");

        hook.beforeAgent(state, config).join();

        assertTrue(Boolean.TRUE.equals(config.context().get(UniversalOrchestrationContextKeys.ORCHESTRATION_DELIVERED)));
    }

    @Test
    void deliveredPathShortCircuitsMainLlm() {
        UniversalOrchestrationRunHolder.bind("turn-1", new UniversalOrchestrationRunHolder.Flags(false, true));
        OrchestrationModelInterceptor interceptor = new OrchestrationModelInterceptor();
        Map<String, Object> context = Map.of(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1");
        AtomicBoolean handlerCalled = new AtomicBoolean(false);
        interceptor.interceptModel(
                ModelRequest.builder().messages(List.of()).context(context).build(),
                req -> {
                    handlerCalled.set(true);
                    return new ModelResponse(null);
                });
        assertTrue(!handlerCalled.get());
    }
}
