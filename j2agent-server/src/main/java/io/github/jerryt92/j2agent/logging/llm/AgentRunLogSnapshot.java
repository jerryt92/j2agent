package io.github.jerryt92.j2agent.logging.llm;

/**
 * 单轮 Agent 运行日志上下文快照。
 */
public record AgentRunLogSnapshot(
        String contextId,
        String turnId,
        String conversationId,
        String userId,
        String agentId) {
}
