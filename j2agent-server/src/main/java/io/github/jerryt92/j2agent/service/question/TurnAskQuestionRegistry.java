package io.github.jerryt92.j2agent.service.question;

import com.alibaba.fastjson2.JSON;
import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.AskQuestionDto;
import io.github.jerryt92.j2agent.model.ChatResponseDto;
import io.github.jerryt92.j2agent.model.MessageDto;
import io.github.jerryt92.j2agent.service.llm.AgentEventBuilder;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import org.springframework.util.StringUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 单轮流式对话内用户提问收集与 WebSocket PATCH 推送。
 */
public final class TurnAskQuestionRegistry {

    private static final ConcurrentHashMap<String, Holder> BY_CONVERSATION = new ConcurrentHashMap<>();

    private TurnAskQuestionRegistry() {
    }

    /**
     * 绑定本轮会话的提问推送上下文。
     */
    public static void bind(String conversationId,
                            Consumer<AgentUiEventEnvelope> sink,
                            Object turnLock,
                            String contextId,
                            String turnId,
                            AtomicLong seq,
                            AgentTurnStateMachine stateMachine,
                            int assistantMessageIndex) {
        if (!StringUtils.hasText(conversationId)) {
            return;
        }
        Holder holder = new Holder();
        holder.sink = sink;
        holder.turnLock = turnLock;
        holder.contextId = contextId;
        holder.turnId = turnId;
        holder.seq = seq;
        holder.stateMachine = stateMachine;
        holder.assistantMessageIndex = assistantMessageIndex;
        BY_CONVERSATION.put(conversationId, holder);
    }

    /**
     * 子智能体运行时 conversationId 与父会话共享同一 Holder。
     */
    public static void shareHolder(String runtimeConversationId, String persistConversationId) {
        if (!StringUtils.hasText(runtimeConversationId) || !StringUtils.hasText(persistConversationId)) {
            return;
        }
        Holder holder = BY_CONVERSATION.get(persistConversationId);
        if (holder != null) {
            BY_CONVERSATION.put(runtimeConversationId, holder);
        }
    }

    /**
     * 解除子智能体运行时 conversationId 的共享映射。
     */
    public static void unshareHolder(String runtimeConversationId) {
        if (StringUtils.hasText(runtimeConversationId)) {
            BY_CONVERSATION.remove(runtimeConversationId);
        }
    }

    /**
     * 发布提问并向前端推送 PATCH。
     */
    public static void publishQuestion(String conversationId, AskQuestionDto question) {
        if (!StringUtils.hasText(conversationId) || question == null) {
            return;
        }
        Holder holder = BY_CONVERSATION.get(conversationId);
        if (holder == null) {
            return;
        }
        holder.question = question;
        pushPatch(holder);
    }

    /**
     * 取出本轮提问 JSON，供记忆持久化；不清除 Holder。
     */
    public static String drainQuestionJson(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return null;
        }
        Holder holder = BY_CONVERSATION.get(conversationId);
        if (holder == null || holder.question == null) {
            return null;
        }
        return JSON.toJSONString(holder.question);
    }

    /**
     * 清除本轮提问绑定。
     */
    public static void clear(String conversationId) {
        if (StringUtils.hasText(conversationId)) {
            BY_CONVERSATION.remove(conversationId);
        }
    }

    private static void pushPatch(Holder holder) {
        if (holder.sink == null) {
            return;
        }
        ChatResponseDto payload = new ChatResponseDto();
        MessageDto message = new MessageDto();
        message.setRole(MessageDto.RoleEnum.ASSISTANT);
        message.setIndex(holder.assistantMessageIndex);
        message.setPendingQuestion(holder.question);
        payload.setMessage(message);
        synchronized (holder.turnLock) {
            holder.sink.accept(AgentEventBuilder.build(
                    holder.contextId,
                    holder.turnId,
                    holder.seq.getAndIncrement(),
                    holder.stateMachine.getState(),
                    null,
                    AgentEventPhase.PATCH,
                    AgentEventType.MESSAGE,
                    payload
            ));
        }
    }

    private static final class Holder {
        Consumer<AgentUiEventEnvelope> sink;
        Object turnLock;
        String contextId;
        String turnId;
        AtomicLong seq;
        AgentTurnStateMachine stateMachine;
        int assistantMessageIndex;
        AskQuestionDto question;
    }
}
