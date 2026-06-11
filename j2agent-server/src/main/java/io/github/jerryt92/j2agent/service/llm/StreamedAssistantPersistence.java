package io.github.jerryt92.j2agent.service.llm;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 流式对话回合内，由 {@link ChatService} 以 chunked 拆分的
 * {@code streamedContent}/{@code streamedReasoning} 落库，避免 Advisor 聚合后的整段 {@code getText()} 覆盖拆分结果。
 */
public final class StreamedAssistantPersistence {

    private static final Set<String> ENABLED_CONVERSATIONS = ConcurrentHashMap.newKeySet();

    private StreamedAssistantPersistence() {
    }

    public static void enable(String conversationId) {
        if (conversationId != null) {
            ENABLED_CONVERSATIONS.add(conversationId);
        }
    }

    public static void disable(String conversationId) {
        if (conversationId != null) {
            ENABLED_CONVERSATIONS.remove(conversationId);
        }
    }

    /**
     * Advisor {@code after()} 是否应跳过「无 tool_calls 的纯文本 assistant」落库。
     */
    public static boolean shouldSkipAdvisorTextAssistant(String conversationId) {
        return conversationId != null && ENABLED_CONVERSATIONS.contains(conversationId);
    }
}
