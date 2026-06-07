package io.github.jerryt92.j2agent.service.file.oss.reconcile;

import io.github.jerryt92.j2agent.service.file.oss.ObjectFileManagementService;
import io.github.jerryt92.j2agent.service.file.oss.ObjectStorageService;
import io.github.jerryt92.j2agent.service.file.oss.model.ObjectUploadReconcileTask;
import io.github.jerryt92.j2agent.service.file.oss.model.UploadReconcileOutcome;
import io.github.jerryt92.j2agent.service.file.oss.util.ObjectKeyUtils;

import io.github.jerryt92.j2agent.config.ObjectStorageProperties;
import io.github.jerryt92.j2agent.mapper.ObjectFileMapper;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
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
public class ObjectUploadReconcileWorker {
    private static final Logger log = LoggerFactory.getLogger(ObjectUploadReconcileWorker.class);
    private static final String UPLOADING = "UPLOADING";

    private final ObjectUploadReconcileQueueService queueService;
    private final ObjectFileManagementService fileService;
    private final ObjectStorageService storageService;
    private final ObjectFileMapper fileMapper;
    private final ObjectFileLockService lockService;
    private final ObjectStorageProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread workerThread;

    public ObjectUploadReconcileWorker(
            ObjectUploadReconcileQueueService queueService,
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
        recoverUploadingRecords();
        startWorker();
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }

    void recoverUploadingRecords() {
        String bucket = storageService.getDefaultBucket();
        List<ObjectFilePo> uploading = fileMapper.selectByBucketAndStatus(bucket, UPLOADING);
        for (ObjectFilePo po : uploading) {
            queueService.scheduleFirst(bucket, po.getObjectKey());
        }
        if (!uploading.isEmpty()) {
            log.info("Re-scheduled {} UPLOADING object file reconcile tasks after startup", uploading.size());
        }
    }

    private void startWorker() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        workerThread = new Thread(this::loop, "upload-reconcile-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private void loop() {
        while (running.get()) {
            try {
                ObjectUploadReconcileTask task = queueService.take();
                process(task);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Upload reconcile worker failed to process task", e);
            }
        }
    }

    void process(ObjectUploadReconcileTask task) {
        RLock lock = lockService.lock(task.objectKey());
        lock.lock();
        try {
            if (queueService.isCancelled(task.objectKey())) {
                queueService.clearCancelled(task.objectKey());
                return;
            }
            ObjectFilePo po = fileMapper.selectByKey(task.bucket(), ObjectKeyUtils.hash(task.objectKey()));
            if (po == null) {
                return;
            }
            if (!UPLOADING.equals(po.getOperationStatus())) {
                return;
            }
            if (storageService.objectExists(task.bucket(), task.objectKey())) {
                UploadReconcileOutcome outcome = fileService.reconcileDirectUpload(task.objectKey());
                if (outcome == UploadReconcileOutcome.COMPLETED) {
                    return;
                }
            }
            if (queueService.isUploadInProgress(task.objectKey())) {
                queueService.schedule(
                        new ObjectUploadReconcileTask(task.bucket(), task.objectKey(), 1),
                        queueService.inProgressDelaySeconds()
                );
                return;
            }
            ObjectStorageProperties.Upload.Reconcile reconcile = properties.getUpload().getReconcile();
            if (task.attempt() >= reconcile.getMaxAttempts()) {
                fileService.forceCleanupUpload(task.objectKey());
                log.info(
                        "Cleaned up stale direct upload after {} reconcile attempts: {}",
                        task.attempt(),
                        task.objectKey()
                );
                return;
            }
            int nextAttempt = task.attempt() + 1;
            int delaySeconds = reconcile.getRetryDelaySeconds();
            queueService.schedule(
                    new ObjectUploadReconcileTask(task.bucket(), task.objectKey(), nextAttempt),
                    delaySeconds
            );
        } finally {
            lock.unlock();
        }
    }
}