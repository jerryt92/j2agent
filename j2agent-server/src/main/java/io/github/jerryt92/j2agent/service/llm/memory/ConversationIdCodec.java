package io.github.jerryt92.j2agent.service.llm.memory;

import org.springframework.util.StringUtils;

/**
 * Spring AI {@code ChatMemory} 使用的会话键编解码：{@code userId:contextId:agentId}，
 * 与库表 {@code (context_id, agent_id)} 及 Redisson 缓存 key 一一对应。
 */
public final class ConversationIdCodec {

    private static final String ANONYMOUS_USER = "anonymous";

    private ConversationIdCodec() {
    }

    /**
     * 组装会话键；{@code userId} 为空时用 anonymous；{@code contextId}、{@code agentId} 均不可为空。
     */
    public static String format(String userId, String contextId, String agentId) {
        if (!StringUtils.hasText(contextId)) {
            throw new IllegalArgumentException("contextId must not be blank.");
        }
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("agentId must not be blank.");
        }
        String uid = StringUtils.hasText(userId) ? userId.trim() : ANONYMOUS_USER;
        return uid + ":" + contextId.trim() + ":" + agentId.trim();
    }

    /**
     * 解析会话键，必须为三段 {@code userId:contextId:agentId}，且各段非空。
     */
    public static Parts parse(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new IllegalArgumentException("conversationId must be 'userId:contextId:agentId'.");
        }
        String[] parts = conversationId.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("conversationId must be 'userId:contextId:agentId'.");
        }
        String userId = parts[0];
        String contextId = parts[1];
        String agentId = parts[2];
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(contextId) || !StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("userId, contextId and agentId must not be blank.");
        }
        return new Parts(userId, contextId, agentId);
    }

    /**
     * 会话键拆分的三段数据。
     */
    public record Parts(String userId, String contextId, String agentId) {
    }
}
