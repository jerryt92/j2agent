package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.config.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 基于 Redis 登记正在进行 WebSocket 流式对话的 contextId + agentId，供删除历史等接口拒绝误删。
 */
@Slf4j
@Component
public class ActiveChatTurnRegistry {

    /** 兜底 TTL，防止 unregister 遗漏导致 key 永驻 */
    private static final Duration KEY_TTL = Duration.ofHours(24);

    private final RedissonClient redissonClient;
    private final String turnCounterPrefix;
    private final String contextActiveAgentsPrefix;

    public ActiveChatTurnRegistry(RedissonClient redissonClient, RedisKeyNamespaces redisKeyNamespaces) {
        this.redissonClient = redissonClient;
        this.turnCounterPrefix = redisKeyNamespaces.key("active-chat-turn:");
        this.contextActiveAgentsPrefix = redisKeyNamespaces.key("active-chat-turn-ctx:");
    }

    public void register(String contextId, String agentId) {
        if (!StringUtils.hasText(contextId)) {
            return;
        }
        String aid = normalizeAgentId(agentId);
        try {
            RAtomicLong counter = turnCounter(contextId, aid);
            long count = counter.incrementAndGet();
            counter.expire(KEY_TTL);
            if (count == 1) {
                RSet<String> activeAgents = activeAgentsForContext(contextId);
                activeAgents.add(aid);
                activeAgents.expire(KEY_TTL);
            }
        } catch (Exception e) {
            log.warn("Failed to register active chat turn, contextId={}, agentId={}", contextId, aid, e);
        }
    }

    public void unregister(String contextId, String agentId) {
        if (!StringUtils.hasText(contextId)) {
            return;
        }
        String aid = normalizeAgentId(agentId);
        try {
            RAtomicLong counter = turnCounter(contextId, aid);
            if (!counter.isExists()) {
                return;
            }
            long count = counter.decrementAndGet();
            if (count <= 0) {
                counter.delete();
                activeAgentsForContext(contextId).remove(aid);
            } else {
                counter.expire(KEY_TTL);
            }
        } catch (Exception e) {
            log.warn("Failed to unregister active chat turn, contextId={}, agentId={}", contextId, aid, e);
        }
    }

    public boolean isActive(String contextId, String agentId) {
        if (!StringUtils.hasText(contextId)) {
            return false;
        }
        try {
            RAtomicLong counter = turnCounter(contextId, normalizeAgentId(agentId));
            return counter.isExists() && counter.get() > 0;
        } catch (Exception e) {
            log.warn("Failed to check active chat turn, contextId={}, agentId={}", contextId, agentId, e);
            return false;
        }
    }

    /**
     * 该 contextId 下是否存在任意 agent 的流式对话。
     */
    public boolean isAnyActive(String contextId) {
        if (!StringUtils.hasText(contextId)) {
            return false;
        }
        try {
            return !activeAgentsForContext(contextId).isEmpty();
        } catch (Exception e) {
            log.warn("Failed to check any active chat turn, contextId={}", contextId, e);
            return false;
        }
    }

    private static String normalizeAgentId(String agentId) {
        return agentId == null ? ConversationIdCodec.LEGACY_AGENT_ID : agentId;
    }

    private static String turnKey(String contextId, String agentId) {
        return contextId.trim() + ":" + normalizeAgentId(agentId);
    }

    private RAtomicLong turnCounter(String contextId, String agentId) {
        return redissonClient.getAtomicLong(turnCounterPrefix + turnKey(contextId, agentId));
    }

    private RSet<String> activeAgentsForContext(String contextId) {
        return redissonClient.getSet(contextActiveAgentsPrefix + contextId.trim());
    }
}
