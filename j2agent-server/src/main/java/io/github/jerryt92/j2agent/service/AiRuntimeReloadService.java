package io.github.jerryt92.j2agent.service;

import io.github.jerryt92.j2agent.config.ReloadableRoutingChatModel;
import io.github.jerryt92.j2agent.config.VectorDatabaseInit;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.llm.mcp.McpRuntimeProperties;
import io.github.jerryt92.j2agent.service.providerconfig.ActiveProviderHolder;
import io.github.jerryt92.j2agent.service.providerconfig.EmbeddingActiveConfig;
import io.github.jerryt92.j2agent.service.providerconfig.LlmActiveConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.Collection;

/**
 * AI 运行时配置热更新：重新加载当前生效的提供商配置并刷新 LLM/Embedding/MCP 客户端、向量库。
 */
@Slf4j
@Service
public class AiRuntimeReloadService {

    private final ActiveProviderHolder activeProviderHolder;
    private final ReloadableRoutingChatModel reloadableRoutingChatModel;
    private final EmbeddingService embeddingService;
    private final VectorDatabaseInit vectorDatabaseInit;
    private final McpRuntimeProperties mcpRuntimeProperties;

    public AiRuntimeReloadService(ActiveProviderHolder activeProviderHolder,
                                  ReloadableRoutingChatModel reloadableRoutingChatModel,
                                  EmbeddingService embeddingService,
                                  VectorDatabaseInit vectorDatabaseInit,
                                  McpRuntimeProperties mcpRuntimeProperties) {
        this.activeProviderHolder = activeProviderHolder;
        this.reloadableRoutingChatModel = reloadableRoutingChatModel;
        this.embeddingService = embeddingService;
        this.vectorDatabaseInit = vectorDatabaseInit;
        this.mcpRuntimeProperties = mcpRuntimeProperties;
    }

    /**
     * MCP / RAG 等 ai_properties 变更后调用：刷新 MCP runtime 与（必要时）向量库。
     * LLM/Embedding 提供商配置改由 {@link io.github.jerryt92.j2agent.event.ProviderConfigChangedEvent} 触发。
     */
    public void reloadOnPropertiesUpdated(Collection<String> changedPropertyNames) {
        reloadMcpRuntime();
        if (shouldReloadVectorDatabase(changedPropertyNames)) {
            reloadVectorDatabase();
        }
        if (!CollectionUtils.isEmpty(changedPropertyNames)) {
            log.info("AI runtime reloaded for properties: {}", changedPropertyNames);
        }
    }

    /**
     * 手动全量热更新（含 LLM/Embedding/MCP/向量库）。
     */
    public void reloadEverything() {
        activeProviderHolder.reloadFromDb();
        reloadLlmStack();
        reloadEmbeddingStack();
        reloadMcpRuntime();
        reloadVectorDatabase();
        log.info("AI runtime fully reloaded (manual).");
    }

    /**
     * 重新加载 LLM 活动配置并切换底层 ChatModel。
     */
    public void reloadLlmStack() {
        try {
            activeProviderHolder.reloadFromDb();
            reloadableRoutingChatModel.reload();
            LlmActiveConfig cfg = activeProviderHolder.getActiveLlm();
            log.info("LLM reloaded: provider={}, baseUrl={}, model={}",
                    cfg == null ? "none" : cfg.getProviderType(),
                    cfg == null ? "none" : cfg.getBaseUrl(),
                    cfg == null ? "none" : cfg.getModelName());
        } catch (Exception e) {
            log.error("LLM reload 失败", e);
        }
    }

    /**
     * 重新加载 Embedding 活动配置并切换底层 WebClient。
     */
    public void reloadEmbeddingStack() {
        try {
            activeProviderHolder.reloadFromDb();
            embeddingService.rebuildClient();
            EmbeddingActiveConfig cfg = activeProviderHolder.getActiveEmbedding();
            log.info("Embedding reloaded: provider={}, baseUrl={}, model={}",
                    cfg == null ? "none" : cfg.getProviderType(),
                    cfg == null ? "none" : cfg.getBaseUrl(),
                    cfg == null ? "none" : cfg.getModelName());
        } catch (Exception e) {
            log.error("Embedding reload 失败", e);
        }
    }

    private void reloadMcpRuntime() {
        try {
            mcpRuntimeProperties.reloadFromDb();
        } catch (Exception e) {
            log.error("MCP runtime properties reload failed.", e);
        }
    }

    /**
     * 强制触发向量库重检并按需重建。
     */
    public void reloadVectorDatabase() {
        try {
            vectorDatabaseInit.reload();
        } catch (Exception e) {
            log.error("Vector database reload failed.", e);
        }
    }

    /**
     * 当用户切换当前 Embedding 配置时，立即触发异步初始化流程。
     */
    public void initVectorDatabaseAfterEmbeddingActivated() {
        try {
            vectorDatabaseInit.init();
        } catch (Exception e) {
            log.error("Vector database init after embedding activation failed.", e);
        }
    }

    /**
     * RAG 度量类型变化时需要重检向量库；embedding 切换由 ProviderConfigChangedEvent 单独处理。
     */
    private static boolean shouldReloadVectorDatabase(Collection<String> changedPropertyNames) {
        if (CollectionUtils.isEmpty(changedPropertyNames)) {
            return false;
        }
        return changedPropertyNames.contains(PropertiesService.RETRIEVE_METRIC_TYPE);
    }
}
