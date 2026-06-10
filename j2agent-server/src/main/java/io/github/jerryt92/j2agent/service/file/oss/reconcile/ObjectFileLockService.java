package io.github.jerryt92.j2agent.service.file.oss.reconcile;

import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * 按 objectKey 串行化文件写操作与后台对账任务。
 */
@Service
@ConditionalOnBean(ObjectStorageService.class)
public class ObjectFileLockService {

    private final RedissonClient redissonClient;
    private final String lockPrefix;

    public ObjectFileLockService(RedissonClient redissonClient, RedisKeyNamespaces redisKeyNamespaces) {
        this.redissonClient = redissonClient;
        this.lockPrefix = redisKeyNamespaces.key("object-file:lock:");
    }

    public RLock lock(String objectKey) {
        return redissonClient.getLock(lockPrefix + objectKey);
    }
}
