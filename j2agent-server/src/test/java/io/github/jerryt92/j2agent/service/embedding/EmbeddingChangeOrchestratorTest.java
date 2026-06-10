package io.github.jerryt92.j2agent.service.embedding;

import io.github.jerryt92.j2agent.event.ProviderConfigChangedEvent;
import io.github.jerryt92.j2agent.service.AiRuntimeReloadService;
import io.github.jerryt92.j2agent.config.provider.ProviderTypes;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMaintenanceCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmbeddingChangeOrchestratorTest {

    @Mock
    private KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator;
    @Mock
    private AiRuntimeReloadService aiRuntimeReloadService;

    @InjectMocks
    private EmbeddingChangeOrchestrator orchestrator;

    @Test
    void onProviderConfigChanged_whenRuntimeChanged_alwaysRequestsFullRebuild() {
        ProviderConfigChangedEvent event = new ProviderConfigChangedEvent(
                ProviderTypes.API_TYPE_EMBEDDING, true, true);

        orchestrator.onProviderConfigChanged(event);

        verify(maintenanceCoordinator).requestEmbeddingRuntimeRebuild();
    }

    @Test
    void onProviderConfigChanged_whenRuntimeNotChanged_reloadsStackOnly() {
        ProviderConfigChangedEvent event = new ProviderConfigChangedEvent(
                ProviderTypes.API_TYPE_EMBEDDING, false, false);

        orchestrator.onProviderConfigChanged(event);

        verify(aiRuntimeReloadService).reloadEmbeddingStack();
        verify(maintenanceCoordinator, never()).requestEmbeddingRuntimeRebuild();
    }
}
