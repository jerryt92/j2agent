package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.ThinkingOverrideRegistry;
import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamOptions;
import io.github.jerryt92.j2agent.service.llm.agent.AgentStreamSession;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunContext;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import io.github.jerryt92.j2agent.service.llm.rag.TurnRagSourceRegistry;
import io.github.jerryt92.j2agent.service.llm.tool.ToolEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 通用助手工具：调用子智能体，运行时切入其完整会话记忆并流式回传结果。
 */
@Slf4j
@Component
public class UniversalSubAgentCallTool {

    public static final String TOOL_NAME = "call_sub_agent";

    /** 历史轨迹兼容 */
    public static final String LEGACY_TOOL_NAME_DELEGATE = "delegate_to_agent";

    public static final String LEGACY_TOOL_NAME_CALL_AGENT = "call_agent";

    private static final int MAX_SUB_AGENT_CALL_RESULT_LENGTH = ToolEventEmitter.MAX_TOOL_RESULT_LENGTH;

    private final AgentRouter agentRouter;
    private final AgentStreamSession agentStreamSession;

    public UniversalSubAgentCallTool(AgentRouter agentRouter, AgentStreamSession agentStreamSession) {
        this.agentRouter = agentRouter;
        this.agentStreamSession = agentStreamSession;
    }

    public static boolean isSubAgentCallToolName(String toolName) {
        if (StringUtils.isBlank(toolName)) {
            return false;
        }
        String trimmed = toolName.trim();
        return TOOL_NAME.equals(trimmed)
                || LEGACY_TOOL_NAME_DELEGATE.equals(trimmed)
                || LEGACY_TOOL_NAME_CALL_AGENT.equals(trimmed);
    }

    @Tool(name = TOOL_NAME, description = "将用户问题交给指定子智能体处理；调用期间使用该子智能体的完整对话记忆。")
    public String callSubAgent(
            @ToolParam(description = "目标子智能体的 agentId") String agentId,
            @ToolParam(description = "传给子智能体的提炼问题") String query,
            ToolContext toolContext) {
        if (StringUtils.isBlank(agentId)) {
            return "Error: agentId is required";
        }
        if (StringUtils.isBlank(query)) {
            return "Error: query is required";
        }
        String trimmedAgentId = agentId.trim();
        String trimmedQuery = query.trim();
        boolean callable = agentRouter.listCallableSubAgents().stream()
                .anyMatch(a -> trimmedAgentId.equals(a.getAgentId()));
        if (!callable) {
            return "Error: unknown or non-callable agentId: " + trimmedAgentId;
        }

        TurnContext turnContext = resolveTurnContext(toolContext);
        if (turnContext == null) {
            log.warn("call_sub_agent missing turn context");
            return "Error: missing turn context for sub-agent call";
        }

        AiAgent targetAgent;
        try {
            targetAgent = agentRouter.route(trimmedAgentId);
        } catch (Exception ex) {
            return "Error: failed to route agent: " + ex.getMessage();
        }

        String specialistConversationId = ConversationIdCodec.format(
                turnContext.userId(), turnContext.contextId(), trimmedAgentId);
        AgentThinkingOverride parentThinking = ThinkingOverrideRegistry.get(turnContext.parentConversationId());
        AgentThinkingOverride runtimeThinking = parentThinking != null
                ? parentThinking
                : targetAgent.getThinkingOverride();
        ThinkingOverrideRegistry.bind(specialistConversationId, runtimeThinking);
        TurnRagSourceRegistry.shareHolder(specialistConversationId, turnContext.parentConversationId());

        SubAgentStreamBridge.Target bridge = SubAgentStreamBridge.lookup(turnContext.turnId());
        StringBuilder content = bridge != null ? bridge.streamedContent() : new StringBuilder();
        StringBuilder reasoning = bridge != null ? bridge.streamedReasoning() : new StringBuilder();
        Object textLock = bridge != null ? bridge.streamedTextLock() : new Object();
        Object subAgentTurnLock = new Object();
        AgentTurnStateMachine subAgentStateMachine = new AgentTurnStateMachine();

        AgentRunContext subContext = new AgentRunContext(
                trimmedQuery,
                turnContext.contextId(),
                turnContext.userId(),
                turnContext.turnId(),
                specialistConversationId,
                trimmedAgentId,
                List.of(),
                turnContext.toolEventEmitter(),
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
                log.warn("call_sub_agent empty result: agentId={}", trimmedAgentId);
                return "子智能体未返回有效内容，请稍后重试。";
            }
            if (MAX_SUB_AGENT_CALL_RESULT_LENGTH > 0 && text.length() > MAX_SUB_AGENT_CALL_RESULT_LENGTH) {
                return text.substring(0, MAX_SUB_AGENT_CALL_RESULT_LENGTH);
            }
            return text;
        } catch (Exception ex) {
            log.warn("call_sub_agent 失败: agentId={}", trimmedAgentId, ex);
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

    private static TurnContext resolveTurnContext(ToolContext toolContext) {
        String turnId = AgentToolContextSupport.turnId(toolContext);
        SubAgentStreamBridge.Target bridge = SubAgentStreamBridge.lookup(turnId);
        if (bridge != null
                && StringUtils.isNoneBlank(bridge.contextId(), bridge.turnId(), bridge.userId(), bridge.parentConversationId())) {
            return new TurnContext(
                    bridge.contextId(),
                    bridge.turnId(),
                    bridge.userId(),
                    bridge.parentConversationId(),
                    bridge.toolEventEmitter());
        }
        String contextId = AgentToolContextSupport.contextId(toolContext);
        String userId = AgentToolContextSupport.userId(toolContext);
        String parentConversationId = AgentToolContextSupport.parentConversationId(toolContext);
        if (StringUtils.isAnyBlank(contextId, turnId, userId, parentConversationId)) {
            return null;
        }
        return new TurnContext(
                contextId,
                turnId,
                userId,
                parentConversationId,
                AgentToolContextSupport.toolEventEmitter(toolContext));
    }

    private record TurnContext(
            String contextId,
            String turnId,
            String userId,
            String parentConversationId,
            ToolEventEmitter toolEventEmitter) {
    }
}
