package io.github.jerryt92.j2agent.service.llm.tool;

import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogContext;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogSnapshot;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.service.llm.agent.builtin.SubAgentCallTurnContextHolder;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 在 ReactAgent 工具链最内层包装真实工具执行：向 Agent-UI 发射 TOOL 生命周期事件。
 * <p>
 * {@link ToolEventEmitter} 由调用方在 {@link com.alibaba.cloud.ai.graph.RunnableConfig#context()} 中按 key 注入（单轮一次）。
 */
@Component
public class AgentUiToolEventInterceptor extends ToolInterceptor {

    /**
     * 放入 {@link com.alibaba.cloud.ai.graph.RunnableConfig#context()} 的键，值为当前轮的 {@link ToolEventEmitter}。
     */
    public static final String CONTEXT_KEY_TOOL_EVENT_EMITTER =
            AgentUiToolEventInterceptor.class.getName() + ".toolEventEmitter";

    @Override
    public String getName() {
        return "AgentUiToolEvent";
    }

    /**
     * 在工具调用前后对接 {@link ToolEventEmitter}；异常在记录 FAILED 后原样抛出，供外层 {@code ToolErrorInterceptor} 合成回注结果。
     * {@link ReadSkillTool#READ_SKILL} 由 {@link io.github.jerryt92.j2agent.service.llm.skill.AgentUiSkillLoadToolInterceptor} 负责 {@link AgentState#LOAD_SKILL} 事件，此处不重复驱动 {@link AgentState#CALLING_TOOL}。
     */
    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        Optional<ToolEventEmitter> emitterOpt = resolveEmitter(request);
        String callId = request.getToolCallId();
        String toolName = request.getToolName();
        String args = request.getArguments() == null ? "" : request.getArguments();

        if (ReadSkillTool.READ_SKILL.equals(toolName)) {
            return handler.call(request);
        }

        String conversationId = resolveConversationId(request);
        SubAgentCallTurnContextHolder.bind(request);
        try {
            if (emitterOpt.isEmpty()) {
                logWarn(request, conversationId, AgentRunEventType.ERROR,
                        AgentRunLogger.kv("errorCode", "toolEventEmitterMissing", "tool", toolName),
                        "ToolEventEmitter missing in RunnableConfig.context, skip Agent-UI tool events");
            }
            logInfo(request, conversationId, AgentRunEventType.TOOL_START,
                    AgentRunLogger.kv("tool", toolName, "callId", callId, "argsPreview", AgentRunLogger.preview(args)),
                    "tool invoked");
            emitterOpt.ifPresent(e -> e.onToolStart(callId, toolName, args));
            long startNanos = System.nanoTime();
            try {
                ToolCallResponse response = handler.call(request);
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                if (response.isError()) {
                    Throwable err = toFailureThrowable(response);
                    logWarn(request, conversationId, AgentRunEventType.TOOL_FAILURE,
                            AgentRunLogger.kv(
                                    "tool", toolName,
                                    "callId", callId,
                                    "durationMs", durationMs,
                                    "errorType", err.getClass().getSimpleName()),
                            err.getMessage());
                    emitterOpt.ifPresent(e -> e.onToolFailure(callId, toolName, err, durationMs));
                } else {
                    logInfo(request, conversationId, AgentRunEventType.TOOL_SUCCESS,
                            AgentRunLogger.kv(
                                    "tool", toolName,
                                    "callId", callId,
                                    "durationMs", durationMs,
                                    "resultPreview", AgentRunLogger.preview(response.getResult())),
                            "tool completed");
                    emitterOpt.ifPresent(e -> e.onToolSuccess(callId, toolName, response.getResult(), durationMs));
                }
                return response;
            } catch (Throwable t) {
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                logError(request, conversationId, AgentRunEventType.TOOL_FAILURE,
                        AgentRunLogger.kv(
                                "tool", toolName,
                                "callId", callId,
                                "durationMs", durationMs,
                                "errorType", t.getClass().getSimpleName()),
                        t.getMessage(),
                        t);
                emitterOpt.ifPresent(e -> e.onToolFailure(callId, toolName, t, durationMs));
                throw t;
            }
        } finally {
            SubAgentCallTurnContextHolder.clear();
        }
    }

    /**
     * 从 {@link ToolCallRequest} 携带的执行上下文中解析本次 run 的 {@link ToolEventEmitter}。
     */
    private Optional<ToolEventEmitter> resolveEmitter(ToolCallRequest request) {
        return request.getExecutionContext()
                .map(ctx -> ctx.config().context().get(CONTEXT_KEY_TOOL_EVENT_EMITTER))
                .filter(ToolEventEmitter.class::isInstance)
                .map(ToolEventEmitter.class::cast);
    }

    private static String resolveConversationId(ToolCallRequest request) {
        return request.getExecutionContext()
                .map(ctx -> ctx.config().context().get(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .orElse(null);
    }

    private static void logInfo(ToolCallRequest request,
                                String conversationId,
                                AgentRunEventType event,
                                Map<String, ?> extra,
                                String message) {
        AgentRunLogSnapshot snapshot = resolveSnapshot(request, conversationId);
        if (snapshot != null) {
            AgentRunLogger.info(snapshot, event, extra, message);
        }
    }

    private static void logWarn(ToolCallRequest request,
                                String conversationId,
                                AgentRunEventType event,
                                Map<String, ?> extra,
                                String message) {
        AgentRunLogSnapshot snapshot = resolveSnapshot(request, conversationId);
        if (snapshot != null) {
            AgentRunLogger.warn(snapshot, event, extra, message);
        }
    }

    private static void logError(ToolCallRequest request,
                                 String conversationId,
                                 AgentRunEventType event,
                                 Map<String, ?> extra,
                                 String message,
                                 Throwable throwable) {
        AgentRunLogSnapshot snapshot = resolveSnapshot(request, conversationId);
        if (snapshot != null) {
            AgentRunLogger.error(snapshot, event, extra, message, throwable);
        }
    }

    private static AgentRunLogSnapshot resolveSnapshot(ToolCallRequest request, String conversationId) {
        AgentRunLogSnapshot snapshot = AgentRunLogContext.lookup(conversationId);
        if (snapshot != null) {
            return snapshot;
        }
        return request.getExecutionContext()
                .map(ctx -> ctx.config().context())
                .map(context -> new AgentRunLogSnapshot(
                        stringValue(context.get(AgentRunnableContextKeys.CONTEXT_KEY_CONTEXT_ID)),
                        stringValue(context.get(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID)),
                        stringValue(context.get(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID)),
                        stringValue(context.get(AgentRunnableContextKeys.CONTEXT_KEY_USER_ID)),
                        stringValue(context.get(AgentRunnableContextKeys.CONTEXT_KEY_AGENT_ID))))
                .filter(s -> s.conversationId() != null && !s.conversationId().isBlank())
                .orElse(null);
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 将已标记为 error 的 {@link ToolCallResponse} 转为可供 {@link ToolEventEmitter#onToolFailure} 使用的异常。
     */
    private static Throwable toFailureThrowable(ToolCallResponse response) {
        Object msg = response.getMetadata().get("errorMessage");
        if (msg != null) {
            return new RuntimeException(String.valueOf(msg));
        }
        String result = response.getResult();
        return new RuntimeException(result != null ? result : "tool error");
    }
}
