package io.github.jerryt92.j2agent.service.file.oss.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.config.storage.ObjectStorageProperties;
import io.github.jerryt92.j2agent.config.redis.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectUploadReconcileTask;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RBucket;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnBean(ObjectStorageService.class)
public class ObjectUploadReconcileQueueService {

    private final RedissonClient redissonClient;
    private final ObjectStorageProperties properties;
    private final RBlockingQueue<ObjectUploadReconcileTask> readyQueue;
    private final RDelayedQueue<ObjectUploadReconcileTask> delayedQueue;
    private final RSet<String> cancelledKeys;
    private final String heartbeatPrefix;

    public ObjectUploadReconcileQueueService(
            RedissonClient redissonClient,
            ObjectStorageProperties properties,
            ObjectMapper objectMapper,
            RedisKeyNamespaces redisKeyNamespaces
    ) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        TypedJsonJacksonCodec taskCodec = new TypedJsonJacksonCodec(ObjectUploadReconcileTask.class, objectMapper);
        this.readyQueue = redissonClient.getBlockingQueue(
                redisKeyNamespaces.key("upload:reconcile:ready"), taskCodec);
        this.delayedQueue = redissonClient.getDelayedQueue(readyQueue);
        this.cancelledKeys = redissonClient.getSet(
                redisKeyNamespaces.key("upload:reconcile:cancelled"), StringCodec.INSTANCE);
        this.heartbeatPrefix = redisKeyNamespaces.key("upload:reconcile:heartbeat:");
    }

    public boolean isEnabled() {
        return properties.getUpload().getReconcile().isEnabled();
    }

    public void scheduleFirst(String bucket, String objectKey) {
        ObjectStorageProperties.Upload.Reconcile reconcile = properties.getUpload().getReconcile();
        schedule(
                new ObjectUploadReconcileTask(bucket, objectKey, 1),
                reconcile.getRetryDelaySeconds()
        );
    }

    public void schedule(ObjectUploadReconcileTask task, int delaySeconds) {
        if (!isEnabled()) {
            return;
        }
        delayedQueue.offer(task, delaySeconds, TimeUnit.SECONDS);
    }

    public ObjectUploadReconcileTask take() throws InterruptedException {
        return readyQueue.take();
    }

    public void markCancelled(String objectKey) {
        cancelledKeys.add(objectKey);
    }

    public boolean isCancelled(String objectKey) {
        return cancelledKeys.contains(objectKey);
    }

    public void clearCancelled(String objectKey) {
        cancelledKeys.remove(objectKey);
    }

    public void touchHeartbeat(String objectKey) {
        int ttlSeconds = properties.getUpload().getReconcile().getHeartbeatTtlSeconds();
        heartbeatBucket(objectKey).set("1", ttlSeconds, TimeUnit.SECONDS);
    }

    public boolean isUploadInProgress(String objectKey) {
        return heartbeatBucket(objectKey).isExists();
    }

    public void clearHeartbeat(String objectKey) {
        heartbeatBucket(objectKey).delete();
    }

    public int inProgressDelaySeconds() {
        return properties.getUpload().getReconcile().getInProgressDelaySeconds();
    }

    private RBucket<String> heartbeatBucket(String objectKey) {
        return redissonClient.getBucket(heartbeatPrefix + objectKey, StringCodec.INSTANCE);
    }
}
