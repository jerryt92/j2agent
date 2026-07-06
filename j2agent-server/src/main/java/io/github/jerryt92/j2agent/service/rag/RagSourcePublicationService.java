package io.github.jerryt92.j2agent.service.rag;

import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.memory.ConversationIdCodec;
import io.github.jerryt92.j2agent.service.llm.rag.TurnRagSourceRegistry;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索完成后同步发布 RAG 来源。
 */
@Service
public class RagSourcePublicationService {

    private static volatile RagSourcePublicationService instance;

    private final RagSourceFileService ragSourceFileService;
    private final AgentRouter agentRouter;

    public RagSourcePublicationService(RagSourceFileService ragSourceFileService, AgentRouter agentRouter) {
        this.ragSourceFileService = ragSourceFileService;
        this.agentRouter = agentRouter;
        instance = this;
    }

    /**
     * 供非 Spring 管理的检索器基类调用。
     */
    public static void tryPublishFromRetriever(String conversationId, String agentId, List<Document> documents) {
        RagSourcePublicationService svc = instance;
        if (svc == null) {
            return;
        }
        svc.tryPublish(conversationId, agentId, documents);
    }

    /**
     * 解析来源并写入回合 Registry；展示开关由 Agent 决定。
     */
    public void tryPublish(String conversationId, String agentId, List<Document> documents) {
        if (!StringUtils.isNotBlank(conversationId) || documents == null || documents.isEmpty()) {
            return;
        }
        String publishConversationId = TurnRagSourceRegistry.persistConversationId(conversationId);
        RagSourceFileService.ResolvedRagSources resolved = ragSourceFileService.resolveUniqueMdSources(documents);
        if (resolved.isEmpty()) {
            if (resolved.stats().inputDocs() > 0) {
                AgentRunLogger.infoByConversationId(publishConversationId, AgentRunEventType.RAG_SOURCE,
                        AgentRunLogger.kv("rag", resolved.stats().toLogFragment()),
                        "RAG sources empty after resolve");
            }
            return;
        }
        boolean display = resolveDisplayEnabled(agentId, conversationId);
        AgentRunLogger.infoByConversationId(publishConversationId, AgentRunEventType.RAG_SOURCE,
                AgentRunLogger.kv("rag", resolved.stats().toLogFragment() + ",display=" + display),
                "RAG sources resolved");
        TurnRagSourceRegistry.publishSources(
                publishConversationId, resolved.srcFiles(), resolved.ragInfos(), display);
    }

    private boolean resolveDisplayEnabled(String agentId, String conversationId) {
        String resolvedAgentId = agentId;
        if (!StringUtils.isNotBlank(resolvedAgentId) && StringUtils.isNotBlank(conversationId)) {
            try {
                resolvedAgentId = ConversationIdCodec.parse(conversationId).agentId();
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        if (!StringUtils.isNotBlank(resolvedAgentId)) {
            return false;
        }
        try {
            AiAgent agent = agentRouter.route(resolvedAgentId);
            return agent != null && agent.isRagSourceDisplayEnabled();
        } catch (Exception ignored) {
            return false;
        }
    }
}
