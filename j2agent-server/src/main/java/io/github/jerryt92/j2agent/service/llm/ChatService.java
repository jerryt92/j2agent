package io.github.jerryt92.j2agent.service.llm;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import io.github.jerryt92.j2agent.config.ChatInputProperties;
import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.model.AgentStateTransition;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatCallback;
import io.github.jerryt92.j2agent.model.ChatRequestDto;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import org.springframework.beans.factory.ObjectProvider;
import io.github.jerryt92.j2agent.model.ChatResponseDto;
import io.github.jerryt92.j2agent.model.MessageDto;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunContext;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import io.github.jerryt92.j2agent.service.llm.reasoning.AssistantMessageReasoningExtractor;
import io.github.jerryt92.j2agent.service.llm.reasoning.ReasoningSnapshotTracker;
import io.github.jerryt92.j2agent.service.llm.reasoning.SpringAiReasoningMetadataAdapter;
import io.github.jerryt92.j2agent.service.llm.reasoning.ThinkingStreamSplitter;
import io.github.jerryt92.j2agent.service.llm.tool.ToolEventEmitter;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Slf4j
@Service
public class ChatService {
    private static final String ANONYMOUS_USER = "anonymous";
    private final ChatContextService chatContextService;
    private final ChatInputProperties chatInputProperties;
    static Map<String, ChatCallback<AgentUiEventEnvelope>> contextChatCallbackMap = new HashMap<>();
    private final AgentRouter agentRouter;
    /**
     * 回合正常结束后生成输入框上方「建议追问」列表。
     */
    private final FollowUpSuggestionService followUpSuggestionService;
    private final ChatMemoryMessageCodec chatMemoryMessageCodec;
    private final ObjectProvider<io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentService>
            chatAttachmentServiceProvider;

    public ChatService(ChatContextService chatContextService,
                       AgentRouter agentRouter,
                       FollowUpSuggestionService followUpSuggestionService,
                       ChatInputProperties chatInputProperties,
                       ChatMemoryMessageCodec chatMemoryMessageCodec,
                       ObjectProvider<io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentService>
                               chatAttachmentServiceProvider) {
        this.chatContextService = chatContextService;
        this.agentRouter = agentRouter;
        this.followUpSuggestionService = followUpSuggestionService;
        this.chatInputProperties = chatInputProperties;
        this.chatMemoryMessageCodec = chatMemoryMessageCodec;
        this.chatAttachmentServiceProvider = chatAttachmentServiceProvider;
    }

