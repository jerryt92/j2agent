package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

/**
 * 知识库维护任务类型。
 */
public enum KnowledgeRepoMaintenanceTaskType {
    IDLE,
    INITIALIZING,
    PROBING,
    INCREMENTAL_SYNC,
    FULL_REBUILD,
    FAILED
}
