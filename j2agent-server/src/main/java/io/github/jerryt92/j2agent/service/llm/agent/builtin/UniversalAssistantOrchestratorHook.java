package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.llm.tool.AgentUiToolEventInterceptor;
import io.github.jerryt92.j2agent.service.llm.tool.ToolEventEmitter;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import io.github.jerryt92.j2agent.service.llm.chat.TurnCancelledException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 通用助手统一编排 Hook：开放召回、决策与子智能体调用；有子调用时跳过主 ReAct。
 */
@Component
public class UniversalAssistantOrchestratorHook extends AgentHook {

    private static final int MAX_ORCHESTRATION_ROUNDS = 3;

    private final UniversalIntentQueryService intentQueryService;
    private final UniversalDispatchDecisionService dispatchDecisionService;
    private final UniversalSubAgentCallService subAgentCallService;
    private final AgentRouter agentRouter;
    private final ChatMemory chatMemory;
    private final OrchestrationModelInterceptor orchestrationModelInterceptor = new OrchestrationModelInterceptor();

    public UniversalAssistantOrchestratorHook(
            UniversalIntentQueryService intentQueryService,
            UniversalDispatchDecisionService dispatchDecisionService,
            UniversalSubAgentCallService subAgentCallService,
            AgentRouter agentRouter,
            ChatMemory chatMemory) {
        this.intentQueryService = intentQueryService;
        this.dispatchDecisionService = dispatchDecisionService;
        this.subAgentCallService = subAgentCallService;
        this.agentRouter = agentRouter;
        this.chatMemory = chatMemory;
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        if (Boolean.TRUE.equals(config.context().get(UniversalOrchestrationContextKeys.ORCHESTRATION_DONE))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (Boolean.TRUE.equals(config.context().get(AgentRunnableContextKeys.CONTEXT_KEY_SUB_AGENT_CALL_RUN))) {
            return CompletableFuture.completedFuture(Map.of());
        }
        if (agentRouter.listCallableSubAgents().isEmpty()) {
            markDone(config, false, false);
            return CompletableFuture.completedFuture(Map.of());
        }

        TurnKeys turnKeys = resolveTurnKeys(config);
        if (turnKeys == null) {
            markDone(config, false, false);
            return CompletableFuture.completedFuture(Map.of());
        }
        if (ChatTurnCancellationRegistry.isCancelled(turnKeys.turnId())) {
            return abortOnCancel(turnKeys.turnId(), config, false, false);
        }

        @SuppressWarnings("unchecked")
        List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());
        List<OrchestrationTraceEntry> trace = new ArrayList<>();
        Set<String> invokedAgentIds = new LinkedHashSet<>();

        List<ChatAttachmentDto> turnAttachments = UniversalIntentQueryService.resolveLatestAttachments(
                messages, chatMemory, turnKeys.parentConversationId());

        String routingQuery = intentQueryService.buildRoutingQuery(
                chatMemory, turnKeys.parentConversationId(), messages, formatTrace(trace));
        String candidates;
        try {
            candidates = intentQueryService.queryIntentAgents(
                    turnKeys.parentConversationId(), routingQuery, turnKeys.turnId());
        } catch (TurnCancelledException ex) {
            return abortOnCancel(turnKeys.turnId(), config, false, false);
        }

        if (UniversalIntentQueryService.isCandidatesEmpty(candidates)) {
            markSkipped(config, turnKeys.turnId());
            return CompletableFuture.completedFuture(Map.of());
        }

        ToolEventEmitter emitter = turnKeys.toolEventEmitter();
        boolean schedulingEmitted = false;
        boolean delivered = false;
        int round = 0;
        while (round < MAX_ORCHESTRATION_ROUNDS) {
            if (ChatTurnCancellationRegistry.isCancelled(turnKeys.turnId())) {
                return abortOnCancel(turnKeys.turnId(), config, false, delivered);
            }
            if (!schedulingEmitted && emitter != null) {
                emitter.onAgentSchedulingStart();
                schedulingEmitted = true;
            }
            boolean forceComplete = round >= MAX_ORCHESTRATION_ROUNDS - 1;
            UniversalDispatchDecisionService.DispatchDecision decision;
            try {
                decision = dispatchDecisionService.decide(
                        candidates, routingQuery, trace, invokedAgentIds, forceComplete, turnKeys.turnId());
            } catch (TurnCancelledException ex) {
                return abortOnCancel(turnKeys.turnId(), config, false, delivered);
            }
            if (decision.isComplete()) {
                break;
            }
            String agentId = decision.agentId();
            if (StringUtils.isBlank(agentId) || invokedAgentIds.contains(agentId.trim())) {
                break;
            }
            String trimmedAgentId = agentId.trim();
            if (StringUtils.isBlank(routingQuery)) {
                break;
            }
            if (ChatTurnCancellationRegistry.isCancelled(turnKeys.turnId())) {
                return abortOnCancel(turnKeys.turnId(), config, false, delivered);
            }

            String callId = UUID.randomUUID().toString();
            String argumentsJson = "{\"agentId\":\"" + trimmedAgentId + "\",\"query\":"
                    + jsonString(routingQuery) + "}";
            long startedAt = System.currentTimeMillis();
            if (emitter != null) {
                emitter.onToolStart(callId, SubAgentCallNames.TOOL_NAME, argumentsJson);
            }
            String result;
            try {
                result = subAgentCallService.call(
                        trimmedAgentId,
                        routingQuery,
                        new UniversalSubAgentCallService.SubAgentCallRequest(
                                turnKeys.contextId(),
                                turnKeys.turnId(),
                                turnKeys.userId(),
                                turnKeys.parentConversationId(),
                                emitter,
                                turnAttachments));
            } catch (TurnCancelledException ex) {
                return abortOnCancel(turnKeys.turnId(), config, false, delivered);
            }
            if (emitter != null) {
                emitter.onToolSuccess(callId, SubAgentCallNames.TOOL_NAME, result,
                        System.currentTimeMillis() - startedAt);
            }

            trace.add(new OrchestrationTraceEntry(trimmedAgentId, routingQuery, result));
            invokedAgentIds.add(trimmedAgentId);
            delivered = true;

            routingQuery = intentQueryService.buildRoutingQuery(
                    chatMemory, turnKeys.parentConversationId(), messages, formatTrace(trace));
            try {
                candidates = intentQueryService.queryIntentAgents(
                        turnKeys.parentConversationId(), routingQuery, turnKeys.turnId());
            } catch (TurnCancelledException ex) {
                return abortOnCancel(turnKeys.turnId(), config, false, delivered);
            }
            round++;
        }

        boolean deliveredFinal = !invokedAgentIds.isEmpty();
        markDone(config, false, deliveredFinal);
        UniversalOrchestrationRunHolder.bind(
                turnKeys.turnId(),
                new UniversalOrchestrationRunHolder.Flags(false, deliveredFinal));
        return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public List<ModelInterceptor> getModelInterceptors() {
        return List.of(orchestrationModelInterceptor);
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    private static void markSkipped(RunnableConfig config, String turnId) {
        config.context().put(UniversalOrchestrationContextKeys.ORCHESTRATION_SKIPPED, Boolean.TRUE);
        markDone(config, true, false);
        UniversalOrchestrationRunHolder.bind(turnId, new UniversalOrchestrationRunHolder.Flags(true, false));
    }

    private static void markDone(RunnableConfig config, boolean skipped, boolean delivered) {
        config.context().put(UniversalOrchestrationContextKeys.ORCHESTRATION_DONE, Boolean.TRUE);
        if (skipped) {
            config.context().put(UniversalOrchestrationContextKeys.ORCHESTRATION_SKIPPED, Boolean.TRUE);
        }
        if (delivered) {
            config.context().put(UniversalOrchestrationContextKeys.ORCHESTRATION_DELIVERED, Boolean.TRUE);
        }
    }

    private static CompletableFuture<Map<String, Object>> abortOnCancel(
            String turnId, RunnableConfig config, boolean skipped, boolean delivered) {
        markDone(config, skipped, delivered);
        return CompletableFuture.failedFuture(new TurnCancelledException(turnId));
    }

    private static String formatTrace(List<OrchestrationTraceEntry> trace) {
        if (trace == null || trace.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【本回合已执行子智能体调用】\n");
        for (OrchestrationTraceEntry entry : trace) {
            sb.append(entry.toTraceBlock()).append('\n');
        }
        return sb.toString().trim();
    }

    private static String jsonString(String text) {
        if (text == null) {
            return "\"\"";
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static TurnKeys resolveTurnKeys(RunnableConfig config) {
        String turnId = stringContext(config, AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID);
        String contextId = stringContext(config, AgentRunnableContextKeys.CONTEXT_KEY_CONTEXT_ID);
        String userId = stringContext(config, AgentRunnableContextKeys.CONTEXT_KEY_USER_ID);
        String parentConversationId = stringContext(config, AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID);
        if (StringUtils.isAnyBlank(turnId, contextId, userId, parentConversationId)) {
            SubAgentStreamBridge.Target bridge = SubAgentStreamBridge.lookup(turnId);
            if (bridge != null
                    && StringUtils.isNoneBlank(bridge.contextId(), bridge.turnId(), bridge.userId(), bridge.parentConversationId())) {
                return new TurnKeys(
                        bridge.contextId(),
                        bridge.turnId(),
                        bridge.userId(),
                        bridge.parentConversationId(),
                        bridge.toolEventEmitter());
            }
            return null;
        }
        Object emitterRaw = config.context().get(AgentUiToolEventInterceptor.CONTEXT_KEY_TOOL_EVENT_EMITTER);
        ToolEventEmitter emitter = emitterRaw instanceof ToolEventEmitter toolEventEmitter ? toolEventEmitter : null;
        return new TurnKeys(contextId, turnId, userId, parentConversationId, emitter);
    }

    private static String stringContext(RunnableConfig config, String key) {
        Object raw = config.context().get(key);
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private record TurnKeys(
            String contextId,
            String turnId,
            String userId,
            String parentConversationId,
            ToolEventEmitter toolEventEmitter) {
    }
}
