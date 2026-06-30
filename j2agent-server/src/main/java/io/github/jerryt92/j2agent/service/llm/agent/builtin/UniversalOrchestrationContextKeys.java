package io.github.jerryt92.j2agent.service.llm.agent.builtin;

/**
 * 通用助手编排 Hook 在 {@link com.alibaba.cloud.ai.graph.RunnableConfig#context()} 与
 * turn 级 {@link UniversalOrchestrationRunHolder} 中使用的键名。
 */
public final class UniversalOrchestrationContextKeys {

    private UniversalOrchestrationContextKeys() {
    }

    /** 本回合编排已执行完毕，防止 beforeAgent 重入。 */
    public static final String ORCHESTRATION_DONE =
            UniversalOrchestrationContextKeys.class.getName() + ".orchestrationDone";

    /** 开放召回无候选，走主智能体 ReAct。 */
    public static final String ORCHESTRATION_SKIPPED =
            UniversalOrchestrationContextKeys.class.getName() + ".orchestrationSkipped";

    /** 本回合已由子智能体流式输出终答，主 ReAct 应短路。 */
    public static final String ORCHESTRATION_DELIVERED =
            UniversalOrchestrationContextKeys.class.getName() + ".orchestrationDelivered";
}
