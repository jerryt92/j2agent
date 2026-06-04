package io.github.jerryt92.j2agent.service.llm.agent;

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

    public AgentPluginReloadService(AgentPluginRegistry agentPluginRegistry, AgentRouter agentRouter) {
        this.agentPluginRegistry = agentPluginRegistry;
        this.agentRouter = agentRouter;
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
        AgentPluginRegistry.AgentPluginReloadOutcome outcome = agentPluginRegistry.reload();
        if (!outcome.success()) {
            return outcome;
        }
        try {
            agentRouter.refresh();
            log.info("Agent plugins reloaded. loadedAgentIds={}", outcome.loadedAgentIds());
            return outcome;
        } catch (RuntimeException ex) {
            log.error("Agent router refresh failed after plugin reload.", ex);
            return AgentPluginRegistry.AgentPluginReloadOutcome.failure(
                    outcome.jarFiles(),
                    outcome.loadedAgentIds(),
                    ex.getMessage());
        }
    }
}