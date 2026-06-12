package io.github.jerryt92.j2agent.logging.llm;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 以 {@code conversationId} 为键绑定单轮日志上下文，供 {@code publishOn} 后工作线程关联。
 */
public final class AgentRunLogContext {

    private static final ConcurrentHashMap<String, AgentRunLogSnapshot> BY_CONVERSATION = new ConcurrentHashMap<>();

    private AgentRunLogContext() {
    }

    public static void bind(String conversationId, AgentRunLogSnapshot snapshot) {
        if (conversationId == null || conversationId.isBlank() || snapshot == null) {
            return;
        }
        BY_CONVERSATION.put(conversationId, snapshot);
    }

    public static AgentRunLogSnapshot lookup(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return BY_CONVERSATION.get(conversationId);
    }

    public static void clear(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            BY_CONVERSATION.remove(conversationId);
        }
    }
}
