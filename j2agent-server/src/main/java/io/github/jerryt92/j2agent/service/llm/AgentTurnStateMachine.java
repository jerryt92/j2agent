package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.model.AgentStateTransition;

/**
 * 轻量级单轮状态机：负责维护当前状态并产出迁移记录。
 */
public class AgentTurnStateMachine {
    private AgentState state = AgentState.IDLE;

    public AgentState getState() {
        return state;
    }

    /**
     * 执行状态迁移，并返回迁移快照供事件信封携带。
     */
    public AgentStateTransition transit(AgentState nextState, String reason) {
        AgentStateTransition transition = new AgentStateTransition()
                .setFrom(state)
                .setTo(nextState)
                .setReason(reason);
        state = nextState;
        return transition;
    }
}
