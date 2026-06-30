package io.github.jerryt92.j2agent.service.llm.agent.core;

import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.service.llm.tool.ToolEventEmitter;

import java.util.List;

/**
 * 单轮 Agent 运行上下文，集中传递路由、记忆和事件桥接所需参数。
 */
public record AgentRunContext(
        String text,
        String contextId,
        String userId,
        String turnId,
        String conversationId,
        String agentId,
        List<ChatAttachmentDto> attachments,
        ToolEventEmitter toolEventEmitter,
        boolean subAgentCallRun,
        boolean userMessagePrePersisted) {
}
