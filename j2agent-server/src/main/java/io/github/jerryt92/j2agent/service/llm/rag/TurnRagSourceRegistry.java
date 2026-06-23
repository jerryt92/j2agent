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
import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 单轮流式对话内 RAG 来源收集与 WebSocket 推送。
 * <p>使用 {@code conversationId} 作为键（非 ThreadLocal），因 RAG Advisor 常在 {@code publishOn} 后的工作线程执行。</p>
 */
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
     * 子智能体调用运行时与直进使用相同 conversationId 键；共享 persist 回合 Holder 以推送 RAG 来源并落库。
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
     * 移除子智能体调用运行时键，不影响 persist 回合 Holder。
     */
    public static void unshareHolder(String runtimeConversationId) {
        if (runtimeConversationId != null && !runtimeConversationId.isBlank()) {
            BY_CONVERSATION.remove(runtimeConversationId);
        }
    }

    private static Holder resolveHolder(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        return BY_CONVERSATION.get(conversationId);
    }

    /**
     * 首次有非空来源时缓存 rag_infos JSON 供落库；{@code displayToFrontend=true} 时额外推送 WebSocket PATCH。
     * 后续调用幂等忽略。
     */
    public static void publishSources(String conversationId,
                                      List<FileDto> srcFiles,
                                      List<RagInfoDto> ragInfos,
                                      boolean displayToFrontend) {
        if (conversationId == null || conversationId.isBlank() || srcFiles == null || srcFiles.isEmpty()) {
            return;
        }
        Holder holder = resolveHolder(conversationId);
        if (holder == null) {
            AgentRunLogger.warnByConversationId(conversationId, AgentRunEventType.RAG_SOURCE,
                    AgentRunLogger.kv("rag", "skipped=registryMissing"),
                    "RAG source collection skipped: turn registry missing");
            return;
        }
        if (holder.collected) {
            return;
        }
        holder.collected = true;
        holder.ragInfosJson = JSON.toJSONString(ragInfos != null ? ragInfos : List.of());
        if (!displayToFrontend) {
            AgentRunLogger.infoByConversationId(conversationId, AgentRunEventType.RAG_SOURCE,
                    AgentRunLogger.kv("rag", "mdFiles=" + srcFiles.size() + ",display=false"),
                    "RAG sources collected");
            return;
        }
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
        AgentRunLogger.infoByConversationId(conversationId, AgentRunEventType.RAG_SOURCE,
                AgentRunLogger.kv("rag", "mdFiles=" + srcFiles.size() + ",display=true"),
                "RAG sources pushed to frontend");
    }

    /**
     * 取出本回合 rag_infos JSON 供落库；未采集过来源时返回 null。
     */
    public static String drainRagInfosJson(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        Holder holder = resolveHolder(conversationId);
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
        private boolean collected;
        private String ragInfosJson;
    }
}
