package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.model.AgentStateTransition;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;

import java.util.HashMap;
import java.util.Map;

import io.github.jerryt92.j2agent.utils.UUIDv7Utils;

/**
 * 统一构建 Agent-UI 事件信封，避免业务代码重复拼装字段。
 */
public final class AgentEventBuilder {
    private AgentEventBuilder() {
    }

    /**
     * 构建整轮失败（{@link AgentState#FAILED}）的 SYSTEM/ERROR 载荷。
     *
     * @param errorCode    与 transition.reason 一致，供前端 i18n
     * @param errorMessage 展示文案；可为 null
     */
    public static Map<String, Object> buildErrorPayload(String errorCode, String errorMessage) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("error", true);
        payload.put("errorCode", errorCode);
        payload.put("errorMessage", errorMessage);
        return payload;
    }

    /**
     * 从异常提取展示文案构建失败载荷（含模型提供商 HTTP status / 响应体摘要）。
     */
    public static Map<String, Object> buildErrorPayload(String errorCode, Throwable t) {
        Map<String, Object> payload = buildErrorPayload(errorCode, LlmProviderErrorFormatter.formatForDisplay(t));
        if (t != null) {
            payload.put("providerErrorDetail", LlmProviderErrorFormatter.formatForLog(t));
        }
        return payload;
    }

    /**
     * 构建整轮失败终态事件：状态机迁移至 FAILED，phase=ERROR，eventType=SYSTEM。
     * 调用方在共享 turnLock 场景下应在外层 synchronized。
     */
    public static AgentUiEventEnvelope buildTurnFailure(String contextId,
                                                          String turnId,
                                                          long seq,
                                                          AgentTurnStateMachine stateMachine,
                                                          String errorCode,
                                                          Throwable cause) {
        AgentStateTransition transition = stateMachine.transit(AgentState.FAILED, errorCode);
        return build(
                contextId,
                turnId,
                seq,
                stateMachine.getState(),
                transition,
                AgentEventPhase.ERROR,
                AgentEventType.SYSTEM,
                buildErrorPayload(errorCode, cause)
        );
    }

    public static AgentUiEventEnvelope build(String contextId,
                                             String turnId,
                                             long seq,
                                             AgentState state,
                                             AgentStateTransition transition,
                                             AgentEventPhase phase,
                                             AgentEventType eventType,
                                             Object payload) {
        return new AgentUiEventEnvelope()
                .setEventId(UUIDv7Utils.randomUUIDv7())
                .setContextId(contextId)
                .setTurnId(turnId)
                .setSeq(seq)
                .setState(state)
                .setTransition(transition)
                .setPhase(phase)
                .setEventType(eventType)
                .setPayload(payload)
                .setTs(System.currentTimeMillis());
    }
}
