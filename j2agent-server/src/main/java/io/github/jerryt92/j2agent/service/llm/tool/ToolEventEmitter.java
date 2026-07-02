package io.github.jerryt92.j2agent.service.llm.tool;

import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.model.AgentStateTransition;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ToolCallEventPayload;
import io.github.jerryt92.j2agent.service.llm.AgentEventBuilder;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.chat.ChatTurnCancellationRegistry;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.Map;

/**
 * 单轮会话内工具与技能加载事件发射器：分别维护 {@link AgentState#CALLING_TOOL} 与 {@link AgentState#LOAD_SKILL} 的并发计数与状态进出。
 * 成功结束时先发 {@link AgentEventPhase#DELTA} 再发 {@link AgentEventPhase#COMPLETE}，载荷均含工具 result，便于只订阅增量的前端。
 */
public class ToolEventEmitter {

    /** 工具返回文本最大长度，超出部分截断以避免 WS 消息过大。 */
    public static final int MAX_TOOL_RESULT_LENGTH = 8192;

    private final String contextId;
    private final String turnId;
    private final AtomicLong seq;
    private final AgentTurnStateMachine stateMachine;
    private final Object turnLock;
    private final Consumer<AgentUiEventEnvelope> sink;
    private final AtomicInteger activeToolCount = new AtomicInteger(0);
    private final AtomicInteger activeSkillLoadCount = new AtomicInteger(0);

    /**
     * @param contextId    会话 ID
     * @param turnId       轮次 ID
     * @param seq          与 ChatService 共享的全局序号
     * @param stateMachine 与 ChatService 共享的状态机实例
     * @param turnLock     与 ChatService 共享的轮次锁，保证事件与文本增量顺序一致
     * @param sink         信封投递回调（通常已在外层加锁）
     */
    public ToolEventEmitter(String contextId,
                            String turnId,
                            AtomicLong seq,
                            AgentTurnStateMachine stateMachine,
                            Object turnLock,
                            Consumer<AgentUiEventEnvelope> sink) {
        this.contextId = contextId;
        this.turnId = turnId;
        this.seq = seq;
        this.stateMachine = stateMachine;
        this.turnLock = turnLock;
        this.sink = sink;
    }

    private boolean skipIfCancelled() {
        return ChatTurnCancellationRegistry.isCancelled(turnId);
    }

    /**
     * 编排服务开始分发：迁移至 {@link AgentState#AGENT_DISPATCHING}。
     */
    public void onAgentDispatchingStart() {
        if (skipIfCancelled()) {
            return;
        }
        synchronized (turnLock) {
            AgentStateTransition transition = stateMachine.transit(AgentState.AGENT_DISPATCHING, "orchestrationStart");
            sink.accept(AgentEventBuilder.build(
                    contextId,
                    turnId,
                    seq.getAndIncrement(),
                    stateMachine.getState(),
                    transition,
                    AgentEventPhase.START,
                    AgentEventType.SYSTEM,
                    Map.of("notice", "agent-dispatching-started")));
        }
    }

    /**
     * 工具即将执行：必要时迁移至 {@link AgentState#CALLING_TOOL} 并发送 START 事件。
     */
    public void onToolStart(String callId, String toolName, String argumentsJson) {
        if (skipIfCancelled()) {
            return;
        }
        ToolCallEventPayload payload = new ToolCallEventPayload()
                .setCallId(callId)
                .setToolName(toolName)
                .setArguments(argumentsJson)
                .setStatus(ToolCallEventPayload.ToolCallStatus.STARTED);
        synchronized (turnLock) {
            AgentStateTransition transition = null;
            if (activeToolCount.getAndIncrement() == 0 && stateMachine.getState() != AgentState.CALLING_TOOL) {
                transition = stateMachine.transit(AgentState.CALLING_TOOL, "toolStart");
            }
            sink.accept(AgentEventBuilder.build(
                    contextId,
                    turnId,
                    seq.getAndIncrement(),
                    stateMachine.getState(),
                    transition,
                    AgentEventPhase.START,
                    AgentEventType.TOOL,
                    payload
            ));
        }
    }

    /**
     * 工具执行成功：发送 COMPLETE，必要时从 CALLING_TOOL 回到 THINKING。
     */
    public void onToolSuccess(String callId, String toolName, String rawResult, long durationMs) {
        if (skipIfCancelled()) {
            return;
        }
        String result = rawResult == null ? "" : rawResult;
        int fullLen = result.length();
        boolean truncated = fullLen > MAX_TOOL_RESULT_LENGTH;
        if (truncated) {
            result = result.substring(0, MAX_TOOL_RESULT_LENGTH);
        }
        ToolCallEventPayload payload = new ToolCallEventPayload()
                .setCallId(callId)
                .setToolName(toolName)
                .setStatus(ToolCallEventPayload.ToolCallStatus.COMPLETED)
                .setResult(result)
                .setTruncated(truncated)
                .setResultLength(fullLen)
                .setDurationMs(durationMs);
        synchronized (turnLock) {
            AgentStateTransition transition = finishToolRound("toolReturned");
            sink.accept(AgentEventBuilder.build(
                    contextId,
                    turnId,
                    seq.getAndIncrement(),
                    stateMachine.getState(),
                    null,
                    AgentEventPhase.DELTA,
                    AgentEventType.TOOL,
                    payload
            ));
            sink.accept(AgentEventBuilder.build(
                    contextId,
                    turnId,
                    seq.getAndIncrement(),
                    stateMachine.getState(),
                    transition,
                    AgentEventPhase.COMPLETE,
                    AgentEventType.TOOL,
                    payload
            ));
        }
    }

