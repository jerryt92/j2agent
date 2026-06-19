package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

/**
 * 知识库目录同步执行结果。
 */
public record KnowledgeRepoSyncOutcome(boolean succeeded, String message) {

    /**
     * 同步成功。
     */
    public static KnowledgeRepoSyncOutcome ok() {
        return new KnowledgeRepoSyncOutcome(true, null);
    }

    /**
     * 异步任务已接受。
     */
    public static KnowledgeRepoSyncOutcome accepted(String message) {
        return new KnowledgeRepoSyncOutcome(true, message);
    }

    /**
     * 同步失败。
     */
    public static KnowledgeRepoSyncOutcome fail(String message) {
        return new KnowledgeRepoSyncOutcome(false, message);
    }
}
