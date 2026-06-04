package io.github.jerryt92.j2agent.service.llm.memory;

import org.springframework.util.StringUtils;

/**
 * Spring AI {@code ChatMemory} 使用的会话键编解码：{@code userId:contextId:agentId}，
 * 与库表 {@code (context_id, agent_id)} 及 Redisson 缓存 key 一一对应。
 */
public final class ConversationIdCodec {

    /**
     * 历史两段子键（{@code userId:contextId}）解析时使用的默认 agent，与库迁移默认空串一致。
     */
    public static final String LEGACY_AGENT_ID = "";

    private static final String ANONYMOUS_USER = "anonymous";

    private ConversationIdCodec() {
    }

    /**
     * 组装会话键；{@code userId} 为空时用 anonymous；{@code agentId} 为空时用 {@link #LEGACY_AGENT_ID}。
     */
    public static String format(String userId, String contextId, String agentId) {
        if (!StringUtils.hasText(contextId)) {
            throw new IllegalArgumentException("contextId must not be blank.");
        }
        String uid = StringUtils.hasText(userId) ? userId.trim() : ANONYMOUS_USER;
        String aid = agentId == null ? LEGACY_AGENT_ID : agentId;
        return uid + ":" + contextId.trim() + ":" + aid;
    }

    /**
     * 解析会话键；仅两段时第三段视为 {@link #LEGACY_AGENT_ID}。
     */
    public static Parts parse(String conversationId) {
        if (!StringUtils.hasText(conversationId) || !conversationId.contains(":")) {
            throw new IllegalArgumentException("conversationId must be 'userId:contextId' or 'userId:contextId:agentId'.");
        }
        String[] parts = conversationId.split(":", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException("conversationId must be 'userId:contextId' or 'userId:contextId:agentId'.");
        }
        String userId = parts[0];
        String contextId = parts[1];
        String agentId = parts.length >= 3 ? parts[2] : LEGACY_AGENT_ID;
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(contextId)) {
            throw new IllegalArgumentException("userId and contextId must not be blank.");
        }
        if (agentId == null) {
            agentId = LEGACY_AGENT_ID;
        }
        return new Parts(userId, contextId, agentId);
    }

    /**
     * 会话键拆分的三段数据。
     */
    public record Parts(String userId, String contextId, String agentId) {
    }
}
