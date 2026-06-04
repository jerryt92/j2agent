package io.github.jerryt92.j2agent.service.llm.tool;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * 工具链最外层兜底：将漏出的工具异常转换为 ToolResponse 回注给 LLM，避免打断整轮 Agent 流程。
 */
@Slf4j
@Component
public class AgentToolErrorReturnInterceptor extends ToolInterceptor {

    @Override
    public String getName() {
        return "AgentToolErrorReturn";
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        ToolCallResponse malformed = rejectMalformedToolCall(request);
        if (malformed != null) {
            return malformed;
        }
        try {
            return handler.call(request);
        } catch (Throwable t) {
            if (t instanceof Error error) {
                throw error;
            }
            if (t instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            String message = buildToolErrorMessage(request, t);
            log.warn("Tool {} execution failed and will be returned to LLM: {}",
                    safeToolName(request), message);
            log.debug("Tool execution failure detail", t);
            return ToolCallResponse.error(request.getToolCallId(), safeToolName(request), message);
        }
    }

    /**
     * 拦截 vLLM 流式 tool_call 分片（空 name / 空 arguments），避免 MethodToolCallback 抛 IllegalArgumentException。
     */
    private static ToolCallResponse rejectMalformedToolCall(ToolCallRequest request) {
        if (request.getToolName() == null || request.getToolName().isBlank()) {
            String message = "Malformed streaming tool-call fragment ignored: missing tool name. Continue without retrying this tool fragment.";
            log.warn("Ignore malformed tool call fragment, callId={}, arguments={}",
                    request.getToolCallId(), request.getArguments());
            return ToolCallResponse.error(request.getToolCallId(), safeToolName(request), message);
        }
        if (StringUtils.isBlank(request.getArguments())) {
            String message = "Malformed streaming tool-call fragment ignored: empty tool arguments. Continue without retrying this tool fragment.";
            log.warn("Ignore malformed tool call fragment, tool={}, callId={}",
                    request.getToolName(), request.getToolCallId());
            return ToolCallResponse.error(request.getToolCallId(), request.getToolName(), message);
        }
        return null;
    }

    private static String buildToolErrorMessage(ToolCallRequest request, Throwable t) {
        String toolName = safeToolName(request);
        String errorType = t == null ? "UnknownException" : t.getClass().getSimpleName();
        String errorMessage = t == null || t.getMessage() == null || t.getMessage().isBlank()
                ? errorType
                : t.getMessage();
        return "Tool " + toolName + " failed with " + errorType + ": " + errorMessage
                + ". You may adjust the tool arguments, retry the tool call, or continue answering based on available context.";
    }

    private static String safeToolName(ToolCallRequest request) {
        return request.getToolName() == null || request.getToolName().isBlank()
                ? "unknown_tool"
                : request.getToolName();
    }
}
