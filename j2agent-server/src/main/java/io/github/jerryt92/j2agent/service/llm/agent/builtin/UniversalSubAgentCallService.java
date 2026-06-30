package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.ThinkingOverrideRegistry;
import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamOptions;
import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamSession;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunContext;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import io.github.jerryt92.j2agent.service.llm.rag.TurnRagSourceRegistry;
import io.github.jerryt92.j2agent.service.llm.tool.ToolEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用助手子智能体调用（无 {@code @Tool}），由编排 Hook 直接调用。
 * 委派调用使用 {@code subAgentCallRun=true}，不读写子智能体会话记忆，避免聊天记录落入专业 Agent 键。
 */
@Slf4j
@Service
public class UniversalSubAgentCallService {

    private static final int MAX_SUB_AGENT_CALL_RESULT_LENGTH = ToolEventEmitter.MAX_TOOL_RESULT_LENGTH;

    private final AgentRouter agentRouter;
    private final AgentStreamSession agentStreamSession;

    public UniversalSubAgentCallService(AgentRouter agentRouter, AgentStreamSession agentStreamSession) {
        this.agentRouter = agentRouter;
        this.agentStreamSession = agentStreamSession;
    }

    public String call(String agentId, String query, SubAgentCallRequest request) {
        if (StringUtils.isBlank(agentId)) {
            return "Error: agentId is required";
        }
        if (StringUtils.isBlank(query)) {
            return "Error: query is required";
        }
        if (request == null) {
            return "Error: missing turn context for sub-agent call";
        }
        String trimmedAgentId = agentId.trim();
        String trimmedQuery = query.trim();
        boolean callable = agentRouter.listCallableSubAgents().stream()
                .anyMatch(a -> trimmedAgentId.equals(a.getAgentId()));
        if (!callable) {
            return "Error: unknown or non-callable agentId: " + trimmedAgentId;
        }

        AiAgent targetAgent;
        try {
            targetAgent = agentRouter.route(trimmedAgentId);
        } catch (Exception ex) {
            return "Error: failed to route agent: " + ex.getMessage();
        }

        String specialistConversationId = ConversationIdCodec.format(
                request.userId(), request.contextId(), trimmedAgentId);
        AgentThinkingOverride parentThinking = ThinkingOverrideRegistry.get(request.parentConversationId());
        AgentThinkingOverride runtimeThinking = parentThinking != null
                ? parentThinking
                : targetAgent.getThinkingOverride();
        ThinkingOverrideRegistry.bind(specialistConversationId, runtimeThinking);
        TurnRagSourceRegistry.shareHolder(specialistConversationId, request.parentConversationId());

        SubAgentStreamBridge.Target bridge = SubAgentStreamBridge.lookup(request.turnId());
        StringBuilder content = bridge != null ? bridge.streamedContent() : new StringBuilder();
        StringBuilder reasoning = bridge != null ? bridge.streamedReasoning() : new StringBuilder();
        Object textLock = bridge != null ? bridge.streamedTextLock() : new Object();
        Object subAgentTurnLock = new Object();
        AgentTurnStateMachine subAgentStateMachine = new AgentTurnStateMachine();

        List<ChatAttachmentDto> attachments = request.attachments() == null ? List.of() : request.attachments();

        AgentRunContext subContext = new AgentRunContext(
                trimmedQuery,
                request.contextId(),
                request.userId(),
                request.turnId(),
                specialistConversationId,
                trimmedAgentId,
                attachments,
                request.toolEventEmitter(),
                true,
                false);

        try {
            Mono.fromCallable(() -> {
                        agentStreamSession.stream(new AgentStreamOptions(
                                        targetAgent,
                                        subContext,
                                        null,
                                        subAgentStateMachine,
                                        content,
                                        reasoning,
                                        textLock,
                                        subAgentTurnLock,
                                        new AtomicLong(0),
                                        new AtomicInteger(0),
                                        null))
                                .doOnNext(parts -> {
                                    if (StringUtils.isNotBlank(parts.answerDelta())) {
                                        synchronized (textLock) {
                                            content.append(parts.answerDelta());
                                        }
                                    }
                                    if (StringUtils.isNotBlank(parts.reasoningDelta())) {
                                        synchronized (textLock) {
                                            reasoning.append(parts.reasoningDelta());
                                        }
                                    }
                                    if (bridge != null) {
                                        bridge.emitDelta(parts.answerDelta(), parts.reasoningDelta());
                                    }
                                })
                                .blockLast();
                        return content.toString().trim();
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();

            String text = content.toString().trim();
            if (StringUtils.isBlank(text)) {
                log.warn("sub-agent call empty result: agentId={}", trimmedAgentId);
                return "子智能体未返回有效内容，请稍后重试。";
            }
            if (MAX_SUB_AGENT_CALL_RESULT_LENGTH > 0 && text.length() > MAX_SUB_AGENT_CALL_RESULT_LENGTH) {
                return text.substring(0, MAX_SUB_AGENT_CALL_RESULT_LENGTH);
            }
            return text;
        } catch (Exception ex) {
            log.warn("sub-agent call 失败: agentId={}", trimmedAgentId, ex);
            Throwable cause = ex instanceof RuntimeException runtime && runtime.getCause() != null
                    ? runtime.getCause()
                    : ex;
            if (cause instanceof GraphRunnerException) {
                return "调用子智能体时出现问题，请稍后重试或换一种方式描述您的问题。";
            }
            return "调用子智能体时出现问题: " + ex.getMessage();
        } finally {
            ThinkingOverrideRegistry.unbind(specialistConversationId);
            TurnRagSourceRegistry.unshareHolder(specialistConversationId);
        }
    }

    public record SubAgentCallRequest(
            String contextId,
            String turnId,
            String userId,
            String parentConversationId,
            ToolEventEmitter toolEventEmitter,
            List<ChatAttachmentDto> attachments) {

        public SubAgentCallRequest(
                String contextId,
                String turnId,
                String userId,
                String parentConversationId,
                ToolEventEmitter toolEventEmitter) {
            this(contextId, turnId, userId, parentConversationId, toolEventEmitter, List.of());
        }
    }
}
