package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

/**
 * 知识库仓库同步结果。
 */
public record KnowledgeRepositorySyncResult(
        String revision,
        String revisionMessage,
        String revisionAuthor,
        Long revisionTime
) {
}
