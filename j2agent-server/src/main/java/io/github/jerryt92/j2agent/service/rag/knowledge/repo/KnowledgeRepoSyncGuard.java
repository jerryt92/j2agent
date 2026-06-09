package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

/**
 * 同步执行守卫：协调器传入 generation / interrupt 检查。
 */
@FunctionalInterface
public interface KnowledgeRepoSyncGuard {

    /**
     * @return 当前同步是否应继续
     */
    boolean shouldContinue();
}
