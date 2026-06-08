package io.github.jerryt92.j2agent.service.llm.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.config.RedisKeyNamespaces;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 Redisson 的 ChatMemoryRepository 装饰：读走 cache-aside，写入与删除后失效对应 key。
 */
@Primary
@Component
public class RedissonCachingChatMemoryRepository implements ChatMemoryRepository {

    /**
     * 是否启用 Redis 缓存（写死在代码中）
     */
    private static final boolean CACHE_ENABLED = true;

    /**
     * 缓存 TTL（秒）
     */
    private static final int CACHE_TTL_SECONDS = 1800;

    private static final TypeReference<List<CachedChatMemoryEntry>> ENTRY_LIST_TYPE =
            new TypeReference<>() {
            };

    private final RedissonClient redissonClient;
    private final ChatMemoryRepository jdbcDelegate;
    private final ObjectMapper objectMapper;
    private final ChatMemoryMessageCodec messageCodec;
    private final String cacheKeyPrefix;

    /**
     * @param jdbcDelegate 纯 JDBC 实现的记忆仓储（Bean 名 jdbcChatMemoryRepository）
     */
    public RedissonCachingChatMemoryRepository(
            RedissonClient redissonClient,
            @Qualifier("jdbcChatMemoryRepository") ChatMemoryRepository jdbcDelegate,
            ObjectMapper objectMapper,
            ChatMemoryMessageCodec messageCodec,
            RedisKeyNamespaces redisKeyNamespaces) {
        this.redissonClient = redissonClient;
        this.jdbcDelegate = jdbcDelegate;
        this.objectMapper = objectMapper;
        this.messageCodec = messageCodec;
        this.cacheKeyPrefix = redisKeyNamespaces.key("chat-memory:");
    }

    /**
     * 列出全部会话 ID，不做缓存（数据量大且语义不适合短期缓存）。
     */
    @Override
    public List<String> findConversationIds() {
        return jdbcDelegate.findConversationIds();
    }

    /**
     * 按会话读取消息：先读 Redis，未命中再查库并回填 TTL。
     */
    @Override
    public List<Message> findByConversationId(String conversationId) {
        if (!CACHE_ENABLED) {
            return jdbcDelegate.findByConversationId(conversationId);
        }
        RBucket<String> bucket = stringBucket(conversationId);
        String json = bucket.get();
        if (json != null) {
            try {
                List<CachedChatMemoryEntry> entries = objectMapper.readValue(json, ENTRY_LIST_TYPE);
                return fromEntries(entries);
            } catch (Exception e) {
                bucket.deleteAsync();
            }
        }
        List<Message> messages = jdbcDelegate.findByConversationId(conversationId);
        putCache(conversationId, messages);
        return messages;
    }

    /**
     * 持久化消息后删除缓存，避免与库不一致。
     */
    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        jdbcDelegate.saveAll(conversationId, messages);
        evictIfEnabled(conversationId);
    }

    /**
     * 删除会话数据后同步删除缓存。
     */
    @Override
    public void deleteByConversationId(String conversationId) {
        jdbcDelegate.deleteByConversationId(conversationId);
        evictIfEnabled(conversationId);
    }

    private void putCache(String conversationId, List<Message> messages) {
        if (!CACHE_ENABLED) {
            return;
        }
        try {
            List<CachedChatMemoryEntry> entries = toEntries(messages);
            String json = objectMapper.writeValueAsString(entries);
            stringBucket(conversationId).set(json, Duration.ofSeconds(CACHE_TTL_SECONDS));
        } catch (Exception ignored) {
            // 缓存写入失败不阻断主流程
        }
    }

    private void evictIfEnabled(String conversationId) {
        if (!CACHE_ENABLED) {
            return;
        }
        stringBucket(conversationId).deleteAsync();
    }

    private RBucket<String> stringBucket(String conversationId) {
        return redissonClient.getBucket(cacheKeyPrefix + conversationId, StringCodec.INSTANCE);
    }

    private List<CachedChatMemoryEntry> toEntries(List<Message> messages) {
        List<CachedChatMemoryEntry> entries = new ArrayList<>();
        for (Message m : messages) {
            try {
                ChatMemoryMessageCodec.PersistedRow row = messageCodec.encode(m);
                if (row == null) {
                    continue;
                }
                entries.add(new CachedChatMemoryEntry(row.chatRole(), row.content(), row.metaJson()));
            } catch (JsonProcessingException ignored) {
                // 单条序列化失败则跳过，避免整批缓存失败
            }
        }
        return entries;
    }

    private List<Message> fromEntries(List<CachedChatMemoryEntry> entries) {
        List<Message> list = new ArrayList<>();
        if (entries == null) {
            return list;
        }
        for (CachedChatMemoryEntry e : entries) {
            Message m = messageCodec.decode(e.chatRole(), e.content(), e.metaJson());
            if (m != null) {
                list.add(m);
            }
        }
        return list;
    }
}
