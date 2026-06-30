package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import io.github.jerryt92.j2agent.service.llm.chat.TurnCancelledException;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        ChatTurnCancellationRegistry.clear("turn-1");
    }

    @Test
    void fastPathSkipsDispatchWhenNoCandidates() throws Exception {
        UniversalIntentQueryService intentQueryService = Mockito.mock(UniversalIntentQueryService.class);
        UniversalDispatchDecisionService dispatchDecisionService =
                Mockito.mock(UniversalDispatchDecisionService.class);
        UniversalSubAgentCallService subAgentCallService = Mockito.mock(UniversalSubAgentCallService.class);
        AgentRouter agentRouter = Mockito.mock(AgentRouter.class);
        ChatMemory chatMemory = Mockito.mock(ChatMemory.class);
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(Mockito.mock(AiAgent.class)));
        when(intentQueryService.buildRoutingQuery(
                ArgumentMatchers.eq(chatMemory), anyString(), anyList(), anyString())).thenReturn("routing");
        when(intentQueryService.queryIntentAgents(anyString(), eq("routing"), any())).thenReturn("[]");

        UniversalAssistantOrchestratorHook hook = new UniversalAssistantOrchestratorHook(
                intentQueryService, dispatchDecisionService, subAgentCallService, agentRouter, chatMemory);

        OverAllState state = new OverAllState(Map.of("messages", List.of(new UserMessage("hello"))));
        RunnableConfig config = RunnableConfig.builder().build();
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CONTEXT_ID, "ctx-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_USER_ID, "user-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, "user-1:ctx-1:universal_assistant");

        hook.beforeAgent(state, config).join();

        verify(dispatchDecisionService, never()).decide(anyString(), anyString(), anyList(), anySet(), anyBoolean(), any());
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
    void deliveredPathPassesRoutingQueryAndAttachments() throws Exception {
        UniversalIntentQueryService intentQueryService = Mockito.mock(UniversalIntentQueryService.class);
        UniversalDispatchDecisionService dispatchDecisionService =
                Mockito.mock(UniversalDispatchDecisionService.class);
        UniversalSubAgentCallService subAgentCallService = Mockito.mock(UniversalSubAgentCallService.class);
        AgentRouter agentRouter = Mockito.mock(AgentRouter.class);
        ChatMemory chatMemory = Mockito.mock(ChatMemory.class);
        ChatAttachmentDto attachment = new ChatAttachmentDto().objectKey("chat/u/c/a.png").name("a.png");
        UserMessage userMessage = UserMessage.builder()
                .text("用户原问题")
                .metadata(Map.of("attachments", List.of(attachment)))
                .build();
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(Mockito.mock(AiAgent.class)));
        when(intentQueryService.buildRoutingQuery(
                ArgumentMatchers.eq(chatMemory), anyString(), anyList(), anyString())).thenReturn("routing");
        when(intentQueryService.queryIntentAgents(anyString(), anyString(), any())).thenReturn("[{\"agentId\":\"wiki\"}]");
        when(dispatchDecisionService.decide(anyString(), anyString(), anyList(), anySet(), anyBoolean(), any()))
                .thenReturn(UniversalDispatchDecisionService.DispatchDecision.invoke("wiki", null, "ok"))
                .thenReturn(UniversalDispatchDecisionService.DispatchDecision.complete("done"));
        when(subAgentCallService.call(anyString(), anyString(), any())).thenReturn("answer");

        UniversalAssistantOrchestratorHook hook = new UniversalAssistantOrchestratorHook(
                intentQueryService, dispatchDecisionService, subAgentCallService, agentRouter, chatMemory);

        OverAllState state = new OverAllState(Map.of("messages", List.of(userMessage)));
        RunnableConfig config = RunnableConfig.builder().build();
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CONTEXT_ID, "ctx-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_USER_ID, "user-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, "user-1:ctx-1:universal_assistant");

        hook.beforeAgent(state, config).join();

        ArgumentCaptor<UniversalSubAgentCallService.SubAgentCallRequest> requestCaptor =
                ArgumentCaptor.forClass(UniversalSubAgentCallService.SubAgentCallRequest.class);
        verify(subAgentCallService).call(eq("wiki"), eq("routing"), requestCaptor.capture());
        assertEquals(1, requestCaptor.getValue().attachments().size());
        assertEquals("chat/u/c/a.png", requestCaptor.getValue().attachments().get(0).getObjectKey());
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

    @Test
    void cancelledBeforeOrchestrationSkipsSubAgentCalls() throws Exception {
        ChatTurnCancellationRegistry.cancel("turn-1");

        UniversalIntentQueryService intentQueryService = Mockito.mock(UniversalIntentQueryService.class);
        UniversalDispatchDecisionService dispatchDecisionService =
                Mockito.mock(UniversalDispatchDecisionService.class);
        UniversalSubAgentCallService subAgentCallService = Mockito.mock(UniversalSubAgentCallService.class);
        AgentRouter agentRouter = Mockito.mock(AgentRouter.class);
        ChatMemory chatMemory = Mockito.mock(ChatMemory.class);
        when(agentRouter.listCallableSubAgents()).thenReturn(List.of(Mockito.mock(AiAgent.class)));

        UniversalAssistantOrchestratorHook hook = new UniversalAssistantOrchestratorHook(
                intentQueryService, dispatchDecisionService, subAgentCallService, agentRouter, chatMemory);

        OverAllState state = new OverAllState(Map.of("messages", List.of(new UserMessage("hello"))));
        RunnableConfig config = RunnableConfig.builder().build();
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID, "turn-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CONTEXT_ID, "ctx-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_USER_ID, "user-1");
        config.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, "user-1:ctx-1:universal_assistant");

        CompletionException completionException = assertThrows(CompletionException.class,
                () -> hook.beforeAgent(state, config).join());
        assertTrue(completionException.getCause() instanceof TurnCancelledException);

        verify(intentQueryService, never()).queryIntentAgents(anyString(), anyString(), any());
        verify(subAgentCallService, never()).call(anyString(), anyString(), any());
    }
}
