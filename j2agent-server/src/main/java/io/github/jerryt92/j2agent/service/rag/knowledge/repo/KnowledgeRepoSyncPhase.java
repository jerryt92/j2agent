package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

/**
 * 知识库同步进度阶段。
 */
public enum KnowledgeRepoSyncPhase {
    IDLE,
    PREPARING,
    SCANNING,
    DELETING,
    UPSERTING,
    COMPLETED
}
