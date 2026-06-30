package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 turnId 暂存编排结果，供 {@link OrchestrationModelInterceptor} 在模型调用前读取
 * （{@link com.alibaba.cloud.ai.graph.RunnableConfig#metadata()} 在运行期不可变）。
 */
public final class UniversalOrchestrationRunHolder {

    private static final ConcurrentHashMap<String, Flags> BY_TURN = new ConcurrentHashMap<>();

    private UniversalOrchestrationRunHolder() {
    }

    public record Flags(boolean skipped, boolean delivered) {
    }

    public static void bind(String turnId, Flags flags) {
        if (turnId != null && flags != null) {
            BY_TURN.put(turnId, flags);
        }
    }

    public static Flags lookup(String turnId) {
        return turnId == null ? null : BY_TURN.get(turnId);
    }

    public static void unbind(String turnId) {
        if (turnId != null) {
            BY_TURN.remove(turnId);
        }
    }
}
