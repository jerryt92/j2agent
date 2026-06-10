package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.config.chat.ActiveChatTurnProperties;
import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings({"unchecked", "rawtypes"})
class ActiveChatTurnRegistryTest {

    private static final String APP = "test-app";
    private static final String CONTEXT_ID = "ctx-1";
    private static final String AGENT_ID = "agent-a";

    @Mock
    private RedissonClient redissonClient;

    private ActiveChatTurnRegistry registry;
    private ActiveChatTurnProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ActiveChatTurnProperties();
        registry = new ActiveChatTurnRegistry(redissonClient, new RedisKeyNamespaces(APP), properties);
    }

    @Test
    void registerThenIsActiveTrueWhenCounterAndHeartbeatExist() {
        RAtomicLong counter = mockCounter(1L, true);
        RSet<String> activeAgents = mockActiveAgents();
        RBucket<String> heartbeat = mockHeartbeat(true);

        when(redissonClient.getAtomicLong(counterKey())).thenReturn(counter);
        when(redissonClient.getSet(ctxSetKey())).thenReturn((RSet) activeAgents);
        when(redissonClient.getBucket(heartbeatKey(), StringCodec.INSTANCE)).thenReturn((RBucket) heartbeat);

        registry.register(CONTEXT_ID, AGENT_ID);

        assertTrue(registry.isActive(CONTEXT_ID, AGENT_ID));
        verify(heartbeat).set(org.mockito.ArgumentMatchers.matches("\\d+"),
                eq((long) properties.getHeartbeatTtlSeconds()), eq(TimeUnit.SECONDS));
    }

    @Test
    void unregisterClearsCounterSetAndHeartbeat() {
        RAtomicLong counter = mockCounter(1L, true);
        RSet<String> activeAgents = mockActiveAgents();
        RBucket<String> heartbeat = mockHeartbeat(true);

        when(redissonClient.getAtomicLong(counterKey())).thenReturn(counter);
        when(redissonClient.getSet(ctxSetKey())).thenReturn((RSet) activeAgents);
        when(redissonClient.getBucket(heartbeatKey(), StringCodec.INSTANCE)).thenReturn((RBucket) heartbeat);

        registry.unregister(CONTEXT_ID, AGENT_ID);

        verify(counter).delete();
        verify(activeAgents).remove(AGENT_ID);
        verify(heartbeat).delete();
    }

    @Test
    void isActiveFalseWhenHeartbeatExpiredButCounterRemains() {
        RAtomicLong counter = mockCounter(1L, true);
        RSet<String> activeAgents = mockActiveAgents();
        RBucket<String> heartbeat = mockHeartbeatWithValue(String.valueOf(System.currentTimeMillis() - 60_000L));

        when(redissonClient.getAtomicLong(counterKey())).thenReturn(counter);
        when(redissonClient.getSet(ctxSetKey())).thenReturn((RSet) activeAgents);
        when(redissonClient.getBucket(heartbeatKey(), StringCodec.INSTANCE)).thenReturn((RBucket) heartbeat);

        assertFalse(registry.isActive(CONTEXT_ID, AGENT_ID));
        verify(counter).delete();
        verify(activeAgents).remove(AGENT_ID);
    }

    @Test
    void isActiveFalseWhenHeartbeatMissing() {
        RAtomicLong counter = mockCounter(1L, true);
        RSet<String> activeAgents = mockActiveAgents();
        RBucket<String> heartbeat = mockHeartbeatWithValue(null);

        when(redissonClient.getAtomicLong(counterKey())).thenReturn(counter);
        when(redissonClient.getSet(ctxSetKey())).thenReturn((RSet) activeAgents);
        when(redissonClient.getBucket(heartbeatKey(), StringCodec.INSTANCE)).thenReturn((RBucket) heartbeat);

        assertFalse(registry.isActive(CONTEXT_ID, AGENT_ID));
        verify(counter).delete();
    }

    @Test
    void touchRefreshesHeartbeatTtl() {
        RBucket<String> heartbeat = mockHeartbeat(true);
        when(redissonClient.getBucket(heartbeatKey(), StringCodec.INSTANCE)).thenReturn((RBucket) heartbeat);

        registry.touch(CONTEXT_ID, AGENT_ID);

        verify(heartbeat).set(org.mockito.ArgumentMatchers.matches("\\d+"),
                eq((long) properties.getHeartbeatTtlSeconds()), eq(TimeUnit.SECONDS));
    }

    @Test
    void isAnyActiveChecksHeartbeatPerAgentInContextSet() {
        RSet<String> activeAgents = mock(RSet.class);
        when(activeAgents.readAll()).thenReturn(Set.of(AGENT_ID, "agent-b"));

        RAtomicLong counterA = mockCounter(1L, true);
        RBucket<String> heartbeatA = mockHeartbeat(true);
        RAtomicLong counterB = mockCounter(1L, true);
        RBucket<String> heartbeatB = mockHeartbeatWithValue(String.valueOf(System.currentTimeMillis() - 60_000L));

        when(redissonClient.getSet(ctxSetKey())).thenReturn((RSet) activeAgents);
        when(redissonClient.getAtomicLong(counterKey(CONTEXT_ID, AGENT_ID))).thenReturn(counterA);
        when(redissonClient.getBucket(heartbeatKey(CONTEXT_ID, AGENT_ID), StringCodec.INSTANCE)).thenReturn((RBucket) heartbeatA);
        when(redissonClient.getAtomicLong(counterKey(CONTEXT_ID, "agent-b"))).thenReturn(counterB);
        when(redissonClient.getBucket(heartbeatKey(CONTEXT_ID, "agent-b"), StringCodec.INSTANCE)).thenReturn((RBucket) heartbeatB);

        assertTrue(registry.isAnyActive(CONTEXT_ID));
    }

    @Test
    void sweepStaleTurnsRemovesCounterWithoutHeartbeat() {
        RKeys keys = mock(RKeys.class);
        RAtomicLong counter = mockCounter(1L, true);
        RSet<String> activeAgents = mockActiveAgents();
        RBucket<String> heartbeat = mockHeartbeatWithValue(String.valueOf(System.currentTimeMillis() - 60_000L));

        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.getKeysByPattern(APP + ":active-chat-turn:*")).thenReturn(Set.of(counterKey()));
        when(redissonClient.getAtomicLong(counterKey())).thenReturn(counter);
        when(redissonClient.getSet(ctxSetKey())).thenReturn((RSet) activeAgents);
        when(redissonClient.getBucket(heartbeatKey(), StringCodec.INSTANCE)).thenReturn((RBucket) heartbeat);

        registry.sweepStaleTurns();

        verify(counter).delete();
        verify(activeAgents).remove(AGENT_ID);
        verify(heartbeat).delete();
    }

    @Test
    void sweepStaleTurnsKeepsActiveTurnWithHeartbeat() {
        RKeys keys = mock(RKeys.class);
        RAtomicLong counter = mockCounter(1L, true);
        RBucket<String> heartbeat = mockHeartbeat(true);

        when(redissonClient.getKeys()).thenReturn(keys);
        when(keys.getKeysByPattern(APP + ":active-chat-turn:*")).thenReturn(Set.of(counterKey()));
        when(redissonClient.getBucket(heartbeatKey(), StringCodec.INSTANCE)).thenReturn((RBucket) heartbeat);

        registry.sweepStaleTurns();

        verify(counter, never()).delete();
    }

    private RAtomicLong mockCounter(long value, boolean exists) {
        RAtomicLong counter = mock(RAtomicLong.class);
        when(counter.incrementAndGet()).thenReturn(value);
        when(counter.decrementAndGet()).thenReturn(value - 1);
        when(counter.isExists()).thenReturn(exists);
        when(counter.get()).thenReturn(value);
        return counter;
    }

    private RSet<String> mockActiveAgents() {
        return mock(RSet.class);
    }

    private RBucket<String> mockHeartbeat(boolean exists) {
        return mockHeartbeatWithValue(exists ? String.valueOf(System.currentTimeMillis()) : null);
    }

    private RBucket<String> mockHeartbeatWithValue(String value) {
        RBucket<String> heartbeat = mock(RBucket.class);
        when(heartbeat.get()).thenReturn(value);
        when(heartbeat.isExists()).thenReturn(value != null);
        return heartbeat;
    }

    private String counterKey() {
        return counterKey(CONTEXT_ID, AGENT_ID);
    }

    private String counterKey(String contextId, String agentId) {
        return APP + ":active-chat-turn:" + contextId + ":" + agentId;
    }

    private String ctxSetKey() {
        return APP + ":active-chat-turn-ctx:" + CONTEXT_ID;
    }

    private String heartbeatKey() {
        return heartbeatKey(CONTEXT_ID, AGENT_ID);
    }

    private String heartbeatKey(String contextId, String agentId) {
        return APP + ":active-chat-turn-hb:" + contextId + ":" + agentId;
    }
}
