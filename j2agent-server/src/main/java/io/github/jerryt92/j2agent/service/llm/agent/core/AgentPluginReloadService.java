package io.github.jerryt92.j2agent.service.llm.agent.core;

import io.github.jerryt92.j2agent.service.rag.SimpleRagStoreSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Agent 插件 JAR 热加载门面：协调注册表刷新与路由表更新。
 */
@Slf4j
@Service
public class AgentPluginReloadService {

    private final AgentPluginRegistry agentPluginRegistry;
    private final AgentRouter agentRouter;
    private final SimpleRagStoreSyncService simpleRagStoreSyncService;

    public AgentPluginReloadService(AgentPluginRegistry agentPluginRegistry,
                                    AgentRouter agentRouter,
                                    SimpleRagStoreSyncService simpleRagStoreSyncService) {
        this.agentPluginRegistry = agentPluginRegistry;
        this.agentRouter = agentRouter;
        this.simpleRagStoreSyncService = simpleRagStoreSyncService;
    }

    /**
     * 查询插件 JAR 与已加载插件 Agent。
     */
    public AgentPluginRegistry.AgentPluginStatus getStatus() {
        return agentPluginRegistry.getStatus();
    }

    /**
     * 重新加载插件目录下全部 JAR，并刷新 {@link AgentRouter}。
     */
    public AgentPluginRegistry.AgentPluginReloadOutcome reload() {
        return reload(false);
    }

    /**
     * 重新加载全部插件；可选失效全部已加载 Agent 的 SimpleRag 以重建向量。
     */
    public AgentPluginRegistry.AgentPluginReloadOutcome reload(boolean rebuildSimpleRag) {
        AgentPluginRegistry.AgentPluginReloadOutcome outcome = agentPluginRegistry.reload();
        if (!outcome.success()) {
            return outcome;
        }
        try {
            agentRouter.refresh();
            if (rebuildSimpleRag) {
                simpleRagStoreSyncService.invalidateByOwnerAgentIds(outcome.loadedAgentIds());
            }
            simpleRagStoreSyncService.synchronizeSimpleRagRetrievers();
            log.info("Agent plugins reloaded. rebuildSimpleRag={}, loadedAgentIds={}",
                    rebuildSimpleRag, outcome.loadedAgentIds());
            return outcome;
        } catch (RuntimeException ex) {
            log.error("Agent router refresh failed after plugin reload.", ex);
            return AgentPluginRegistry.AgentPluginReloadOutcome.failure(
                    outcome.jarFiles(),
                    outcome.loadedAgentIds(),
                    ex.getMessage());
        }
    }

    /**
     * 热重载单个插件包；可选失效该包 SimpleRag 以重建向量。
     */
    public AgentPluginRegistry.AgentPluginReloadOutcome reloadPackage(String agentDir, boolean rebuildSimpleRag) {
        AgentPluginRegistry.AgentPluginReloadOutcome outcome = agentPluginRegistry.reloadPackage(agentDir);
        if (!outcome.success()) {
            return outcome;
        }
        try {
            agentRouter.refresh();
            if (rebuildSimpleRag) {
                simpleRagStoreSyncService.invalidateByOwnerAgentIds(
                        agentPluginRegistry.getLoadedAgentIdsForPackage(agentDir));
            }
            simpleRagStoreSyncService.synchronizeSimpleRagRetrievers();
            log.info("Agent package reloaded. agentDir={}, rebuildSimpleRag={}, loadedAgentIds={}",
                    agentDir, rebuildSimpleRag, outcome.loadedAgentIds());
            return outcome;
        } catch (RuntimeException ex) {
            log.error("Agent router refresh failed after package reload: agentDir={}", agentDir, ex);
            return AgentPluginRegistry.AgentPluginReloadOutcome.failure(
                    outcome.jarFiles(),
                    outcome.loadedAgentIds(),
                    ex.getMessage());
        }
    }
}
