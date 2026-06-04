package io.github.jerryt92.j2agent.service.llm.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class McpHealthService {

    private final McpRuntimeProperties mcpRuntimeProperties;
    private final McpClientManager mcpClientManager;
    private final McpToolRegistry mcpToolRegistry;

    private volatile long lastHealthCheckTs = 0L;

    public McpHealthService(McpRuntimeProperties mcpRuntimeProperties,
                            McpClientManager mcpClientManager,
                            McpToolRegistry mcpToolRegistry) {
        this.mcpRuntimeProperties = mcpRuntimeProperties;
        this.mcpClientManager = mcpClientManager;
        this.mcpToolRegistry = mcpToolRegistry;
    }

    /**
     * 固定 1s tick 执行调度；是否真正进行健康检查由 DB 配置的 intervalSeconds 控制。
     */
    @Scheduled(fixedDelay = 1000)
    public void healthCheck() {
        long intervalMillis = Math.max(0, mcpRuntimeProperties.getHealthCheckIntervalSeconds()) * 1000;
        long now = System.currentTimeMillis();
        if (intervalMillis > 0 && now - lastHealthCheckTs < intervalMillis) {
            return;
        }
        lastHealthCheckTs = now;

        Map<String, String> status = mcpClientManager.getHealthStatus();
        boolean reconnected = false;
        boolean autoReconnect = mcpRuntimeProperties.isAutoReconnect();
        status.forEach((name, state) -> {
            if ("offline".equals(state) && autoReconnect) {
                log.warn("MCP server offline, reconnecting: {}", name);
                mcpClientManager.reconnect(name);
            }
        });
        reconnected = autoReconnect && status.containsValue("offline");
        mcpToolRegistry.refreshToolCallbacks(reconnected ? "health-check-reconnect" : "health-check");
    }
}
