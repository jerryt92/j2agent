package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;
import io.github.jerryt92.j2agent.service.llm.chat.TurnCancelledException;
import io.github.jerryt92.j2agent.service.llm.tool.ToolEventEmitter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 通用助手编排：需要调度时切子智能体上下文并流式输出；否则由调用方继续走通用助手 ReAct。
 */
@Service
public class UniversalAssistantOrchestratorService {

    private static final int MAX_ORCHESTRATION_ROUNDS = 3;

    private final UniversalIntentQueryService intentQueryService;
    private final UniversalDispatchDecisionService dispatchDecisionService;
    private final UniversalSubAgentCallService subAgentCallService;
    private final AgentRouter agentRouter;
    private final ChatMemory chatMemory;

    public UniversalAssistantOrchestratorService(
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

    /**
     * 编排结果：{@link #CONTINUE} 由通用助手继续；{@link #DISPATCHED} 子智能体已交付，勿再启动主 ReAct。
     */
    public enum OrchestrationOutcome {
        CONTINUE,
        DISPATCHED
    }

    public record OrchestrationRequest(
            String contextId,
            String turnId,
            String userId,
            String parentConversationId,
            ToolEventEmitter toolEventEmitter,
            List<ChatAttachmentDto> attachments,
            String userMessage) {
    }

    /**
     * 执行开放召回、决策与子智能体调用；无委派时返回 {@link OrchestrationOutcome#CONTINUE}。
     */
    public OrchestrationOutcome orchestrate(OrchestrationRequest request) {
        if (request == null) {
            return OrchestrationOutcome.CONTINUE;
        }
        if (agentRouter.listCallableSubAgents().isEmpty()) {
            return OrchestrationOutcome.CONTINUE;
        }
        if (StringUtils.isAnyBlank(
                request.turnId(), request.contextId(), request.userId(), request.parentConversationId())) {
            return OrchestrationOutcome.CONTINUE;
        }
        if (ChatTurnCancellationRegistry.isCancelled(request.turnId())) {
            throw new TurnCancelledException(request.turnId());
        }

        List<Message> messages = buildTurnMessages(request);
        List<OrchestrationTraceEntry> trace = new ArrayList<>();
        Set<String> invokedAgentIds = new LinkedHashSet<>();

        List<ChatAttachmentDto> turnAttachments = UniversalIntentQueryService.resolveLatestAttachments(
                messages, chatMemory, request.parentConversationId());

        String routingQuery = intentQueryService.buildRoutingQuery(
                chatMemory, request.parentConversationId(), messages, formatTrace(trace));
        String candidates = intentQueryService.queryIntentAgents(
                request.parentConversationId(), routingQuery, request.turnId());

        if (UniversalIntentQueryService.isCandidatesEmpty(candidates)) {
            return OrchestrationOutcome.CONTINUE;
        }

        ToolEventEmitter emitter = request.toolEventEmitter();
        boolean dispatchingEmitted = false;
        int round = 0;
        while (round < MAX_ORCHESTRATION_ROUNDS) {
            if (ChatTurnCancellationRegistry.isCancelled(request.turnId())) {
                throw new TurnCancelledException(request.turnId());
            }
            if (!dispatchingEmitted && emitter != null) {
                emitter.onAgentDispatchingStart();
                dispatchingEmitted = true;
            }
            boolean forceComplete = round >= MAX_ORCHESTRATION_ROUNDS - 1;
            UniversalDispatchDecisionService.DispatchDecision decision = dispatchDecisionService.decide(
                    candidates, routingQuery, trace, invokedAgentIds, forceComplete, request.turnId());
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
            if (ChatTurnCancellationRegistry.isCancelled(request.turnId())) {
                throw new TurnCancelledException(request.turnId());
            }

            String callId = UUID.randomUUID().toString();
            String argumentsJson = "{\"agentId\":\"" + trimmedAgentId + "\",\"query\":"
                    + jsonString(routingQuery) + "}";
            long startedAt = System.currentTimeMillis();
            if (emitter != null) {
                emitter.onToolStart(callId, SubAgentCallNames.TOOL_NAME, argumentsJson);
            }
            String result = subAgentCallService.call(
                    trimmedAgentId,
                    routingQuery,
                    new UniversalSubAgentCallService.SubAgentCallRequest(
                            request.contextId(),
                            request.turnId(),
                            request.userId(),
                            request.parentConversationId(),
                            emitter,
                            turnAttachments));
            if (emitter != null) {
                emitter.onToolSuccess(callId, SubAgentCallNames.TOOL_NAME, result,
                        System.currentTimeMillis() - startedAt);
            }

            trace.add(new OrchestrationTraceEntry(trimmedAgentId, routingQuery, result));
            invokedAgentIds.add(trimmedAgentId);

            routingQuery = intentQueryService.buildRoutingQuery(
                    chatMemory, request.parentConversationId(), messages, formatTrace(trace));
            candidates = intentQueryService.queryIntentAgents(
                    request.parentConversationId(), routingQuery, request.turnId());
            round++;
        }

        return invokedAgentIds.isEmpty()
                ? OrchestrationOutcome.CONTINUE
                : OrchestrationOutcome.DISPATCHED;
    }

    private static List<Message> buildTurnMessages(OrchestrationRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        List<ChatAttachmentDto> attachments = request.attachments() == null ? List.of() : request.attachments();
        if (!attachments.isEmpty()) {
            metadata.put("attachments", attachments);
        }
        UserMessage.Builder builder = UserMessage.builder()
                .text(request.userMessage() == null ? "" : request.userMessage());
        if (!metadata.isEmpty()) {
            builder.metadata(metadata);
        }
        return List.of(builder.build());
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
}
