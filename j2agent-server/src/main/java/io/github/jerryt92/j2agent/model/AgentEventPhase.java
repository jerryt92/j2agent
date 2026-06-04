package io.github.jerryt92.j2agent.model;

/**
 * Agent 事件在单轮执行中的阶段标识。
 */
public enum AgentEventPhase {
    /** 本轮或子过程启动。 */
    START,
    /** 流式增量事件（如 token 持续输出）。 */
    DELTA,
    /** 局部修补事件（如结构化内容补丁）。 */
    PATCH,
    /** 本轮正常结束。 */
    COMPLETE,
    /** 本轮异常结束。 */
    ERROR
}
