package io.github.jerryt92.j2agent.service.llm.tool;

import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import io.github.jerryt92.j2agent.model.AgentState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import org.springframework.stereotype.Component;

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
     * {@link ReadSkillTool#READ_SKILL} 由 {@link AgentUiSkillLoadToolInterceptor} 负责 {@link AgentState#LOAD_SKILL} 事件，此处不重复驱动 {@link AgentState#CALLING_TOOL}。
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
        if (emitterOpt.isEmpty()) {
            AgentRunLogger.warnByConversationId(conversationId, AgentRunEventType.ERROR,
                    AgentRunLogger.kv("errorCode", "toolEventEmitterMissing", "tool", toolName),
                    "ToolEventEmitter missing in RunnableConfig.context, skip Agent-UI tool events");
            return handler.call(request);
        }
        ToolEventEmitter emitter = emitterOpt.get();
        AgentRunLogger.infoByConversationId(conversationId, AgentRunEventType.TOOL_START,
                AgentRunLogger.kv("tool", toolName, "callId", callId, "argsPreview", AgentRunLogger.preview(args)),
                "tool invoked");
        emitter.onToolStart(callId, toolName, args);
        long startNanos = System.nanoTime();
        try {
            ToolCallResponse response = handler.call(request);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            if (response.isError()) {
                Throwable err = toFailureThrowable(response);
                AgentRunLogger.warnByConversationId(conversationId, AgentRunEventType.TOOL_FAILURE,
                        AgentRunLogger.kv(
                                "tool", toolName,
                                "callId", callId,
                                "durationMs", durationMs,
                                "errorType", err.getClass().getSimpleName()),
                        err.getMessage());
                emitter.onToolFailure(callId, toolName, err, durationMs);
            } else {
                AgentRunLogger.infoByConversationId(conversationId, AgentRunEventType.TOOL_SUCCESS,
                        AgentRunLogger.kv(
                                "tool", toolName,
                                "callId", callId,
                                "durationMs", durationMs,
                                "resultPreview", AgentRunLogger.preview(response.getResult())),
                        "tool completed");
                emitter.onToolSuccess(callId, toolName, response.getResult(), durationMs);
            }
            return response;
        } catch (Throwable t) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            AgentRunLogger.errorByConversationId(conversationId, AgentRunEventType.TOOL_FAILURE,
                    AgentRunLogger.kv(
                            "tool", toolName,
                            "callId", callId,
                            "durationMs", durationMs,
                            "errorType", t.getClass().getSimpleName()),
                    t.getMessage(),
                    t);
            emitter.onToolFailure(callId, toolName, t, durationMs);
            throw t;
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
