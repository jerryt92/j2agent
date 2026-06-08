package io.github.jerryt92.j2agent.service.file.oss.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jerryt92.j2agent.config.ObjectStorageProperties;
import io.github.jerryt92.j2agent.config.RedisKeyNamespaces;
import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectDeleteReconcileTask;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.redisson.codec.TypedJsonJacksonCodec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@ConditionalOnBean(ObjectStorageService.class)
public class ObjectDeleteReconcileQueueService {

    private final ObjectStorageProperties properties;
    private final RBlockingQueue<ObjectDeleteReconcileTask> readyQueue;
    private final RDelayedQueue<ObjectDeleteReconcileTask> delayedQueue;

    public ObjectDeleteReconcileQueueService(
            RedissonClient redissonClient,
            ObjectStorageProperties properties,
            ObjectMapper objectMapper,
            RedisKeyNamespaces redisKeyNamespaces
    ) {
        this.properties = properties;
        TypedJsonJacksonCodec taskCodec = new TypedJsonJacksonCodec(ObjectDeleteReconcileTask.class, objectMapper);
        this.readyQueue = redissonClient.getBlockingQueue(
                redisKeyNamespaces.key("delete:reconcile:ready"), taskCodec);
        this.delayedQueue = redissonClient.getDelayedQueue(readyQueue);
    }

    public boolean isEnabled() {
        return properties.getDelete().getReconcile().isEnabled();
    }

    public void scheduleFirst(String bucket, String objectKey) {
        ObjectStorageProperties.Delete.Reconcile reconcile = properties.getDelete().getReconcile();
        schedule(
                new ObjectDeleteReconcileTask(bucket, objectKey, 1),
                ObjectUploadReconcileDelayCalculator.delaySeconds(
                        1,
                        reconcile.getInitialDelaySeconds(),
                        reconcile.getMaxDelaySeconds()
                )
        );
    }

    public void schedule(ObjectDeleteReconcileTask task, int delaySeconds) {
        if (!isEnabled()) {
            return;
        }
        delayedQueue.offer(task, delaySeconds, TimeUnit.SECONDS);
    }

    public ObjectDeleteReconcileTask take() throws InterruptedException {
        return readyQueue.take();
    }
}
