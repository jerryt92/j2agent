package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 conversationId 保存单轮对话的深度思考运行时策略（跨 Reactor 线程可读）。
 */
public final class ThinkingOverrideRegistry {

    private static final ConcurrentHashMap<String, AgentThinkingOverride> OVERRIDES = new ConcurrentHashMap<>();

    private ThinkingOverrideRegistry() {
    }

    /**
     * 绑定本轮 conversationId 对应的深度思考策略。
     */
    public static void bind(String conversationId, AgentThinkingOverride override) {
        if (StringUtils.isBlank(conversationId) || override == null) {
            return;
        }
        OVERRIDES.put(conversationId, override);
    }

    /**
     * 读取已绑定的策略；未绑定时返回 null。
     */
    public static AgentThinkingOverride get(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return null;
        }
        return OVERRIDES.get(conversationId);
    }

    /**
     * 解除绑定，避免线程池复用导致泄漏。
     */
    public static void unbind(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return;
        }
        OVERRIDES.remove(conversationId);
    }
}
