package io.github.jerryt92.j2agent.service.file.oss.reconcile;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;

/**
 * 按 objectKey 串行化文件写操作与后台对账任务。
 */
@Service
@ConditionalOnBean(ObjectStorageService.class)
public class ObjectFileLockService {
    static final String LOCK_PREFIX = "j2agent:object-file:lock:";

    private final RedissonClient redissonClient;

    public ObjectFileLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public RLock lock(String objectKey) {
        return redissonClient.getLock(LOCK_PREFIX + objectKey);
    }
}
