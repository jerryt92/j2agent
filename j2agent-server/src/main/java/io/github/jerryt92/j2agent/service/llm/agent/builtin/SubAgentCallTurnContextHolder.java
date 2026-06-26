package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 工具调用期间从 {@link com.alibaba.cloud.ai.graph.RunnableConfig#context()} 传递回合键。
 * Spring AI {@link org.springframework.ai.chat.model.ToolContext} 未必包含这些键。
 */
public final class SubAgentCallTurnContextHolder {

    private static final ThreadLocal<Map<String, Object>> ACTIVE_CONTEXT = new ThreadLocal<>();

    private SubAgentCallTurnContextHolder() {
    }

    public static void bind(ToolCallRequest request) {
        if (request == null) {
            return;
        }
        request.getExecutionContext().ifPresent(ctx -> {
            Map<String, Object> source = ctx.config().context();
            if (source == null || source.isEmpty()) {
                ACTIVE_CONTEXT.remove();
                return;
            }
            ACTIVE_CONTEXT.set(Collections.unmodifiableMap(new HashMap<>(source)));
        });
    }

    public static Map<String, Object> context() {
        Map<String, Object> map = ACTIVE_CONTEXT.get();
        return map == null ? Map.of() : map;
    }

    public static void clear() {
        ACTIVE_CONTEXT.remove();
    }
}
