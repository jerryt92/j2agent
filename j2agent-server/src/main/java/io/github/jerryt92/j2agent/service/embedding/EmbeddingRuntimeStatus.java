package io.github.jerryt92.j2agent.service.embedding;

/**
 * Embedding 运行时探测状态，供管理端展示。
 */
public record EmbeddingRuntimeStatus(
        boolean ready,
        Integer dimension,
        String checkEmbeddingHash,
        String modelName,
        String providerType,
        Long lastProbeTime,
        String probeError,
        boolean fullRebuildRunning,
        Integer embeddingBatchSize
) {
}
