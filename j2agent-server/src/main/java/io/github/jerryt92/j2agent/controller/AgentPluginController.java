package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.config.annotation.RequiredRole;
import io.github.jerryt92.j2agent.model.AgentPluginStatusDto;
import io.github.jerryt92.j2agent.model.AgentReloadResult;
import io.github.jerryt92.j2agent.server.api.AgentPluginApi;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentPluginRegistry;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentPluginReloadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部 Agent 插件 JAR 管理接口。
 */
@RestController
@RequiredRole(RequiredRole.ADMIN)
public class AgentPluginController implements AgentPluginApi {

    private final AgentPluginReloadService agentPluginReloadService;

    public AgentPluginController(AgentPluginReloadService agentPluginReloadService) {
        this.agentPluginReloadService = agentPluginReloadService;
    }

    @Override
    public ResponseEntity<AgentPluginStatusDto> getAgentPlugins() {
        AgentPluginRegistry.AgentPluginStatus status = agentPluginReloadService.getStatus();
        AgentPluginStatusDto dto = new AgentPluginStatusDto()
                .jarFiles(status.jarFiles())
                .loadedAgentIds(status.loadedAgentIds());
        return ResponseEntity.ok(dto);
    }

    @Override
    public ResponseEntity<AgentReloadResult> reloadAgents() {
        AgentPluginRegistry.AgentPluginReloadOutcome outcome = agentPluginReloadService.reload();
        AgentReloadResult result = new AgentReloadResult()
                .success(outcome.success())
                .message(outcome.message())
                .jarFiles(outcome.jarFiles())
                .loadedAgentIds(outcome.loadedAgentIds());
        if (!outcome.success()) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }
}
