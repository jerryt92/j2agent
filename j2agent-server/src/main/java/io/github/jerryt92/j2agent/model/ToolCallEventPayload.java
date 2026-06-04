package io.github.jerryt92.j2agent.model;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Agent-UI 工具调用生命周期事件的载荷（对应 {@link AgentEventType#TOOL}）。
 */
@Data
@Accessors(chain = true)
public class ToolCallEventPayload {

    /**
     * 单次工具执行的唯一标识（一轮内可多条）。
     */
    private String callId;

    /**
     * 工具名称，对应 {@link org.springframework.ai.tool.definition.ToolDefinition#name()}。
     */
    private String toolName;

    /**
     * 模型传入的工具参数 JSON 原文（即 ToolCallback 收到的 input 字符串）。
     */
    private String arguments;

    /**
     * read_skill 场景下解析出的技能名，供 UI 轨迹展示；其它工具可为空。
     */
    private String skillName;

    /**
     * 当前工具事件所处阶段。
     */
    private ToolCallStatus status;

    /**
     * 工具返回文本（成功且 {@link #truncated} 为 true 时为截断后的内容）。
     */
    private String result;

    /**
     * 返回内容是否因超长被截断。
     */
    private Boolean truncated;

    /**
     * 原始返回字符串长度（截断前）。
     */
    private Integer resultLength;

    /**
     * 失败时的错误描述。
     */
    private String errorMessage;

    /**
     * 本次调用耗时（毫秒）。
     */
    private Long durationMs;

    /**
     * 工具调用状态枚举，与 {@link AgentEventPhase} 配合表达生命周期。
     */
    public enum ToolCallStatus {
        /** 即将执行底层工具。 */
        STARTED,
        /** 执行成功并已拿到返回值。 */
        COMPLETED,
        /** 执行过程中抛出异常。 */
        FAILED
    }
}
