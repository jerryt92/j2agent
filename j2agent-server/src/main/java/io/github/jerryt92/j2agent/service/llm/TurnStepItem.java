package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.model.AgentState;

/**
 * 单轮 Agent 状态轨迹中的一个步骤，与前端 {@code TurnStepItem} 及 OpenAPI {@code TurnStepDto} 对齐。
 */
public record TurnStepItem(AgentState state, String toolName, Long ts) {

    public TurnStepItem(AgentState state) {
        this(state, null, null);
    }

    public TurnStepItem(AgentState state, String toolName) {
        this(state, toolName, null);
    }
}
