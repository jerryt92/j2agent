package io.github.jerryt92.j2agent.config;

import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.service.PropertiesService;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import io.github.jerryt92.j2agent.utils.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 向量库维度/度量同步：probe Embedding 后更新 Milvus 期望维度，不创建 collection。
 * 异步编排由 {@link io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMaintenanceCoordinator} 负责。
 */
@Slf4j
@Service
@DependsOn("flywayInitializer")
public class VectorDatabaseInit {

    private final EmbeddingService embeddingService;
    private final VectorDatabaseService vectorDatabaseService;
    private final PropertiesService propertiesService;
    private volatile String metricType;

    public VectorDatabaseInit(EmbeddingService embeddingService,
                              VectorDatabaseService vectorDatabaseService,
                              PropertiesService propertiesService) {
        this.embeddingService = embeddingService;
        this.vectorDatabaseService = vectorDatabaseService;
        this.propertiesService = propertiesService;
    }

    /**
     * 同步 probe 当前 Embedding 并更新 Milvus 期望维度。
     */
    public boolean probeAndConfigure() {
        if (!embeddingService.hasActiveEmbeddingConfig()) {
            log.info("未设置当前 Embedding 配置，跳过向量库 configure");
            return false;
        }
        metricType = propertiesService.getProperty(PropertiesService.RETRIEVE_METRIC_TYPE);
        embeddingService.init();
        Integer dimension = embeddingService.getDimension();
        if (dimension == null || dimension <= 0) {
            log.warn("向量库 configure 跳过：Embedding 未探测到维度, probeError={}",
                    embeddingService.getLastProbeError());
            return false;
        }
        vectorDatabaseService.reBuildVectorDatabase(dimension, metricType);
        log.info("向量库 configure 成功: dimension={}, metricType={}", dimension, metricType);
        return true;
    }

    /**
     * 带重试的 probe + configure，供启动初始化使用。
     */
    public boolean probeAndConfigureWithRetry(int maxAttempts, Duration retryInterval) {
        if (!embeddingService.hasActiveEmbeddingConfig()) {
            log.info("未设置当前 Embedding 配置，跳过向量库初始化");
            return false;
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                log.info("向量库初始化被中断");
                return false;
            }
            if (probeAndConfigure()) {
                return true;
            }
            if (attempt < maxAttempts) {
                sleep(retryInterval);
            }
        }
        log.error("向量库初始化失败：{} 次尝试后仍不可用", maxAttempts);
        return false;
    }

    /**
     * 检测 Embedding 维度/哈希/度量是否变化；若变化则 re-probe 并 configure（不触发知识库 drop）。
     */
    public synchronized boolean reloadIfConfigurationChanged() {
        if (!embeddingService.hasActiveEmbeddingConfig()) {
            log.info("未设置当前 Embedding 配置，跳过向量库重载检查");
            return false;
        }
        String newMetricType = propertiesService.getProperty(PropertiesService.RETRIEVE_METRIC_TYPE);
        try {
            EmbeddingModel.EmbeddingsResponse response = embeddingService.embed(embeddingService.checkEmbeddingsRequest);
            if (response == null || response.getData().isEmpty()) {
                log.warn("向量库重载检查失败：无法获取 test embedding");
                return false;
            }
            EmbeddingModel.EmbeddingsItem testEmbed = response.getData().getFirst();
            int newDimension = testEmbed.getEmbeddings().length;
            String newCheckEmbeddingHash = HashUtil.getMessageDigest(
                    Arrays.toString(testEmbed.getEmbeddings()).getBytes(), HashUtil.MdAlgorithm.SHA256);
            boolean needReload = false;
            if (!Objects.equals(newCheckEmbeddingHash, embeddingService.getCheckEmbeddingHash())) {
                needReload = true;
            }
            Integer currentDimension = embeddingService.getDimension();
            if (currentDimension != null && currentDimension != newDimension) {
                needReload = true;
            }
            if (!Objects.equals(newMetricType, metricType)) {
                needReload = true;
            }
            if (needReload) {
                log.info("向量库配置变化检测到，重新 probe 并 configure");
                return probeAndConfigure();
            }
            log.info("向量库配置无变化，跳过重载");
            return false;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Hash algorithm not found", e);
        }
    }

    private void sleep(Duration duration) {
        try {
            TimeUnit.MILLISECONDS.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
