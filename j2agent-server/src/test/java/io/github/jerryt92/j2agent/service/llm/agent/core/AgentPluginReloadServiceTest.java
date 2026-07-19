package io.github.jerryt92.j2agent.service.llm.agent.core;

import io.github.jerryt92.j2agent.service.rag.SimpleRagStoreSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentPluginReloadServiceTest {

    private AgentPluginRegistry agentPluginRegistry;
    private AgentRouter agentRouter;
    private SimpleRagStoreSyncService simpleRagStoreSyncService;
    private AgentPluginReloadService reloadService;

    @BeforeEach
    void setUp() {
        agentPluginRegistry = mock(AgentPluginRegistry.class);
        agentRouter = mock(AgentRouter.class);
        simpleRagStoreSyncService = mock(SimpleRagStoreSyncService.class);
        reloadService = new AgentPluginReloadService(agentPluginRegistry, agentRouter, simpleRagStoreSyncService);
    }

    @Test
    void shouldDelegateReloadToRegistryThenRefreshRouter() {
        when(agentPluginRegistry.reload()).thenReturn(
                AgentPluginRegistry.AgentPluginReloadOutcome.success(List.of("agents/demo/demo.jar"), List.of("demo_agent")));

        AgentPluginRegistry.AgentPluginReloadOutcome outcome = reloadService.reload();

        assertTrue(outcome.success());
        verify(agentPluginRegistry).reload();
        verify(agentRouter).refresh();
        verify(simpleRagStoreSyncService, org.mockito.Mockito.never())
                .invalidateByOwnerAgentIds(org.mockito.ArgumentMatchers.any());
        verify(simpleRagStoreSyncService).synchronizeSimpleRagRetrievers();
    }

    @Test
    void shouldInvalidateAllAgentsWhenGlobalReloadRebuildRequested() {
        when(agentPluginRegistry.reload()).thenReturn(
                AgentPluginRegistry.AgentPluginReloadOutcome.success(
                        List.of("agents/demo/demo.jar"), List.of("demo_agent", "other_agent")));

        AgentPluginRegistry.AgentPluginReloadOutcome outcome = reloadService.reload(true);

        assertTrue(outcome.success());
        verify(simpleRagStoreSyncService)
                .invalidateByOwnerAgentIds(List.of("demo_agent", "other_agent"));
        verify(simpleRagStoreSyncService).synchronizeSimpleRagRetrievers();
    }

    @Test
    void shouldNotRefreshRouterWhenRegistryReloadFails() {
        when(agentPluginRegistry.reload()).thenReturn(
                AgentPluginRegistry.AgentPluginReloadOutcome.failure(List.of(), List.of(), "agentId conflict"));

        AgentPluginRegistry.AgentPluginReloadOutcome outcome = reloadService.reload();

        assertFalse(outcome.success());
        verify(agentPluginRegistry).reload();
        verifyNoInteractions(agentRouter);
        verifyNoInteractions(simpleRagStoreSyncService);
    }

    @Test
    void shouldReloadPackageAndInvalidateSimpleRagWhenRebuildRequested() {
        when(agentPluginRegistry.reloadPackage("demo-agent")).thenReturn(
                AgentPluginRegistry.AgentPluginReloadOutcome.success(
                        List.of("agents/demo-agent/demo.jar"), List.of("demo_agent")));
        when(agentPluginRegistry.getLoadedAgentIdsForPackage("demo-agent"))
                .thenReturn(List.of("demo_agent"));

        AgentPluginRegistry.AgentPluginReloadOutcome outcome =
                reloadService.reloadPackage("demo-agent", true);

        assertTrue(outcome.success());
        verify(agentPluginRegistry).reloadPackage("demo-agent");
        verify(agentRouter).refresh();
        verify(simpleRagStoreSyncService).invalidateByOwnerAgentIds(List.of("demo_agent"));
        verify(simpleRagStoreSyncService).synchronizeSimpleRagRetrievers();
    }

    @Test
    void shouldReloadPackageWithoutInvalidatingSimpleRagWhenRebuildNotRequested() {
        when(agentPluginRegistry.reloadPackage("demo-agent")).thenReturn(
                AgentPluginRegistry.AgentPluginReloadOutcome.success(
                        List.of("agents/demo-agent/demo.jar"), List.of("demo_agent")));

        AgentPluginRegistry.AgentPluginReloadOutcome outcome =
                reloadService.reloadPackage("demo-agent", false);

        assertTrue(outcome.success());
        verify(agentPluginRegistry).reloadPackage("demo-agent");
        verify(agentRouter).refresh();
        verify(simpleRagStoreSyncService, org.mockito.Mockito.never()).invalidateByOwnerAgentIds(org.mockito.ArgumentMatchers.any());
        verify(simpleRagStoreSyncService).synchronizeSimpleRagRetrievers();
    }

    @Test
    void shouldNotRefreshWhenPackageReloadFails() {
        when(agentPluginRegistry.reloadPackage("demo-agent")).thenReturn(
                AgentPluginRegistry.AgentPluginReloadOutcome.failure(
                        List.of(), List.of(), "not found"));

        AgentPluginRegistry.AgentPluginReloadOutcome outcome =
                reloadService.reloadPackage("demo-agent", true);

        assertFalse(outcome.success());
        verify(agentPluginRegistry).reloadPackage("demo-agent");
        verifyNoInteractions(agentRouter);
        verifyNoInteractions(simpleRagStoreSyncService);
    }
}