    public void handleChat(ChatCallback<AgentUiEventEnvelope> chatChatCallback, ChatRequestDto request, String userId, String agentId) {
        String turnId = UUIDv7Utils.randomUUIDv7();
        AtomicLong seq = new AtomicLong(0L);
        AgentTurnStateMachine stateMachine = new AgentTurnStateMachine();
        Object turnLock = new Object();
        AtomicBoolean terminated = new AtomicBoolean(false);
        TurnStepRecorder turnStepRecorder = new TurnStepRecorder();
        AtomicBoolean turnTraceFlushed = new AtomicBoolean(false);
        Runnable originalCompleteCall = chatChatCallback.completeCall;
        String contextId = null;
        final AtomicReference<String> conversationIdRef = new AtomicReference<>(null);
        final AtomicReference<ChatMemory> agentChatMemoryRef = new AtomicReference<>(null);
        Consumer<AgentUiEventEnvelope> originalResponseCall = chatChatCallback.responseCall;
        Consumer<AgentUiEventEnvelope> recordingResponseCall = envelope -> {
            turnStepRecorder.record(envelope);
            if (originalResponseCall != null) {
                originalResponseCall.accept(envelope);
            }
        };
        chatChatCallback.responseCall = recordingResponseCall;
        Runnable flushTurnTrace = () -> flushTurnTrace(
                agentChatMemoryRef.get(),
                conversationIdRef.get(),
                turnId,
                turnStepRecorder,
                turnTraceFlushed);
        try {
            contextId = request.getContextId();
            if (contextId == null) {
                contextId = UUIDv7Utils.randomUUIDv7();
            } else {
                if (userId != null && !chatContextService.userOwnsContext(contextId, userId)) {
                    throw new IllegalArgumentException("Current user does not own the contextId.");
                }
            }
            contextChatCallbackMap.put(contextId, chatChatCallback);
            if (CollectionUtils.isEmpty(request.getMessages())) {
                terminateTurnWithFailure(chatChatCallback, contextId, turnId, seq, stateMachine, turnLock,
                        terminated, originalCompleteCall, "emptyMessages", null, null, flushTurnTrace);
                return;
            }
            AiAgent aiAgentForConversation = agentRouter.route(agentId);
            final String turnConversationId = buildConversationId(userId, contextId, aiAgentForConversation.getAgentId());
            conversationIdRef.set(turnConversationId);
            String finalContextId = contextId;
            int index = request.getMessages().size() - 1;
            StringBuilder streamedContent = new StringBuilder();
            StringBuilder streamedReasoning = new StringBuilder();
            Object streamedTextLock = new Object();
            ReasoningSnapshotTracker reasoningTracker = new ReasoningSnapshotTracker();
            ThinkingStreamSplitter thinkingStreamSplitter = new ThinkingStreamSplitter();
            /** 中断/错误时仅补偿落库未完成 assistant 一次，避免与 Advisor 正常 after() 重复 */
            AtomicBoolean interruptAssistantFlushed = new AtomicBoolean(false);
            ToolEventEmitter toolEventEmitter = new ToolEventEmitter(
                    finalContextId,
                    turnId,
                    seq,
                    stateMachine,
                    turnLock,
                    recordingResponseCall);
            String latestUserMessage = extractLatestUserMessage(request.getMessages());
            String limitedUserMessage = limitMessageLength(latestUserMessage);
            MessageDto latestUser = extractLatestUser(request.getMessages());
            List<ChatAttachmentDto> attachments = List.of();
            if (!CollectionUtils.isEmpty(latestUser.getAttachments())) {
                var attachmentService = chatAttachmentServiceProvider.getIfAvailable();
                if (attachmentService == null) {
                    throw new IllegalStateException("Object storage is required for image input.");
                }
                attachments = attachmentService.validateAndReference(
                        latestUser.getAttachments(),
                        finalContextId,
                        aiAgentForConversation.getAgentId(),
                        latestUser.getIndex() == null ? index : latestUser.getIndex(),
                        userId);
            }
            if (StringUtils.isBlank(limitedUserMessage) && attachments.isEmpty()) {
                throw new IllegalArgumentException("User message and attachments must not both be empty.");
            }
            final List<ChatAttachmentDto> finalAttachments = attachments;
            AgentRunContext agentRunContext = new AgentRunContext(
                    limitedUserMessage,
                    finalContextId,
                    userId,
                    turnId,
                    turnConversationId,
                    finalAttachments,
                    toolEventEmitter);
            // 会话记忆主要由 ChatClient Advisor 写入；关闭/异常时补偿未写完的流式 assistant
            AiAgent aiAgent = aiAgentForConversation;
            AgentThinkingOverride effectiveThinking = ThinkingOverrideResolver.resolve(request, aiAgent);
            ThinkingOverrideRegistry.bind(turnConversationId, effectiveThinking);
            AtomicBoolean thinkingOverrideUnbound = new AtomicBoolean(false);
            Runnable unbindThinkingOverride = () -> {
                if (thinkingOverrideUnbound.compareAndSet(false, true)) {
                    ThinkingOverrideRegistry.unbind(turnConversationId);
                }
            };
            agentChatMemoryRef.set(aiAgent.getChatMemory());
            final ChatMemory agentChatMemory = agentChatMemoryRef.get();
            AtomicLong streamStartedAtMs = new AtomicLong(0L);
            chatChatCallback.completeCall = () -> {
                if (!terminated.compareAndSet(false, true)) {
                    return;
                }
                // 建议追问依赖二次写 WS；任一步失败也必须收尾关连接，否则影响下一轮对话。
                try {
                    boolean emittedCompleted = false;
                    synchronized (turnLock) {
                        if (stateMachine.getState() != AgentState.COMPLETED
                                && stateMachine.getState() != AgentState.FAILED
                                && stateMachine.getState() != AgentState.CANCELLED) {
                            AgentStateTransition toCompleted = stateMachine.transit(AgentState.COMPLETED, "streamFinished");
                            chatChatCallback.responseCall.accept(AgentEventBuilder.build(
                                    finalContextId,
                                    turnId,
                                    seq.getAndIncrement(),
                                    stateMachine.getState(),
                                    toCompleted,
                                    AgentEventPhase.COMPLETE,
                                    AgentEventType.SYSTEM,
                                    Map.of("notice", "completed")
                            ));
                            emittedCompleted = true;
                        }
                    }
                    if (emittedCompleted) {
                        try {
                            String assistantSnapshot;
                            synchronized (streamedTextLock) {
                                assistantSnapshot = streamedContent.toString();
                            }
                            List<String> suggestions = followUpSuggestionService.suggest(limitedUserMessage, assistantSnapshot);
                            if (CollectionUtils.isNotEmpty(suggestions)) {
                                Map<String, Object> noticePayload = new HashMap<>();
                                noticePayload.put("notice", "suggested-follow-ups");
                                noticePayload.put("items", suggestions);
                                synchronized (turnLock) {
                                    chatChatCallback.responseCall.accept(AgentEventBuilder.build(
                                            finalContextId,
                                            turnId,
                                            seq.getAndIncrement(),
                                            AgentState.COMPLETED,
                                            null,
                                            AgentEventPhase.COMPLETE,
                                            AgentEventType.NOTICE,
                                            noticePayload
                                    ));
                                }
                            }
                        } catch (Throwable suggestOrNoticeErr) {
                            log.warn("建议追问生成或下发失败（已忽略，不影响会话关闭）: {}", suggestOrNoticeErr.toString());
                        }
                    }
                } finally {
                    flushTurnTrace.run();
                    runOriginalCompleteCall(originalCompleteCall);
                }
            };
            chatChatCallback.errorCall = t -> {
                int bufferedLen;
                AgentState currentState;
                synchronized (streamedTextLock) {
                    bufferedLen = streamedContent.length();
                }
                synchronized (turnLock) {
                    currentState = stateMachine.getState();
                }
                log.error("Agent stream failed, contextId={}, turnId={}, detail={}",
                        finalContextId,
                        turnId,
                        "elapsedMs=" + (streamStartedAtMs.get() == 0L ? "unknown" : String.valueOf(System.currentTimeMillis() - streamStartedAtMs.get()))
                                + ", state=" + currentState
                                + ", bufferedAssistantTextLen=" + bufferedLen
                                + System.lineSeparator()
                                + LlmProviderErrorFormatter.formatForLog(t),
                        t);
                terminateTurnWithFailure(
                        chatChatCallback,
                        finalContextId,
                        turnId,
                        seq,
                        stateMachine,
                        turnLock,
                        terminated,
                        originalCompleteCall,
                        LlmProviderErrorFormatter.resolveErrorCode(t),
                        t,
                        () -> {
                            unbindThinkingOverride.run();
                            flushPartialAssistantOnInterrupt(agentChatMemory, turnConversationId, streamedContent,
                                    streamedReasoning, streamedTextLock, interruptAssistantFlushed, true);
                        },
                        flushTurnTrace);
            };
            // 流式接收
            synchronized (turnLock) {
                AgentStateTransition toThinking = stateMachine.transit(AgentState.THINKING, "userMessageAccepted");
                chatChatCallback.responseCall.accept(AgentEventBuilder.build(
                        finalContextId,
                        turnId,
                        seq.getAndIncrement(),
                        stateMachine.getState(),
                        toThinking,
                        AgentEventPhase.START,
                        AgentEventType.SYSTEM,
                        Map.of("notice", "turn-started")
                ));
            }
            AtomicInteger retryNo = new AtomicInteger(0);
            Disposable disposable = Flux.defer(() -> {
                        streamStartedAtMs.set(System.currentTimeMillis());
                        try {
                            return aiAgent.stream(agentRunContext)
                                    .doFinally(signalType -> unbindThinkingOverride.run());
                        } catch (GraphRunnerException e) {
                            unbindThinkingOverride.run();
                            throw new RuntimeException(e);
                        }
                    })
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                            .maxBackoff(Duration.ofSeconds(3))
                            .filter(t -> isConnectionResetByPeer(t)
                                    && isStillThinkingAndEmpty(streamedContent, streamedReasoning, stateMachine,
                                    streamedTextLock, turnLock))
                            .doBeforeRetry(rs -> {
                                int bufferedLen;
                                AgentState currentState;
                                synchronized (streamedTextLock) {
                                    bufferedLen = streamedContent.length();
                                }
                                synchronized (turnLock) {
                                    currentState = stateMachine.getState();
                                }
                                int nextRetryNo = retryNo.incrementAndGet();
                                log.warn("LLM stream attempt failed, willRetry. nextRetryNo={}, contextId={}, turnId={}, state={}, elapsedMs={}, bufferedAssistantTextLen={}\n{}",
                                        nextRetryNo,
                                        finalContextId,
                                        turnId,
                                        currentState,
                                        System.currentTimeMillis() - streamStartedAtMs.get(),
                                        bufferedLen,
                                        LlmProviderErrorFormatter.formatForLog(rs.failure()));
                            })
                    )
                    .subscribe(nodeOutput -> {
                        StreamingTextParts parts = extractStreamingParts(
                                nodeOutput, reasoningTracker, thinkingStreamSplitter);
                        if (parts == null) {
                            return;
                        }
                        String answerDelta = parts.answerDelta();
                        String reasoningDelta = parts.reasoningDelta();
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
                                    || stateMachine.getState() == AgentState.CALLING_TOOL
                                    || stateMachine.getState() == AgentState.LOAD_SKILL)) {
                                transition = stateMachine.transit(AgentState.STREAMING_TEXT, "firstAnswerToken");
                            }
                            chatChatCallback.responseCall.accept(AgentEventBuilder.build(
                                    finalContextId,
                                    turnId,
                                    seq.getAndIncrement(),
                                    stateMachine.getState(),
                                    transition,
                                    AgentEventPhase.DELTA,
                                    AgentEventType.MESSAGE,
                                    chatResponseDto
                            ));
                        }
                    }, chatChatCallback.errorCall, chatChatCallback.completeCall);
            chatChatCallback.onWebsocketClose = () -> {
                // 终态幂等保护：避免 complete/error/close 并发时重复收尾
                if (!terminated.compareAndSet(false, true)) {
                    return;
                }
                unbindThinkingOverride.run();
                flushPartialAssistantOnInterrupt(agentChatMemory, turnConversationId, streamedContent,
                        streamedReasoning, streamedTextLock, interruptAssistantFlushed, true);
                // 关闭上游流，停止继续接收增量 token
                if (!disposable.isDisposed()) {
                    disposable.dispose();
                }
                synchronized (turnLock) {
                    if (stateMachine.getState() != AgentState.COMPLETED
                            && stateMachine.getState() != AgentState.FAILED
                            && stateMachine.getState() != AgentState.CANCELLED) {
                        AgentStateTransition toCancelled = stateMachine.transit(AgentState.CANCELLED, "websocketClosed");
                        recordingResponseCall.accept(AgentEventBuilder.build(
                                finalContextId,
                                turnId,
                                seq.getAndIncrement(),
                                stateMachine.getState(),
                                toCancelled,
                                AgentEventPhase.COMPLETE,
                                AgentEventType.SYSTEM,
                                Map.of("notice", "cancelled")
                        ));
                    }
                }
                flushTurnTrace.run();
                runOriginalCompleteCall(originalCompleteCall);
            };
            log.info("conversationId: {}, ask: {}", turnConversationId, latestUserMessage);
        } catch (Throwable t) {
            log.error("handleChat failed, contextId={}, detail={}", contextId, LlmProviderErrorFormatter.formatForLog(t), t);
            if (conversationIdRef.get() != null) {
                ThinkingOverrideRegistry.unbind(conversationIdRef.get());
            }
            terminateTurnWithFailure(chatChatCallback, contextId, turnId, seq, stateMachine, turnLock,
                    terminated, originalCompleteCall, resolveErrorCode(t), t, null, flushTurnTrace);
        }
    }

    /**
     * 下发整轮 FAILED 事件并关闭 WebSocket（幂等，仅首次生效）。
     */
    private void terminateTurnWithFailure(ChatCallback<AgentUiEventEnvelope> callback,
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
                                          Runnable afterTerminal) {
        if (!terminated.compareAndSet(false, true)) {
            return;
        }
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
    }

    private static void runOriginalCompleteCall(Runnable originalCompleteCall) {
        if (originalCompleteCall != null) {
            try {
                originalCompleteCall.run();
            } catch (Throwable closeErr) {
                log.warn("WebSocket complete 回调执行异常: {}", closeErr.toString());
            }
        }
    }

    /**
     * 将业务异常映射为前端可识别的 errorCode。
     */
    private static String resolveErrorCode(Throwable t) {
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

    /**
     * 回合终态时将状态轨迹写入记忆（append-only turn_trace 审计行），幂等仅落库一次。
     */
    private void flushTurnTrace(ChatMemory chatMemory,
                                String conversationId,
                                String turnId,
                                TurnStepRecorder turnStepRecorder,
                                AtomicBoolean turnTraceFlushed) {
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
            log.warn("Failed to persist turn trace audit, context conversationId={}, turnId={}: {}",
                    conversationId, turnId, e.toString());
        }
    }

    /**
     * WebSocket 断开或流错误时，将已流式输出但未触发记忆 Advisor {@code after()} 的 assistant 补偿写入。
     * 同时落库最终回答（{@code content}）与深度思考（{@code reasoningContent} properties）。
     */
    void flushPartialAssistantOnInterrupt(ChatMemory chatMemory, String conversationId,
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
        if (content.isEmpty() && reasoning.isEmpty()) {
            return;
        }
        chatMemory.add(conversationId, List.of(buildInterruptedAssistantMessage(content, reasoning, addEllipsis)));
    }

    /**
     * 构造中断补偿用的 assistant 消息，与 {@link ChatMemoryMessageCodec} 编码路径对齐。
     */
    static AssistantMessage buildInterruptedAssistantMessage(String content, String reasoning, boolean addEllipsis) {
        String finalContent = content != null ? content : "";
        if (addEllipsis && StringUtils.isNotBlank(finalContent)) {
            finalContent = finalContent + "...";
        }
        String finalReasoning = reasoning != null ? reasoning : "";
        if (addEllipsis && StringUtils.isNotBlank(finalReasoning)) {
            finalReasoning = finalReasoning + "...";
        }
        AssistantMessage.Builder builder = AssistantMessage.builder().content(finalContent);
        if (StringUtils.isNotBlank(finalReasoning)) {
            builder.properties(Map.of(SpringAiReasoningMetadataAdapter.UNIFIED_REASONING_KEY, finalReasoning));
        }
        return builder.build();
    }

    /**
     * 构造 Spring AI 记忆使用的会话键，格式为 userId:contextId:agentId。
     */
    private String buildConversationId(String userId, String contextId, String resolvedAgentId) {
        return ConversationIdCodec.format(userId == null ? ANONYMOUS_USER : userId, contextId, resolvedAgentId);
    }

    /**
     * 模型流式输出片段：回答正文与 Spring AI 推理 metadata 可同 chunk 到达。
     */
    private record StreamingTextParts(String answerDelta, String reasoningDelta) {
    }

    /**
     * 从 ReactAgent 节点输出中提取模型流式文本，忽略工具和 Hook 节点输出。
     */
    private StreamingTextParts extractStreamingParts(NodeOutput nodeOutput,
                                                     ReasoningSnapshotTracker reasoningTracker,
                                                     ThinkingStreamSplitter thinkingStreamSplitter) {
        if (!(nodeOutput instanceof StreamingOutput<?> streamingOutput)) {
            return null;
        }
        if (streamingOutput.getOutputType() != OutputType.AGENT_MODEL_STREAMING) {
            return null;
        }
        Message message = streamingOutput.message();
        if (message instanceof AssistantMessage assistantMessage) {
            AssistantMessageReasoningExtractor.TextParts parts =
                    AssistantMessageReasoningExtractor.splitStreamingDelta(
                            assistantMessage, null, reasoningTracker);
            if (parts == null) {
                return null;
            }
            return new StreamingTextParts(parts.answerDelta(), parts.reasoningDelta());
        }
        String chunk = streamingOutput.chunk();
        AssistantMessageReasoningExtractor.TextParts parts =
                AssistantMessageReasoningExtractor.splitRawChunk(chunk, thinkingStreamSplitter);
        if (parts == null) {
            return null;
        }
        return new StreamingTextParts(parts.answerDelta(), parts.reasoningDelta());
    }

    /**
     * 构造前端沿用的 ChatResponseDto 增量消息。
     */
    private ChatResponseDto buildChatResponseDto(String answerDelta, String reasoningDelta, int index) {
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

    /**
     * 从请求消息列表中逆序提取最新一条用户消息，作为本轮输入。
     */
    private String extractLatestUserMessage(List<MessageDto> messages) {
        return extractLatestUser(messages).getContent();
    }

    private MessageDto extractLatestUser(List<MessageDto> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageDto message = messages.get(i);
            if (message.getRole() == MessageDto.RoleEnum.USER) {
                return message;
            }
        }
        throw new IllegalArgumentException("No user message found in request.");
    }

    /**
     * 控制单条用户输入长度，避免超长文本导致 token 膨胀。
     */
    private String limitMessageLength(String message) {
        if (message == null) {
            return "";
        }
        int maxLen = chatInputProperties.getMaxUserMessageLength();
        if (maxLen > 0 && message.length() > maxLen) {
            log.warn("User message truncated from {} to {}", message.length(), maxLen);
            return message.substring(0, maxLen);
        }
        return message;
    }

    /**
     * 判断是否为上游主动重置连接（常见于偶发网络断连/网关抖动或连接池复用失效连接）。
     * 需遍历 cause 链：Spring AI Advisor 常将 {@link WebClientRequestException} 包装为 {@link IllegalStateException}。
     */
    static boolean isConnectionResetByPeer(Throwable t) {
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 16) {
            if (isConnectionResetSignal(current)) {
                return true;
            }
            if (current.getCause() == null || current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private static boolean isConnectionResetSignal(Throwable t) {
        if (t instanceof WebClientRequestException) {
            return messageIndicatesConnectionReset(t.getMessage())
                    || (t.getCause() != null && messageIndicatesConnectionReset(t.getCause().getMessage()));
        }
        String className = t.getClass().getName();
        if (className.contains("NativeIoException")) {
            return messageIndicatesConnectionReset(t.getMessage());
        }
        return messageIndicatesConnectionReset(t.getMessage())
                || String.valueOf(t).contains("Connection reset by peer");
    }

    private static boolean messageIndicatesConnectionReset(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("Connection reset by peer")
                || message.contains("Connection reset")
                || message.contains("recvAddress");
    }

    /**
     * 仅在“首 token 之前”且未落入工具调用/文本流式输出阶段时重试，避免重复 tool 调用/重复落库。
     */
    private static boolean isStillThinkingAndEmpty(StringBuilder streamedContent,
                                                   StringBuilder streamedReasoning,
                                                   AgentTurnStateMachine stateMachine,
                                                   Object streamedTextLock,
                                                   Object turnLock) {
        int contentLen;
        int reasoningLen;
        synchronized (streamedTextLock) {
            contentLen = streamedContent.length();
            reasoningLen = streamedReasoning.length();
        }
        if (contentLen != 0 || reasoningLen != 0) {
            return false;
        }
        synchronized (turnLock) {
            return stateMachine.getState() == AgentState.THINKING;
        }
    }
}
