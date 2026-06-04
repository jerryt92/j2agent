package io.github.jerryt92.j2agent.config;

import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.service.PropertiesService;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import io.github.jerryt92.j2agent.utils.HashUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@DependsOn("flywayInitializer")
public class VectorDatabaseInit {
    private final EmbeddingService embeddingService;
    private final VectorDatabaseService vectorDatabaseService;
    private final PropertiesService propertiesService;
    private volatile String metricType;
    private final ExecutorService initExecutor = Executors.newSingleThreadExecutor();
    private Future<?> currentTask;

    public VectorDatabaseInit(EmbeddingService embeddingService, VectorDatabaseService vectorDatabaseService, PropertiesService propertiesService) {
        this.embeddingService = embeddingService;
        this.vectorDatabaseService = vectorDatabaseService;
        this.propertiesService = propertiesService;
    }

    /**
     * 在 Flyway 等启动初始化完成后提交向量库初始化，但仍早于知识库目录同步。
     */
    @Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 100)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        init();
    }

    /**
     * 阻塞等待当前初始化任务结束；知识库同步应在调用后再写入 Milvus。
     *
     * @param timeout 建议不小于向量库重试总时长（如 50×5s）
     */
    public void awaitInitTask(Duration timeout) {
        Future<?> task = currentTask;
        if (task == null) {
            log.warn("向量库初始化任务尚未提交，跳过等待");
            return;
        }
        try {
            task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("等待向量库初始化超时: {} ms", timeout.toMillis(), e);
        } catch (ExecutionException e) {
            log.warn("向量库初始化任务异常结束", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 加锁 synchronized，防止 reload 和启动同时触发导致产生多个任务
     */
    public synchronized void init() {
        // 1. 如果有正在运行的任务，尝试取消它
        if (currentTask != null && !currentTask.isDone()) {
            log.info("Stopping previous initialization task...");
            // 发送中断信号
            currentTask.cancel(true);
        }
        // 2. 提交新任务
        currentTask = initExecutor.submit(this::doInitTask);
    }

    private void doInitTask() {
        try {
            if (!embeddingService.hasActiveEmbeddingConfig()) {
                log.info("未设置当前 Embedding 配置，跳过向量库初始化任务");
                return;
            }
            metricType = propertiesService.getProperty(PropertiesService.RETRIEVE_METRIC_TYPE);
            int retryCount = 0;
            boolean isHealthy = false;
            while (!isHealthy && retryCount < 50 && !Thread.currentThread().isInterrupted()) {
                retryCount++;
                embeddingService.init();
                Integer dimension = embeddingService.getDimension();
                if (dimension == null || dimension <= 0) {
                    log.warn("Init skipped: Embedding unavailable (Attempt {}/50). Retrying...", retryCount);
                    sleepBeforeRetry();
                    continue;
                }
                try {
                    vectorDatabaseService.reBuildVectorDatabase(dimension, metricType);
                    isHealthy = true;
                } catch (RuntimeException t) {
                    log.warn("Init failed: Failed to rebuild vector database (Attempt {}/50). Retrying...", retryCount);
                    sleepBeforeRetry();
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                log.info("Initialization task stopped by interrupt.");
                return;
            }
            if (!isHealthy) {
                log.error("Failed to initialize Vector Database after 50 attempts.");
                return;
            }
            log.info("Vector Database initialized successfully.");
        } catch (Exception e) {
            log.error("Unexpected error during Vector Database initialization", e);
        }
    }

    /**
     * 重试间隔统一封装，减少重复中断处理代码。
     */
    private void sleepBeforeRetry() {
        try {
            TimeUnit.SECONDS.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Initialization task interrupted during sleep.");
        }
    }

    public synchronized void reload() {
        if (!embeddingService.hasActiveEmbeddingConfig()) {
            log.info("未设置当前 Embedding 配置，跳过向量库重载检查");
            return;
        }
        // 检查是否需要重建向量数据
        String newMetricType = propertiesService.getProperty(PropertiesService.RETRIEVE_METRIC_TYPE);
        boolean needRebuild = false;
        try {
            EmbeddingModel.EmbeddingsResponse response = embeddingService.embed(embeddingService.checkEmbeddingsRequest);
            if (response != null && !response.getData().isEmpty()) {
                EmbeddingModel.EmbeddingsItem testEmbed = response.getData().getFirst();
                String newCheckEmbeddingHash = HashUtil.getMessageDigest(Arrays.toString(testEmbed.getEmbeddings()).getBytes(), HashUtil.MdAlgorithm.SHA256);
                if (!newCheckEmbeddingHash.equals(embeddingService.getCheckEmbeddingHash())) {
                    needRebuild = true;
                }
                if (!newMetricType.equals(metricType)) {
                    needRebuild = true;
                }
                if (needRebuild) {
                    log.info("Configuration change detected. Reloading...");
                    embeddingService.init();
                    init();
                } else {
                    log.info("No configuration changes detected.");
                }
            } else {
                log.warn("Init failed: Unable to fetch embedding for test input.");
            }
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Hash algorithm not found", e);
        }
    }

    // 容器销毁时关闭线程池
    @PreDestroy
    public void destroy() {
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        initExecutor.shutdownNow();
    }
}
