package io.github.jerryt92.j2agent.service.llm.agent.core;

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
    private AgentPluginReloadService reloadService;

    @BeforeEach
    void setUp() {
        agentPluginRegistry = mock(AgentPluginRegistry.class);
        agentRouter = mock(AgentRouter.class);
        reloadService = new AgentPluginReloadService(agentPluginRegistry, agentRouter);
    }

    @Test
    void shouldDelegateReloadToRegistryThenRefreshRouter() {
        when(agentPluginRegistry.reload()).thenReturn(
                AgentPluginRegistry.AgentPluginReloadOutcome.success(List.of("agents/demo/demo.jar"), List.of("demo_agent")));

        AgentPluginRegistry.AgentPluginReloadOutcome outcome = reloadService.reload();

        assertTrue(outcome.success());
        verify(agentPluginRegistry).reload();
        verify(agentRouter).refresh();
    }

    @Test
    void shouldNotRefreshRouterWhenRegistryReloadFails() {
        when(agentPluginRegistry.reload()).thenReturn(
                AgentPluginRegistry.AgentPluginReloadOutcome.failure(List.of(), List.of(), "agentId conflict"));

        AgentPluginRegistry.AgentPluginReloadOutcome outcome = reloadService.reload();

        assertFalse(outcome.success());
        verify(agentPluginRegistry).reload();
        verifyNoInteractions(agentRouter);
    }
}
