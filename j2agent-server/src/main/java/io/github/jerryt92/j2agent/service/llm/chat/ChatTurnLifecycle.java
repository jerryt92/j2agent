package io.github.jerryt92.j2agent.service.llm.chat;

import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogContext;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogSnapshot;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.model.AgentStateTransition;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.ChatCallback;
import io.github.jerryt92.j2agent.model.ChatResponseDto;
import io.github.jerryt92.j2agent.model.MessageDto;
import io.github.jerryt92.j2agent.service.llm.AgentEventBuilder;
import io.github.jerryt92.j2agent.service.llm.advisor.ReactCompatibleMessageChatMemoryAdvisor;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import io.github.jerryt92.j2agent.service.llm.LlmProviderErrorFormatter;
import io.github.jerryt92.j2agent.service.llm.TurnStepRecorder;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import io.github.jerryt92.j2agent.service.llm.rag.TurnRagSourceRegistry;
import io.github.jerryt92.j2agent.service.llm.reasoning.SpringAiReasoningMetadataAdapter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 单轮聊天 WebSocket 生命周期工具（通用助手与插件 Agent 共用）。
 */
@Slf4j
public final class ChatTurnLifecycle {

    private static final String ANONYMOUS_USER = "anonymous";

    private ChatTurnLifecycle() {
    }

    public static String formatConversationId(String userId, String contextId, String agentId) {
        return ConversationIdCodec.format(userId == null ? ANONYMOUS_USER : userId, contextId, agentId);
    }

    public static String resolveLogUserId(String userId) {
        return StringUtils.isNotBlank(userId) ? userId.trim() : ANONYMOUS_USER;
    }

    public static String extractLatestUserMessage(List<MessageDto> messages) {
        return extractLatestUser(messages).getContent();
    }

