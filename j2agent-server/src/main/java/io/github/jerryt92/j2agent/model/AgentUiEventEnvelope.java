package io.github.jerryt92.j2agent.model;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AgentUiEventEnvelope {
    /** 事件唯一 ID，用于去重与追踪。 */
    private String eventId;
    /** 对话上下文 ID。 */
    private String contextId;
    /** 单轮执行 ID。 */
    private String turnId;
    /** 单轮内事件序号，保证前端有序消费。 */
    private Long seq;
    /** 当前事件对应的 Agent 状态。 */
    private AgentState state;
    /** 若发生状态迁移，记录迁移详情。 */
    private AgentStateTransition transition;
    /** 事件阶段（启动/增量/完成/异常）。 */
    private AgentEventPhase phase;
    /** 事件类型（消息/工具/确认等）。 */
    private AgentEventType eventType;
    /** 事件载荷，按 eventType 解释。 */
    private Object payload;
    /** 服务端事件时间戳。 */
    private Long ts;
}
