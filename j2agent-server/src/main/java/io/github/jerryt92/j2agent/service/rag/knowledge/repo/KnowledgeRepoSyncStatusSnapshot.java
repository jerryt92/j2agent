package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import java.util.List;

/**
 * 知识库同步状态快照（协调器 + 文件进度）。
 */
public record KnowledgeRepoSyncStatusSnapshot(
        KnowledgeRepoMaintenanceTaskType taskType,
        boolean maintenanceActive,
        boolean fullRebuildRunning,
        boolean exclusiveSyncActive,
        String lastFailureMessage,
        KnowledgeRepoSyncPhase phase,
        int totalCount,
        int processedCount,
        String currentFilePath,
        List<KnowledgeRepoSyncProgressTracker.FileProgress> files
) {
}
