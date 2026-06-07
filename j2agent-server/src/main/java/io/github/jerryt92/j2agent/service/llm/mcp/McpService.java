package io.github.jerryt92.j2agent.service.llm.mcp;

import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.model.McpStatusItem;
import io.github.jerryt92.j2agent.model.McpToolItem;
import io.github.jerryt92.j2agent.service.llm.agent.inf.feature.McpFeature;
import io.modelcontextprotocol.client.McpSyncClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class McpService {

    private final io.github.jerryt92.j2agent.service.llm.mcp.McpClientManager mcpClientManager;
    private final McpToolRegistry mcpToolRegistry;

    public McpService(io.github.jerryt92.j2agent.service.llm.mcp.McpClientManager mcpClientManager, McpToolRegistry mcpToolRegistry) {
        this.mcpClientManager = mcpClientManager;
        this.mcpToolRegistry = mcpToolRegistry;
    }

    /**
     * 手动重载全部 MCP 连接，并刷新工具回调提供器。
     */
    public void reload() {
        mcpClientManager.reloadAll();
        mcpToolRegistry.refreshToolCallbacks("reload");
    }

    /**
     * 返回当前生效的 MCP 工具回调提供器，供 ChatClient 注入调用。
     */
    public ToolCallbackProvider getToolCallbackProvider() {
        return mcpToolRegistry.getToolCallbackProvider();
    }

    /**
     * 按 Agent {@link McpFeature} 声明合并 MCP 工具回调；不污染全局 {@link #getToolCallbackProvider()}。
     */
    public ToolCallback[] getToolCallbacksForAgent(McpFeature mcpFeature) {
        List<McpSyncClient> clients = mcpFeature.useAllMcpServers()
                ? mcpClientManager.getClients()
                : mcpClientManager.getClients(mcpFeature.useMcpServers());
        if (clients.isEmpty()) {
            return new ToolCallback[0];
        }
        ToolCallbackProvider provider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .build();
        ToolCallback[] callbacks = provider.getToolCallbacks();
        return callbacks == null ? new ToolCallback[0] : callbacks;
    }

    /**
     * 返回各 MCP server 的实时健康状态（online/offline）。
     */
    public Map<String, String> getStatus() {
        return mcpClientManager.getHealthStatus();
    }

    /**
     * 返回最近一次连接或调用失败的错误信息。
     */
    public Map<String, String> getLastErrors() {
        return mcpClientManager.getLastErrors();
    }

    /**
     * 读取持久化的 MCP JSON 配置（兼容 legacy 结构）。
     */
    public JSONObject getMcpConfig() {
        return mcpClientManager.getLegacyConfig();
    }

    /**
     * 更新 MCP JSON 配置并立即重载连接与工具回调。
     */
    public void updateMcpConfig(JSONObject config) {
        log.info("Start MCP config update.");
        mcpClientManager.updateLegacyConfig(config);
        log.info("MCP config reloaded. reloadTime={}", mcpClientManager.getLastReloadTime());
        mcpToolRegistry.refreshToolCallbacks("config-update");
        log.info("MCP tool callbacks refreshed after config update.");
    }

    /**
     * 聚合 MCP server 状态、连接信息与工具列表，供前端展示。
     */
    public List<McpStatusItem> getMcpServerStatus() {
        Map<String, String> statusMap = mcpClientManager.getHealthStatus();
        Map<String, String> errors = mcpClientManager.getLastErrors();
        Map<String, io.github.jerryt92.j2agent.service.llm.mcp.McpClientManager.ConnectionMeta> connectionMeta = mcpClientManager.getConnectionMeta();
        List<McpStatusItem> result = new ArrayList<>();
        for (Map.Entry<String, io.github.jerryt92.j2agent.service.llm.mcp.McpClientManager.ConnectionMeta> entry : connectionMeta.entrySet()) {
            String serverName = entry.getKey();
            io.github.jerryt92.j2agent.service.llm.mcp.McpClientManager.ConnectionMeta meta = entry.getValue();
            McpStatusItem item = new McpStatusItem();
            item.setName(serverName);
            item.setType(meta.type());
            item.setEndpoint(meta.endpoint());
            item.setStatus(statusMap.getOrDefault(serverName, "offline"));
            List<McpToolItem> tools = new ArrayList<>();
            mcpClientManager.listTools(serverName).forEach(tool -> {
                McpToolItem mcpToolItem = new McpToolItem();
                mcpToolItem.setName(tool.name());
                mcpToolItem.setDescription(tool.description());
                tools.add(mcpToolItem);
            });
            item.setTools(tools);
            if (!errors.isEmpty() && errors.containsKey(serverName) && item.getStatus() != null && item.getStatus().equals("offline")) {
                item.setEndpoint(meta.endpoint() + " | error: " + errors.get(serverName));
            }
            result.add(item);
        }
        return result;
    }
}
