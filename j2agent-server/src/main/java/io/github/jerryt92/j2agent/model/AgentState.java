package io.github.jerryt92.j2agent.model;

/**
 * Agent 单轮对话状态机的核心状态定义。
 */
public enum AgentState {
    /** 初始状态，尚未开始本轮执行。 */
    IDLE,
    /** 通用助手被动子智能体意图召回中（调度 LLM）。 */
    AGENT_SCHEDULING,
    /** 已接收用户输入，模型正在推理。 */
    THINKING,
    /** 正在流式输出文本内容。 */
    STREAMING_TEXT,
    /** 正在执行工具调用。 */
    CALLING_TOOL,
    /** 正在加载技能（read_skill），与 {@link #CALLING_TOOL} 平行，独立计数与状态迁移。 */
    LOAD_SKILL,
    /** 本轮成功结束。 */
    COMPLETED,
    /** 本轮异常结束。 */
    FAILED,
    /** 本轮被用户中断或连接关闭导致取消。 */
    CANCELLED
}
