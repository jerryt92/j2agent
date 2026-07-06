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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 单轮流式对话内 RAG 来源收集与 WebSocket 推送。
 * <p>使用 {@code conversationId} 作为键（非 ThreadLocal），因 RAG Advisor 常在 {@code publishOn} 后的工作线程执行。</p>
 * <p>单回合内多次 RAG（含子智能体委派）按 {@code sourceFile} 路径累加去重后统一 PATCH 与落库。</p>
 */
public final class TurnRagSourceRegistry {

    private static final ConcurrentHashMap<String, Holder> BY_CONVERSATION = new ConcurrentHashMap<>();

    private TurnRagSourceRegistry() {
    }

    /** 绑定当前回合 Holder，用于采集 RAG 来源并推送 PATCH。 */
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
        holder.persistConversationId = conversationId;
        BY_CONVERSATION.put(conversationId, holder);
    }

    /**
     * 子智能体调用运行时与直进使用相同 conversationId 键；共享 persist 回合 Holder 以推送 RAG 来源并落库。
     *
     * @return 是否成功关联到父回合 Holder
     */
    public static boolean shareHolder(String runtimeConversationId, String persistConversationId) {
        if (!StringUtils.hasText(runtimeConversationId) || !StringUtils.hasText(persistConversationId)) {
            return false;
        }
        Holder holder = BY_CONVERSATION.get(persistConversationId);
        if (holder != null) {
            BY_CONVERSATION.put(runtimeConversationId, holder);
            return true;
        }
        return false;
    }

    /** 当前 conversationId 是否已绑定或可解析到回合 Holder。 */
    public static boolean hasHolder(String conversationId) {
        return resolveHolder(conversationId) != null;
    }

    /**
     * 返回回合持久化键（bind 时的父 conversationId）；未绑定时回退入参。
     */
    public static String persistConversationId(String conversationId) {
        Holder holder = resolveHolder(conversationId);
        if (holder != null && StringUtils.hasText(holder.persistConversationId)) {
            return holder.persistConversationId;
        }
        return conversationId;
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
        Holder holder = BY_CONVERSATION.get(conversationId);
        if (holder != null) {
            return holder;
        }
        for (Holder candidate : BY_CONVERSATION.values()) {
            if (conversationId.equals(candidate.persistConversationId)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 按路径累加 RAG 来源；{@code displayToFrontend=true} 时推送含全集的 WebSocket PATCH。
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
                    AgentRunLogger.kv("rag", "skipped=registryMissing,mdFiles=" + srcFiles.size()),
                    "RAG source collection skipped: turn registry missing");
            return;
        }
        int before = holder.ragInfosByPath.size();
        mergeRagInfos(holder, ragInfos);
        int after = holder.ragInfosByPath.size();
        if (after == 0) {
            return;
        }
        holder.ragInfosJson = JSON.toJSONString(holder.mergedRagInfos());
        boolean added = after > before;
        if (!displayToFrontend) {
            return;
        }
        if (!added && holder.displayPushed) {
            return;
        }
        pushSrcFilePatch(holder);
        holder.displayPushed = true;
        AgentRunLogger.infoByConversationId(conversationId, AgentRunEventType.RAG_SOURCE,
                AgentRunLogger.kv("rag", "files=" + after + ",display=true,added=" + (after - before)),
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
        if (holder == null || holder.ragInfosByPath.isEmpty()) {
            return null;
        }
        if (holder.ragInfosJson == null) {
            holder.ragInfosJson = JSON.toJSONString(holder.mergedRagInfos());
        }
        return holder.ragInfosJson;
    }

    /** 清除 persist 键及其全部 runtime 别名。 */
    public static void clear(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        Holder holder = BY_CONVERSATION.remove(conversationId);
        if (holder == null) {
            return;
        }
        Iterator<Map.Entry<String, Holder>> iterator = BY_CONVERSATION.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() == holder) {
                iterator.remove();
            }
        }
    }

    private static void mergeRagInfos(Holder holder, List<RagInfoDto> ragInfos) {
        if (ragInfos == null || ragInfos.isEmpty()) {
            return;
        }
        for (RagInfoDto ragInfo : ragInfos) {
            if (ragInfo == null || ragInfo.getSrcFile() == null) {
                continue;
            }
            String pathKey = pathKey(ragInfo.getSrcFile());
            if (!StringUtils.hasText(pathKey)) {
                continue;
            }
            holder.ragInfosByPath.putIfAbsent(pathKey, ragInfo);
        }
    }

    private static String pathKey(FileDto fileDto) {
        if (fileDto == null) {
            return null;
        }
        if (StringUtils.hasText(fileDto.getRelativePath())) {
            return fileDto.getRelativePath().replace('\\', '/');
        }
        if (StringUtils.hasText(fileDto.getUrl())) {
            return fileDto.getUrl();
        }
        return null;
    }

    private static void pushSrcFilePatch(Holder holder) {
        if (holder.sink == null) {
            return;
        }
        List<FileDto> srcFiles = holder.mergedSrcFiles();
        if (srcFiles.isEmpty()) {
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
    }

    private static final class Holder {
        Consumer<AgentUiEventEnvelope> sink;
        Object turnLock;
        String contextId;
        String turnId;
        AtomicLong seq;
        AgentTurnStateMachine stateMachine;
        int assistantMessageIndex;
        String persistConversationId;
        boolean displayPushed;
        final LinkedHashMap<String, RagInfoDto> ragInfosByPath = new LinkedHashMap<>();
        String ragInfosJson;

        List<RagInfoDto> mergedRagInfos() {
            return new ArrayList<>(ragInfosByPath.values());
        }

        List<FileDto> mergedSrcFiles() {
            return mergedRagInfos().stream()
                    .map(RagInfoDto::getSrcFile)
                    .filter(file -> file != null)
                    .toList();
        }
    }
}
