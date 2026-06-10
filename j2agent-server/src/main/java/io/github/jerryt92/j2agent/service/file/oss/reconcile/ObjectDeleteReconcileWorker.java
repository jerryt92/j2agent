package io.github.jerryt92.j2agent.service.file.oss.reconcile;

import io.github.jerryt92.j2agent.config.storage.ObjectStorageProperties;
import io.github.jerryt92.j2agent.mapper.ObjectFileMapper;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import io.github.jerryt92.j2agent.service.file.oss.ObjectFileManagementService;
import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.model.DeleteReconcileOutcome;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectDeleteReconcileTask;
import io.github.jerryt92.j2agent.service.file.oss.util.ObjectKeyUtils;
import org.redisson.api.RLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@ConditionalOnBean(ObjectStorageService.class)
public class ObjectDeleteReconcileWorker {
    private static final Logger log = LoggerFactory.getLogger(ObjectDeleteReconcileWorker.class);
    private static final String DELETING = "DELETING";
    private static final String ERROR = "ERROR";

    private final ObjectDeleteReconcileQueueService queueService;
    private final ObjectFileManagementService fileService;
    private final ObjectStorageService storageService;
    private final ObjectFileMapper fileMapper;
    private final ObjectFileLockService lockService;
    private final ObjectStorageProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    public ObjectDeleteReconcileWorker(
            ObjectDeleteReconcileQueueService queueService,
            ObjectFileManagementService fileService,
            ObjectStorageService storageService,
            ObjectFileMapper fileMapper,
            ObjectFileLockService lockService,
            ObjectStorageProperties properties
    ) {
        this.queueService = queueService;
        this.fileService = fileService;
        this.storageService = storageService;
        this.fileMapper = fileMapper;
        this.lockService = lockService;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!queueService.isEnabled()) {
            return;
        }
        recoverDeletingRecords();
        startWorker();
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    void recoverDeletingRecords() {
        String bucket = storageService.getDefaultBucket();
        List<ObjectFilePo> deleting = fileMapper.selectByBucketAndStatus(bucket, DELETING);
        for (ObjectFilePo po : deleting) {
            queueService.scheduleFirst(bucket, po.getObjectKey());
        }
        if (!deleting.isEmpty()) {
            log.info("Re-scheduled {} DELETING object file reconcile tasks after startup", deleting.size());
        }
    }

    private void startWorker() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        workerThread = new Thread(this::loop, "delete-reconcile-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void loop() {
        while (running.get()) {
            try {
                ObjectDeleteReconcileTask task = queueService.take();
                process(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Delete reconcile worker failed to process task", e);
            }
        }
    }

    void process(ObjectDeleteReconcileTask task) {
        RLock lock = lockService.lock(task.objectKey());
        lock.lock();
        try {
            ObjectFilePo po = fileMapper.selectByKey(task.bucket(), ObjectKeyUtils.hash(task.objectKey()));
            if (po == null) {
                return;
            }
            if (!DELETING.equals(po.getOperationStatus()) && !ERROR.equals(po.getOperationStatus())) {
                return;
            }
            DeleteReconcileOutcome outcome;
            try {
                outcome = fileService.reconcileDelete(task.objectKey());
            } catch (Exception e) {
                log.warn("Delete reconcile attempt {} failed for {}", task.attempt(), task.objectKey(), e);
                outcome = DeleteReconcileOutcome.NOT_READY;
            }
            if (outcome == DeleteReconcileOutcome.COMPLETED || outcome == DeleteReconcileOutcome.SKIPPED) {
                return;
            }
            ObjectStorageProperties.Delete.Reconcile reconcile = properties.getDelete().getReconcile();
            if (task.attempt() >= reconcile.getMaxAttempts()) {
                log.warn(
                        "Delete reconcile exhausted after {} attempts, leaving ERROR: {}",
                        task.attempt(),
                        task.objectKey()
                );
                return;
            }
            int nextAttempt = task.attempt() + 1;
            int delaySeconds = ObjectUploadReconcileDelayCalculator.delaySeconds(
                    nextAttempt,
                    reconcile.getInitialDelaySeconds(),
                    reconcile.getMaxDelaySeconds()
            );
            queueService.schedule(
                    new ObjectDeleteReconcileTask(task.bucket(), task.objectKey(), nextAttempt),
                    delaySeconds
            );
        } finally {
            lock.unlock();
        }
    }
}