    public static MessageDto extractLatestUser(List<MessageDto> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageDto message = messages.get(i);
            if (message.getRole() == MessageDto.RoleEnum.USER) {
                return message;
            }
        }
        throw new IllegalArgumentException("No user message found in request.");
    }

    public static String limitMessageLength(String message, int maxLen) {
        if (message == null) {
            return "";
        }
        if (maxLen > 0 && message.length() > maxLen) {
            log.warn("User message truncated from {} to {}", message.length(), maxLen);
            return message.substring(0, maxLen);
        }
        return message;
    }

    public static void emitAnswerDelta(ChatCallback<AgentUiEventEnvelope> chatChatCallback,
                                       String contextId,
                                       String turnId,
                                       AtomicLong seq,
                                       AgentTurnStateMachine stateMachine,
                                       Object turnLock,
                                       StringBuilder streamedContent,
                                       StringBuilder streamedReasoning,
                                       Object streamedTextLock,
                                       int index,
                                       String answerDelta,
                                       String reasoningDelta) {
        if (ChatTurnCancellationRegistry.isCancelled(turnId)) {
            return;
        }
        if (StringUtils.isNotBlank(answerDelta)
                && StringUtils.isNotBlank(reasoningDelta)
                && answerDelta.equals(reasoningDelta)) {
            reasoningDelta = null;
        }
        if (!StringUtils.isNotBlank(answerDelta) && !StringUtils.isNotBlank(reasoningDelta)) {
            return;
        }
        ChatResponseDto chatResponseDto = buildChatResponseDto(answerDelta, reasoningDelta, index);
        synchronized (streamedTextLock) {
            if (StringUtils.isNotBlank(answerDelta)) {
                streamedContent.append(answerDelta);
            }
            if (StringUtils.isNotBlank(reasoningDelta)) {
                streamedReasoning.append(reasoningDelta);
            }
        }
        synchronized (turnLock) {
            AgentStateTransition transition = null;
            if (StringUtils.isNotBlank(answerDelta)
                    && (stateMachine.getState() == AgentState.THINKING
                    || stateMachine.getState() == AgentState.AGENT_DISPATCHING
                    || stateMachine.getState() == AgentState.CALLING_TOOL
                    || stateMachine.getState() == AgentState.LOAD_SKILL)) {
                transition = stateMachine.transit(AgentState.STREAMING_TEXT, "firstAnswerToken");
            }
            chatChatCallback.responseCall.accept(AgentEventBuilder.build(
                    contextId,
                    turnId,
                    seq.getAndIncrement(),
                    stateMachine.getState(),
                    transition,
                    AgentEventPhase.DELTA,
                    AgentEventType.MESSAGE,
                    chatResponseDto
            ));
        }
    }

    public static void terminateTurnWithFailure(ChatCallback<AgentUiEventEnvelope> callback,
                                                  String contextId,
                                                  String turnId,
                                                  AtomicLong seq,
                                                  AgentTurnStateMachine stateMachine,
                                                  Object turnLock,
                                                  AtomicBoolean terminated,
                                                  Runnable originalCompleteCall,
                                                  String errorCode,
                                                  Throwable cause,
                                                  Runnable beforeEmit,
                                                  Runnable afterTerminal,
                                                  Runnable releaseActiveTurn) {
        if (!terminated.compareAndSet(false, true)) {
            if (releaseActiveTurn != null) {
                releaseActiveTurn.run();
            }
            return;
        }
        try {
            if (beforeEmit != null) {
                beforeEmit.run();
            }
            Consumer<AgentUiEventEnvelope> responseCall = callback.responseCall;
            if (responseCall != null) {
                synchronized (turnLock) {
                    if (stateMachine.getState() != AgentState.FAILED) {
                        responseCall.accept(AgentEventBuilder.buildTurnFailure(
                                contextId, turnId, seq.getAndIncrement(), stateMachine, errorCode, cause));
                    }
                }
            }
            if (afterTerminal != null) {
                afterTerminal.run();
            }
            runOriginalCompleteCall(originalCompleteCall);
        } finally {
            if (releaseActiveTurn != null) {
                releaseActiveTurn.run();
            }
        }
    }

    public static void runOriginalCompleteCall(Runnable originalCompleteCall) {
        if (originalCompleteCall != null) {
            try {
                originalCompleteCall.run();
            } catch (Throwable closeErr) {
                log.warn("WebSocket complete 回调执行异常: {}", closeErr.toString());
            }
        }
    }

    public static String resolveErrorCode(Throwable t) {
        if (t instanceof IllegalArgumentException) {
            String msg = t.getMessage();
            if (StringUtils.isNotBlank(msg)) {
                if (msg.contains("Unsupported agentId")) {
                    return "unsupportedAgent";
                }
                if (msg.contains("does not own")) {
                    return "contextAccessDenied";
                }
                if (msg.contains("No user message")) {
                    return "noUserMessage";
                }
            }
        }
        if (LlmProviderErrorFormatter.isProviderCallFailure(t)) {
            return LlmProviderErrorFormatter.resolveErrorCode(t);
        }
        return "internalError";
    }

    public static void flushTurnTrace(ChatMemory chatMemory,
                                      String conversationId,
                                      String turnId,
                                      TurnStepRecorder turnStepRecorder,
                                      AtomicBoolean turnTraceFlushed,
                                      ChatMemoryMessageCodec chatMemoryMessageCodec) {
        if (chatMemory == null || conversationId == null || turnStepRecorder.isEmpty()) {
            return;
        }
        if (!turnTraceFlushed.compareAndSet(false, true)) {
            return;
        }
        try {
            chatMemory.add(conversationId,
                    List.of(chatMemoryMessageCodec.buildTurnTraceAuditMessage(turnId, turnStepRecorder.getSteps())));
        } catch (Exception e) {
            AgentRunLogSnapshot snapshot = AgentRunLogContext.lookup(conversationId);
            if (snapshot != null) {
                AgentRunLogger.warn(snapshot, AgentRunEventType.ERROR,
                        AgentRunLogger.kv("errorCode", "turnTracePersistFailed",
                                "errorType", e.getClass().getSimpleName()),
                        e.toString());
            }
        }
    }

    public static void logTurnEnd(AgentRunLogSnapshot snapshot,
                                  AtomicLong streamStartedAtMs,
                                  TurnStepRecorder turnStepRecorder,
                                  AgentState status) {
        if (snapshot == null) {
            return;
        }
        long elapsedMs = streamStartedAtMs.get() == 0L
                ? 0L
                : System.currentTimeMillis() - streamStartedAtMs.get();
        AgentRunLogger.info(snapshot, AgentRunEventType.TURN_END,
                AgentRunLogger.kv(
                        "status", status == null ? "" : status.name(),
                        "elapsedMs", elapsedMs,
                        "stepCount", turnStepRecorder == null ? 0 : turnStepRecorder.getSteps().size()),
                "turn finished");
    }

    /**
     * 回合开始时将用户消息写入记忆（通用助手在编排/子智能体阶段前落库，避免刷新后历史为空）。
     */
    public static void persistTurnUserMessage(ChatMemory chatMemory,
                                            String conversationId,
                                            String text,
                                            List<ChatAttachmentDto> attachments) {
        if (chatMemory == null || !StringUtils.isNotBlank(conversationId)) {
            return;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ChatMemory.CONVERSATION_ID, conversationId);
        metadata.put("attachments", attachments == null ? List.of() : attachments);
        metadata.put(ReactCompatibleMessageChatMemoryAdvisor.META_USER_MESSAGE_PRE_PERSISTED, Boolean.TRUE);
        UserMessage userMessage = UserMessage.builder()
                .text(text != null ? text : "")
                .metadata(metadata)
                .build();
        chatMemory.add(conversationId, userMessage);
    }

    public static void persistStreamedAssistant(ChatMemory chatMemory,
                                                 String conversationId,
                                                 StringBuilder streamedContent,
                                                 StringBuilder streamedReasoning,
                                                 Object streamedTextLock,
                                                 AtomicBoolean once,
                                                 boolean addEllipsis) {
        if (!once.compareAndSet(false, true)) {
            return;
        }
        String content;
        String reasoning;
        synchronized (streamedTextLock) {
            content = streamedContent.toString();
            reasoning = streamedReasoning.toString();
        }
        String ragInfosJson = TurnRagSourceRegistry.drainRagInfosJson(conversationId);
        if (content.isEmpty() && reasoning.isEmpty() && StringUtils.isBlank(ragInfosJson)) {
            return;
        }
        chatMemory.add(conversationId,
                List.of(buildStreamedAssistantMessage(content, reasoning, addEllipsis, ragInfosJson)));
    }

    public static AssistantMessage buildStreamedAssistantMessage(String content,
                                                                 String reasoning,
                                                                 boolean addEllipsis,
                                                                 String ragInfosJson) {
        String finalContent = content != null ? content : "";
        if (addEllipsis && StringUtils.isNotBlank(finalContent)) {
            finalContent = finalContent + "...";
        }
        String finalReasoning = reasoning != null ? reasoning : "";
        if (addEllipsis && StringUtils.isNotBlank(finalReasoning)) {
            finalReasoning = finalReasoning + "...";
        }
        AssistantMessage.Builder builder = AssistantMessage.builder().content(finalContent);
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        if (StringUtils.isNotBlank(finalReasoning)) {
            properties.put(SpringAiReasoningMetadataAdapter.UNIFIED_REASONING_KEY, finalReasoning);
        }
        if (StringUtils.isNotBlank(ragInfosJson)) {
            properties.put(ChatMemoryMessageCodec.META_RAG_INFOS, ragInfosJson);
        }
        if (!properties.isEmpty()) {
            builder.properties(properties);
        }
        return builder.build();
    }

    private static ChatResponseDto buildChatResponseDto(String answerDelta, String reasoningDelta, int index) {
        ChatResponseDto chatResponseDto = new ChatResponseDto();
        MessageDto messageDto = new MessageDto();
        messageDto.setRole(MessageDto.RoleEnum.ASSISTANT);
        messageDto.setIndex(index);
        if (StringUtils.isNotBlank(answerDelta)) {
            messageDto.setContent(answerDelta);
        }
        if (StringUtils.isNotBlank(reasoningDelta)) {
            messageDto.setReasoningContent(reasoningDelta);
        }
        chatResponseDto.setMessage(messageDto);
        return chatResponseDto;
    }
}