    /**
     * 工具执行失败：发送 ERROR，必要时从 CALLING_TOOL 回到 THINKING。
     */
    public void onToolFailure(String callId, String toolName, Throwable error, long durationMs) {
        if (skipIfCancelled()) {
            return;
        }
        String msg = error == null ? null : error.getMessage();
        ToolCallEventPayload payload = new ToolCallEventPayload()
                .setCallId(callId)
                .setToolName(toolName)
                .setStatus(ToolCallEventPayload.ToolCallStatus.FAILED)
                .setErrorMessage(msg)
                .setDurationMs(durationMs);
        synchronized (turnLock) {
            AgentStateTransition transition = finishToolRound("toolFailed");
            sink.accept(AgentEventBuilder.build(
                    contextId,
                    turnId,
                    seq.getAndIncrement(),
                    stateMachine.getState(),
                    transition,
                    AgentEventPhase.ERROR,
                    AgentEventType.TOOL,
                    payload
            ));
        }
    }

    /**
     * read_skill 即将执行：必要时迁移至 {@link AgentState#LOAD_SKILL} 并发送 TOOL START（与工具调用事件类型一致，便于前端复用）。
     */
    public void onSkillLoadStart(String callId, String toolName, String argumentsJson, String skillName) {
        if (skipIfCancelled()) {
            return;
        }
        ToolCallEventPayload payload = new ToolCallEventPayload()
                .setCallId(callId)
                .setToolName(toolName)
                .setArguments(argumentsJson)
                .setSkillName(skillName)
                .setStatus(ToolCallEventPayload.ToolCallStatus.STARTED);
        synchronized (turnLock) {
            AgentStateTransition transition = null;
            if (activeSkillLoadCount.getAndIncrement() == 0 && stateMachine.getState() != AgentState.LOAD_SKILL) {
                transition = stateMachine.transit(AgentState.LOAD_SKILL, "skillLoadStart");
            }
            sink.accept(AgentEventBuilder.build(
                    contextId,
                    turnId,
                    seq.getAndIncrement(),
                    stateMachine.getState(),
                    transition,
                    AgentEventPhase.START,
                    AgentEventType.TOOL,
                    payload
            ));
        }
    }

    /**
     * read_skill 执行成功：发送 DELTA/COMPLETE，必要时从 LOAD_SKILL 回到 THINKING。
     */
    public void onSkillLoadSuccess(String callId, String toolName, String skillName, String rawResult, long durationMs) {
        if (skipIfCancelled()) {
            return;
        }
        String result = rawResult == null ? "" : rawResult;
        int fullLen = result.length();
        boolean truncated = fullLen > MAX_TOOL_RESULT_LENGTH;
        if (truncated) {
            result = result.substring(0, MAX_TOOL_RESULT_LENGTH);
        }
        ToolCallEventPayload payload = new ToolCallEventPayload()
                .setCallId(callId)
                .setToolName(toolName)
                .setSkillName(skillName)
                .setStatus(ToolCallEventPayload.ToolCallStatus.COMPLETED)
                .setResult(result)
                .setTruncated(truncated)
                .setResultLength(fullLen)
                .setDurationMs(durationMs);
        synchronized (turnLock) {
            AgentStateTransition transition = finishSkillLoadRound("skillLoadReturned");
            sink.accept(AgentEventBuilder.build(
                    contextId,
                    turnId,
                    seq.getAndIncrement(),
                    stateMachine.getState(),
                    null,
                    AgentEventPhase.DELTA,
                    AgentEventType.TOOL,
                    payload
            ));
            sink.accept(AgentEventBuilder.build(
                    contextId,
                    turnId,
                    seq.getAndIncrement(),
                    stateMachine.getState(),
                    transition,
                    AgentEventPhase.COMPLETE,
                    AgentEventType.TOOL,
                    payload
            ));
        }
    }

    /**
     * read_skill 执行失败：发送 ERROR，必要时从 LOAD_SKILL 回到 THINKING。
     */
    public void onSkillLoadFailure(String callId, String toolName, String skillName, Throwable error, long durationMs) {
        if (skipIfCancelled()) {
            return;
        }
        String msg = error == null ? null : error.getMessage();
        ToolCallEventPayload payload = new ToolCallEventPayload()
                .setCallId(callId)
                .setToolName(toolName)
                .setSkillName(skillName)
                .setStatus(ToolCallEventPayload.ToolCallStatus.FAILED)
                .setErrorMessage(msg)
                .setDurationMs(durationMs);
        synchronized (turnLock) {
            AgentStateTransition transition = finishSkillLoadRound("skillLoadFailed");
            sink.accept(AgentEventBuilder.build(
                    contextId,
                    turnId,
                    seq.getAndIncrement(),
                    stateMachine.getState(),
                    transition,
                    AgentEventPhase.ERROR,
                    AgentEventType.TOOL,
                    payload
            ));
        }
    }

    /**
     * 最后一个并发工具结束时从 CALLING_TOOL 迁回 THINKING。
     */
    private AgentStateTransition finishToolRound(String reason) {
        AgentStateTransition transition = null;
        if (activeToolCount.decrementAndGet() == 0 && stateMachine.getState() == AgentState.CALLING_TOOL) {
            transition = stateMachine.transit(AgentState.THINKING, reason);
        }
        return transition;
    }

    /**
     * 最后一个并发 read_skill 结束时从 LOAD_SKILL 迁回 THINKING。
     */
    private AgentStateTransition finishSkillLoadRound(String reason) {
        AgentStateTransition transition = null;
        if (activeSkillLoadCount.decrementAndGet() == 0 && stateMachine.getState() == AgentState.LOAD_SKILL) {
            transition = stateMachine.transit(AgentState.THINKING, reason);
        }
        return transition;
    }
}
