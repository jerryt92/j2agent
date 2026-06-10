package io.github.jerryt92.j2agent.service.embedding;

import io.github.jerryt92.j2agent.event.ProviderConfigChangedEvent;
import io.github.jerryt92.j2agent.service.AiRuntimeReloadService;
import io.github.jerryt92.j2agent.config.provider.ProviderTypes;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMaintenanceCoordinator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Embedding 运行时变更编排：委托 {@link KnowledgeRepoMaintenanceCoordinator} 停止任务并 exclusive 完全重建。
 */
@Slf4j
@Service
public class EmbeddingChangeOrchestrator {

    private final KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator;
    private final AiRuntimeReloadService aiRuntimeReloadService;

    public EmbeddingChangeOrchestrator(KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator,
                                       AiRuntimeReloadService aiRuntimeReloadService) {
        this.maintenanceCoordinator = maintenanceCoordinator;
        this.aiRuntimeReloadService = aiRuntimeReloadService;
    }

    @EventListener
    public void onProviderConfigChanged(ProviderConfigChangedEvent event) {
        if (!ProviderTypes.API_TYPE_EMBEDDING.equals(event.getApiType())) {
            return;
        }
        if (event.isEmbeddingRuntimeChanged()) {
            maintenanceCoordinator.requestEmbeddingRuntimeRebuild();
        } else {
            aiRuntimeReloadService.reloadEmbeddingStack();
        }
    }

    /**
     * 手动重新探测（异步，不触发完全重建）。
     */
    public void enqueueProbeOnly() {
        maintenanceCoordinator.requestProbeOnly();
    }
}
