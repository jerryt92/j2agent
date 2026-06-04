package io.github.jerryt92.j2agent.service.llm.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

@Slf4j
@Component
public class McpToolRegistry {

    private final McpClientManager mcpClientManager;

    private final ApplicationEventPublisher eventPublisher;

    @Getter
    private volatile ToolCallbackProvider toolCallbackProvider = ToolCallbackProvider.from();
    private volatile String lastFingerprint = "";

    /**
     * @param mcpClientManager MCP 客户端管理
     * @param eventPublisher   用于在工具回调实际变更后通知重建 Agent 等
     */
    public McpToolRegistry(McpClientManager mcpClientManager, ApplicationEventPublisher eventPublisher) {
        this.mcpClientManager = mcpClientManager;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 组件初始化时基于当前连接刷新一次工具回调。
     */
    @PostConstruct
    public void init() {
        refreshToolCallbacks("init");
    }

    /**
     * 手动刷新工具回调，默认原因为 manual。
     */
    public synchronized void refreshToolCallbacks() {
        refreshToolCallbacks("manual");
    }

    /**
     * 刷新工具回调提供器。若工具指纹未变化则跳过，避免无效重建。
     */
    public synchronized void refreshToolCallbacks(String reason) {
        List<McpSyncClient> clients = mcpClientManager.getClients();
        long reloadGeneration = mcpClientManager.getLastReloadTime();
        String fingerprint = buildFingerprint(clients, reloadGeneration);
        if (fingerprint.equals(lastFingerprint)) {
            log.debug("Skip MCP callback refresh, no tool changes. reason: {}", reason);
            return;
        }
        toolCallbackProvider = SyncMcpToolCallbackProvider.builder()
                .mcpClients(clients)
                .build();
        lastFingerprint = fingerprint;
        log.info("MCP tool callbacks refreshed. clients: {}, reason: {}", clients.size(), reason);
        eventPublisher.publishEvent(new McpToolCallbacksRefreshedEvent(reason));
    }

    private String buildFingerprint(List<McpSyncClient> clients, long reloadGeneration) {
        Set<String> names = validateAndCollectToolNames(clients);
        String toolsPart = String.join("|", new TreeSet<>(names));
        return reloadGeneration + "::" + toolsPart;
    }

    /**
     * 收集并校验所有 MCP 工具名，若有重复则快速失败。
     */
    private Set<String> validateAndCollectToolNames(List<McpSyncClient> clients) {
        Set<String> names = new HashSet<>();
        for (McpSyncClient client : clients) {
            McpSchema.ListToolsResult listToolsResult = client.listTools();
            for (McpSchema.Tool tool : listToolsResult.tools()) {
                if (!names.add(tool.name())) {
                    throw new IllegalStateException("Duplicate MCP tool name detected: " + tool.name());
                }
            }
        }
        return names;
    }
}
