package io.github.jerryt92.j2agent.service.llm.mcp;

import io.github.jerryt92.j2agent.service.PropertiesService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MCP 运维参数（完全由 DB 驱动），用于健康检查与自动重连策略。
 */
@Slf4j
@Component
public class McpRuntimeProperties {

    /**
     * 是否自动重连（离线->重新注册连接）。
     */
    public static final String KEY_MCP_AUTO_RECONNECT = "mcp-auto-reconnect";

    /**
     * 健康检查间隔（秒）。
     */
    public static final String KEY_MCP_HEALTH_CHECK_INTERVAL_SECONDS = "mcp-health-check-interval-seconds";

    private final PropertiesService propertiesService;

    private volatile boolean autoReconnect = true;
    private volatile long healthCheckIntervalSeconds = 15L;

    public McpRuntimeProperties(PropertiesService propertiesService) {
        this.propertiesService = propertiesService;
    }

    /**
     * Spring 启动后首次加载 DB 中的 MCP 运维参数。
     */
    @org.springframework.context.event.EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
    public void initOnReady() {
        reloadFromDb();
    }

    /**
     * 从 DB 重载 MCP 运维参数。
     */
    public synchronized void reloadFromDb() {
        this.autoReconnect = readBoolean(KEY_MCP_AUTO_RECONNECT, true);
        this.healthCheckIntervalSeconds = readLong(KEY_MCP_HEALTH_CHECK_INTERVAL_SECONDS, 15L);
        log.info("MCP runtime properties loaded: autoReconnect={}, healthCheckIntervalSeconds={}",
                autoReconnect, healthCheckIntervalSeconds);
    }

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public long getHealthCheckIntervalSeconds() {
        return healthCheckIntervalSeconds;
    }

    private boolean readBoolean(String key, boolean defaultValue) {
        String v = propertiesService.getProperty(key);
        if (v == null) {
            return defaultValue;
        }
        String t = v.trim();
        return "true".equalsIgnoreCase(t) || "1".equals(t);
    }

    private long readLong(String key, long defaultValue) {
        String v = propertiesService.getProperty(key);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (Exception e) {
            log.warn("Invalid long in ai_properties key={}, value={}, using default={}", key, v, defaultValue);
            return defaultValue;
        }
    }
}

