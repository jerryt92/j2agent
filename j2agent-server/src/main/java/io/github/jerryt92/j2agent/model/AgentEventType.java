package io.github.jerryt92.j2agent.model;

/**
 * Agent-UI 事件类型，用于指示 payload 的业务语义。
 */
public enum AgentEventType {
    /** 对话消息事件（文本/系统消息载体）。 */
    MESSAGE,
    /** 工具调用相关事件。 */
    TOOL,
    /** 过程进度事件。 */
    PROGRESS,
    /** 提示通知事件。 */
    NOTICE,
    /** 系统生命周期事件。 */
    SYSTEM
}
