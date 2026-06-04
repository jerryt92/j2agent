package io.github.jerryt92.j2agent.model;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AgentStateTransition {
    /** 迁移前状态。 */
    private AgentState from;
    /** 迁移后状态。 */
    private AgentState to;
    /** 状态迁移原因，便于前端展示与调试。 */
    private String reason;
}
