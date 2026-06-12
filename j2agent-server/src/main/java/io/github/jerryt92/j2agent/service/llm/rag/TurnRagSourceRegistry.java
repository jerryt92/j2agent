package io.github.jerryt92.j2agent.service.llm.rag;

import com.alibaba.fastjson2.JSON;
import io.github.jerryt92.j2agent.model.AgentEventPhase;
import io.github.jerryt92.j2agent.model.AgentEventType;
import io.github.jerryt92.j2agent.model.AgentUiEventEnvelope;
import io.github.jerryt92.j2agent.model.ChatResponseDto;
import io.github.jerryt92.j2agent.model.FileDto;
import io.github.jerryt92.j2agent.model.MessageDto;
import io.github.jerryt92.j2agent.model.RagInfoDto;
import io.github.jerryt92.j2agent.service.llm.AgentEventBuilder;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 单轮流式对话内 RAG 来源收集与 WebSocket 推送。
 * <p>使用 {@code conversationId} 作为键（非 ThreadLocal），因 RAG Advisor 常在 {@code publishOn} 后的工作线程执行。</p>
 */
@Slf4j
public final class TurnRagSourceRegistry {

    private static final ConcurrentHashMap<String, Holder> BY_CONVERSATION = new ConcurrentHashMap<>();

    private TurnRagSourceRegistry() {
    }

    public static void bind(String conversationId,
                            Consumer<AgentUiEventEnvelope> sink,
                            Object turnLock,
                            String contextId,
                            String turnId,
                            AtomicLong seq,
                            AgentTurnStateMachine stateMachine,
                            int assistantMessageIndex) {
        if (conversationId == null || conversationId.isBlank()) {
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
     * 首次有非空来源时推送 PATCH 并缓存 rag_infos JSON；后续调用幂等忽略。
     */
    public static void publishSources(String conversationId, List<FileDto> srcFiles, List<RagInfoDto> ragInfos) {
        if (conversationId == null || conversationId.isBlank() || srcFiles == null || srcFiles.isEmpty()) {
            return;
        }
        Holder holder = BY_CONVERSATION.get(conversationId);
        if (holder == null) {
            log.warn("RAG 来源发布跳过: 未找到 conversationId={} 的回合注册", conversationId);
            return;
        }
        if (holder.published) {
            return;
        }
        holder.published = true;
        holder.ragInfosJson = JSON.toJSONString(ragInfos != null ? ragInfos : List.of());
        ChatResponseDto payload = new ChatResponseDto();
        MessageDto message = new MessageDto();
        message.setRole(MessageDto.RoleEnum.ASSISTANT);
        message.setIndex(holder.assistantMessageIndex);
        message.setSrcFile(new ArrayList<>(srcFiles));
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

    /**
     * 取出本回合 rag_infos JSON 供落库；未发布过来源时返回 null。
     */
    public static String drainRagInfosJson(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        Holder holder = BY_CONVERSATION.get(conversationId);
        if (holder == null) {
            return null;
        }
        return holder.ragInfosJson;
    }

    public static void clear(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            BY_CONVERSATION.remove(conversationId);
        }
    }

    private static final class Holder {
        private Consumer<AgentUiEventEnvelope> sink;
        private Object turnLock;
        private String contextId;
        private String turnId;
        private AtomicLong seq;
        private AgentTurnStateMachine stateMachine;
        private int assistantMessageIndex;
        private boolean published;
        private String ragInfosJson;
    }
}
