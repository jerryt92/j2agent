package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.config.chat.ActiveChatTurnProperties;
import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 登记正在进行 WebSocket 流式对话的 contextId + agentId，供删除历史等接口拒绝误删。
 * 配合心跳 key 看门狗：无活动续期则不再视为进行中，并由定时任务清扫孤儿 counter。
 */
@Slf4j
@Component
public class ActiveChatTurnRegistry {

    private final RedissonClient redissonClient;
    private final ActiveChatTurnProperties properties;
    private final String turnCounterPrefix;
    private final String contextActiveAgentsPrefix;
    private final String heartbeatPrefix;

    public ActiveChatTurnRegistry(RedissonClient redissonClient,
                                  RedisKeyNamespaces redisKeyNamespaces,
                                  ActiveChatTurnProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.turnCounterPrefix = redisKeyNamespaces.key("active-chat-turn:");
        this.contextActiveAgentsPrefix = redisKeyNamespaces.key("active-chat-turn-ctx:");
        this.heartbeatPrefix = redisKeyNamespaces.key("active-chat-turn-hb:");
    }

    public void register(String contextId, String agentId) {
        if (!StringUtils.hasText(contextId)) {
            return;
        }
        String aid = requireAgentId(agentId);
        try {
            RAtomicLong counter = turnCounter(contextId, aid);
            long count = counter.incrementAndGet();
            counter.expire(keyFallbackTtl());
            if (count == 1) {
                RSet<String> activeAgents = activeAgentsForContext(contextId);
                activeAgents.add(aid);
                activeAgents.expire(keyFallbackTtl());
            }
            touchHeartbeat(contextId, aid);
        } catch (Exception e) {
            log.warn("Failed to register active chat turn, contextId={}, agentId={}", contextId, aid, e);
        }
    }

    public void unregister(String contextId, String agentId) {
        if (!StringUtils.hasText(contextId)) {
            return;
        }
        String aid = requireAgentId(agentId);
        try {
            RAtomicLong counter = turnCounter(contextId, aid);
            if (!counter.isExists()) {
                clearHeartbeat(contextId, aid);
                return;
            }
            long count = counter.decrementAndGet();
            if (count <= 0) {
                counter.delete();
                activeAgentsForContext(contextId).remove(aid);
            } else {
                counter.expire(keyFallbackTtl());
            }
            clearHeartbeat(contextId, aid);
        } catch (Exception e) {
            log.warn("Failed to unregister active chat turn, contextId={}, agentId={}", contextId, aid, e);
        }
    }

    /**
     * 流式进行中续期心跳，供看门狗判定活动是否仍在进行。
     */
    public void touch(String contextId, String agentId) {
        if (!StringUtils.hasText(contextId)) {
            return;
        }
        String aid = requireAgentId(agentId);
        try {
            touchHeartbeat(contextId, aid);
        } catch (Exception e) {
            log.warn("Failed to touch active chat turn heartbeat, contextId={}, agentId={}", contextId, aid, e);
        }
    }

    public boolean isActive(String contextId, String agentId) {
        if (!StringUtils.hasText(contextId)) {
            return false;
        }
        String aid = requireAgentId(agentId);
        try {
            RAtomicLong counter = turnCounter(contextId, aid);
            if (!counter.isExists() || counter.get() <= 0) {
                return false;
            }
            if (!isHeartbeatAlive(contextId, aid)) {
                forceCleanup(contextId, aid);
                return false;
            }
            return true;
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
            RSet<String> activeAgents = activeAgentsForContext(contextId);
            for (String aid : activeAgents.readAll()) {
                if (isActive(contextId, aid)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to check any active chat turn, contextId={}", contextId, e);
            return false;
        }
    }

    /**
     * 清扫 counter 存在但心跳已过期的孤儿登记。
     */
    public void sweepStaleTurns() {
        try {
            RKeys keys = redissonClient.getKeys();
            Iterable<String> counterKeys = keys.getKeysByPattern(turnCounterPrefix + "*");
            for (String key : counterKeys) {
                if (!key.startsWith(turnCounterPrefix)) {
                    continue;
                }
                String suffix = key.substring(turnCounterPrefix.length());
                int sep = suffix.lastIndexOf(':');
                if (sep <= 0 || sep >= suffix.length() - 1) {
                    continue;
                }
                String contextId = suffix.substring(0, sep);
                String agentId = suffix.substring(sep + 1);
                if (!isHeartbeatAlive(contextId, agentId)) {
                    forceCleanup(contextId, agentId);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to sweep stale active chat turns", e);
        }
    }

    private void forceCleanup(String contextId, String agentId) {
        String aid = requireAgentId(agentId);
        try {
            turnCounter(contextId, aid).delete();
            activeAgentsForContext(contextId).remove(aid);
            clearHeartbeat(contextId, aid);
            log.warn("Swept stale active chat turn, contextId={}, agentId={}", contextId, aid);
        } catch (Exception e) {
            log.warn("Failed to force cleanup active chat turn, contextId={}, agentId={}", contextId, aid, e);
        }
    }

    private boolean isHeartbeatAlive(String contextId, String agentId) {
        RBucket<String> bucket = heartbeatBucket(contextId, agentId);
        String raw = bucket.get();
        if (!StringUtils.hasText(raw)) {
            return false;
        }
        try {
            long lastTouchMs = Long.parseLong(raw.trim());
            long ageMs = System.currentTimeMillis() - lastTouchMs;
            return ageMs <= properties.getHeartbeatTtlSeconds() * 1000L;
        } catch (NumberFormatException ignored) {
            // 兼容旧版占位值 "1"：退化为 key 是否存在
            return bucket.isExists();
        }
    }

    private void touchHeartbeat(String contextId, String agentId) {
        heartbeatBucket(contextId, agentId)
                .set(String.valueOf(System.currentTimeMillis()),
                        properties.getHeartbeatTtlSeconds(),
                        TimeUnit.SECONDS);
    }

    private void clearHeartbeat(String contextId, String agentId) {
        heartbeatBucket(contextId, agentId).delete();
    }

    private Duration keyFallbackTtl() {
        return Duration.ofHours(properties.getKeyFallbackTtlHours());
    }

    private static String requireAgentId(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            throw new IllegalArgumentException("agentId must not be blank.");
        }
        return agentId.trim();
    }

    private static String turnKey(String contextId, String agentId) {
        return contextId.trim() + ":" + requireAgentId(agentId);
    }

    private RAtomicLong turnCounter(String contextId, String agentId) {
        return redissonClient.getAtomicLong(turnCounterPrefix + turnKey(contextId, agentId));
    }

    private RSet<String> activeAgentsForContext(String contextId) {
        return redissonClient.getSet(contextActiveAgentsPrefix + contextId.trim());
    }

    private RBucket<String> heartbeatBucket(String contextId, String agentId) {
        return redissonClient.getBucket(heartbeatPrefix + turnKey(contextId, agentId), StringCodec.INSTANCE);
    }
}
