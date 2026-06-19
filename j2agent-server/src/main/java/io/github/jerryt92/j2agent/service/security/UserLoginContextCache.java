package io.github.jerryt92.j2agent.service.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.model.security.UserContextBo;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * 登录用户上下文 Redis 缓存：按 sid 存储 role/permissions，TTL 与 JWT 一致。
 */
@Service
public class UserLoginContextCache {

    private static final Logger log = LoggerFactory.getLogger(UserLoginContextCache.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String ctxKeyPrefix;
    private final String userSidsKeyPrefix;

    public UserLoginContextCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            RedisKeyNamespaces redisKeyNamespaces) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ctxKeyPrefix = redisKeyNamespaces.key("login:ctx:");
        this.userSidsKeyPrefix = redisKeyNamespaces.key("login:user-sids:");
    }

    public void save(String sid, String userId, UserContextBo ctx, long ttlSeconds) {
        if (StringUtils.isBlank(sid) || ttlSeconds <= 0) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(ctx);
            redisTemplate.opsForValue().set(ctxKeyPrefix + sid, json, Duration.ofSeconds(ttlSeconds));
            if (StringUtils.isNotBlank(userId)) {
                String userSidsKey = userSidsKeyPrefix + userId;
                redisTemplate.opsForSet().add(userSidsKey, sid);
                redisTemplate.expire(userSidsKey, Duration.ofSeconds(ttlSeconds));
            }
        } catch (JsonProcessingException e) {
            log.error("failed to serialize user context for sid={}", sid, e);
        } catch (DataAccessException e) {
            log.error("redis unavailable when saving user context for sid={}", sid, e);
        }
    }

    public Optional<UserContextBo> get(String sid) {
        if (StringUtils.isBlank(sid)) {
            return Optional.empty();
        }
        try {
            String json = redisTemplate.opsForValue().get(ctxKeyPrefix + sid);
            if (StringUtils.isBlank(json)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, UserContextBo.class));
        } catch (JsonProcessingException e) {
            log.warn("failed to deserialize user context for sid={}", sid, e);
            return Optional.empty();
        } catch (DataAccessException e) {
            log.error("redis unavailable when loading user context for sid={}", sid, e);
            return Optional.empty();
        }
    }

    public void remove(String sid, String userId) {
        if (StringUtils.isBlank(sid)) {
            return;
        }
        try {
            redisTemplate.delete(ctxKeyPrefix + sid);
            if (StringUtils.isNotBlank(userId)) {
                redisTemplate.opsForSet().remove(userSidsKeyPrefix + userId, sid);
            }
        } catch (DataAccessException e) {
            log.error("redis unavailable when removing user context for sid={}", sid, e);
        }
    }

    public void invalidateByUserId(String userId) {
        if (StringUtils.isBlank(userId)) {
            return;
        }
        try {
            String userSidsKey = userSidsKeyPrefix + userId;
            Set<String> sids = redisTemplate.opsForSet().members(userSidsKey);
            if (sids != null) {
                for (String sid : sids) {
                    redisTemplate.delete(ctxKeyPrefix + sid);
                }
            }
            redisTemplate.delete(userSidsKey);
        } catch (DataAccessException e) {
            log.error("redis unavailable when invalidating user contexts for userId={}", userId, e);
        }
    }
}
