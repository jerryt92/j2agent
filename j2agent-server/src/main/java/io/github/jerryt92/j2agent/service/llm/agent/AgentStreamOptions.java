package io.github.jerryt92.j2agent.service.llm.agent;

import io.github.jerryt92.j2agent.logging.llm.AgentRunLogSnapshot;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunContext;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link AgentStreamSession#stream(AgentStreamOptions)} 入参。
 */
public record AgentStreamOptions(
        AiAgent aiAgent,
        AgentRunContext agentRunContext,
        AgentRunLogSnapshot runLogSnapshot,
        AgentTurnStateMachine stateMachine,
        StringBuilder streamedContent,
        StringBuilder streamedReasoning,
        Object streamedTextLock,
        Object turnLock,
        AtomicLong streamStartedAtMs,
        AtomicInteger retryNo,
        Runnable onStreamFinally) {
}
