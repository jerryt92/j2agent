package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

/**
 * 本轮同步中单文件处理状态。
 */
public enum KnowledgeRepoSyncFileStatus {
    PENDING,
    IN_PROGRESS,
    SYNCED,
    DELETED,
    SKIPPED,
    FAILED
}
