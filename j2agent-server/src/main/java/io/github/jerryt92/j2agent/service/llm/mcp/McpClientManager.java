package io.github.jerryt92.j2agent.service.llm.mcp;

import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.model.PropertyDto;
import io.github.jerryt92.j2agent.service.PropertiesService;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class McpClientManager {

    private static final String MCP_CONFIG_PROPERTY_NAME = "mcp-config-json";
    private static final String LEGACY_TYPE_SSE = "sse";
    private static final String LEGACY_TYPE_STREAMABLE = "streamable_http";

    private final PropertiesService propertiesService;
    private final McpSyncClientCreator mcpSyncClientCreator;
    private final Map<String, ResolvedConnection> resolvedConnections = new ConcurrentHashMap<>();
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();

    @Getter
    private volatile long lastReloadTime = 0L;

    public McpClientManager(PropertiesService propertiesService, McpSyncClientCreator mcpSyncClientCreator) {
        this.propertiesService = propertiesService;
        this.mcpSyncClientCreator = mcpSyncClientCreator;
    }

    /**
     * 启动后首次加载配置并建立 MCP 连接。
     */
    @PostConstruct
    public void init() {
        reloadAll();
    }

    /**
     * 全量重载连接：关闭旧连接、同步配置、重新注册客户端。
     */
    public synchronized void reloadAll() {
        closeAll();
        syncConfig();
        registerAll();
        lastReloadTime = System.currentTimeMillis();
    }

    /**
     * 重连指定 MCP 服务。
     */
    public synchronized void reconnect(String serverName) {
        closeClient(serverName);
        registerOne(serverName);
        lastReloadTime = System.currentTimeMillis();
    }

    /**
     * 获取当前已注册的 MCP 客户端列表。
     */
    public List<McpSyncClient> getClients() {
        return new ArrayList<>(clients.values());
    }

    /**
     * 按 server 名称获取当前已连接的 MCP 客户端；未连接或不存在时记录 warn 并跳过。
     */
    public List<McpSyncClient> getClients(Collection<String> serverNames) {
        if (CollectionUtils.isEmpty(serverNames)) {
            return Collections.emptyList();
        }
        List<McpSyncClient> result = new ArrayList<>();
        for (String serverName : serverNames) {
            McpSyncClient client = clients.get(serverName);
            if (client == null) {
                log.warn("MCP server not found or offline, skipped: {}", serverName);
                continue;
            }
            result.add(client);
        }
        return result;
    }

    /**
     * 返回配置中解析出的 MCP server 名称集合（含尚未成功连接的项）。
     */
    public Set<String> getConfiguredServerNames() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(resolvedConnections.keySet()));
    }

    /**
     * 获取最近一次连接/调用错误信息。
     */
    public Map<String, String> getLastErrors() {
        return Collections.unmodifiableMap(lastErrors);
    }

    /**
     * 对所有配置服务执行 ping，返回在线状态。
     */
    public Map<String, String> getHealthStatus() {
        Map<String, String> status = new HashMap<>();
        for (String name : getServerNames()) {
            McpSyncClient client = clients.get(name);
            if (client == null) {
                status.put(name, "offline");
                continue;
            }
            try {
                client.ping();
                status.put(name, "online");
            } catch (Exception e) {
                status.put(name, "offline");
                lastErrors.put(name, e.getMessage());
            }
        }
        return status;
    }

    /**
     * 从属性存储读取 legacy MCP JSON 配置。
     */
    public JSONObject getLegacyConfig() {
        String legacyJson = propertiesService.getProperty(MCP_CONFIG_PROPERTY_NAME);
        if (legacyJson == null || legacyJson.isBlank()) {
            return new JSONObject();
        }
        JSONObject jsonObject = JSONObject.parseObject(legacyJson);
        return jsonObject == null ? new JSONObject() : jsonObject;
    }

    /**
     * 更新 legacy MCP JSON 配置并立即触发重载。
     */
    public void updateLegacyConfig(JSONObject config) {
        if (config == null) {
            return;
        }
        PropertyDto propertyDto = new PropertyDto();
        propertyDto.setPropertyName(MCP_CONFIG_PROPERTY_NAME);
        propertyDto.setPropertyValue(JSONObject.toJSONString(config));
        propertiesService.putProperty(List.of(propertyDto));
        reloadAll();
    }

    /**
     * 获取连接元信息，供状态展示接口使用。
     */
    public Map<String, ConnectionMeta> getConnectionMeta() {
        Map<String, ConnectionMeta> result = new HashMap<>();
        resolvedConnections.forEach((name, conn) -> {
            if ("stdio".equals(conn.type())) {
                String endpoint = conn.command();
                if (!CollectionUtils.isEmpty(conn.args())) {
                    endpoint += " " + String.join(" ", conn.args());
                }
                result.put(name, new ConnectionMeta("stdio", endpoint));
                return;
            }
            result.put(name, new ConnectionMeta(conn.type(), conn.baseUrl() + conn.endpoint()));
        });
        return result;
    }

    /**
     * 查询指定服务的工具列表。
     */
    public List<McpSchema.Tool> listTools(String serverName) {
        McpSyncClient client = clients.get(serverName);
        if (client == null) {
            return Collections.emptyList();
        }
        try {
            McpSchema.ListToolsResult listToolsResult = client.listTools();
            return listToolsResult.tools();
        } catch (Exception e) {
            lastErrors.put(serverName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 按当前配置批量注册所有 MCP 服务连接。
     */
    private void registerAll() {
        for (String serverName : getServerNames()) {
            registerOne(serverName);
        }
    }

    /**
     * 注册单个 MCP 服务连接。
     */
    private void registerOne(String serverName) {
        ResolvedConnection resolvedConnection = resolvedConnections.get(serverName);
        if (resolvedConnection == null) {
            return;
        }
        McpSyncClient client = null;
        try {
            client = mcpSyncClientCreator.createAndInitialize(resolvedConnection);
            client.listTools();
            clients.put(serverName, client);
            lastErrors.remove(serverName);
            log.info("MCP server connected: {}", serverName);
        } catch (Exception e) {
            if (client != null) {
                try {
                    client.closeGracefully();
                } catch (Exception closeEx) {
                    log.debug("Close failed MCP client: {}", serverName, closeEx);
                }
            }
            String detail = buildConnectErrorDetail(serverName, e);
            lastErrors.put(serverName, detail);
            log.warn("MCP server connect failed: {}, detail: {}", serverName, detail, e);
        }
    }

    /**
     * 获取当前解析后的 MCP 服务名集合。
     */
    private List<String> getServerNames() {
        return new ArrayList<>(resolvedConnections.keySet());
    }

    /**
     * 同步配置来源：完全从 DB 的 {@code mcp-config-json} 解析并重建统一连接模型。
     */
    private void syncConfig() {
        resolvedConnections.clear();
        loadLegacyJsonIfPresent();
    }

    /**
     * 解析并合并 legacy json 中的 mcpServers 配置。
     */
    private void loadLegacyJsonIfPresent() {
        String legacyJson = propertiesService.getProperty(MCP_CONFIG_PROPERTY_NAME);
        if (legacyJson == null || legacyJson.isBlank()) {
            return;
        }
        JSONObject root = JSONObject.parseObject(legacyJson);
        if (root == null) {
            return;
        }
        JSONObject mcpServers = root.getJSONObject("mcpServers");
        if (mcpServers == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : mcpServers.entrySet()) {
            String serverName = entry.getKey();
            JSONObject server = mcpServers.getJSONObject(serverName);
            if (server == null) {
                continue;
            }
            String type = server.getString("type");
            if (LEGACY_TYPE_SSE.equals(type)) {
                String url = server.getString("url");
                String sseEndpoint = server.getString("sseEndpoint");
                Map<String, String> headers = new HashMap<>();
                JSONObject headersJson = server.getJSONObject("headers");
                if (headersJson != null) {
                    headersJson.forEach((k, v) -> headers.put(k, String.valueOf(v)));
                }
                HttpTarget target = normalizeHttpTarget(url, sseEndpoint, "/sse");
                resolvedConnections.put(serverName, new ResolvedConnection(
                        "sse",
                        target.baseUrl(),
                        target.endpoint(),
                        null,
                        Map.of(),
                        List.of(),
                        headers.isEmpty() ? Map.of() : Map.copyOf(headers)
                ));
            } else if (LEGACY_TYPE_STREAMABLE.equals(type)) {
                String url = server.getString("url");
                String endpoint = server.getString("endpoint");
                Map<String, String> headers = new HashMap<>();
                JSONObject headersJson = server.getJSONObject("headers");
                if (headersJson != null) {
                    headersJson.forEach((k, v) -> headers.put(k, String.valueOf(v)));
                }
                HttpTarget target = normalizeHttpTarget(url, endpoint, "/mcp");
                resolvedConnections.put(serverName, new ResolvedConnection(
                        "streamable_http",
                        target.baseUrl(),
                        target.endpoint(),
                        null,
                        Map.of(),
                        List.of(),
                        headers.isEmpty() ? Map.of() : Map.copyOf(headers)
                ));
            } else {
                String command = server.getString("command");
                List<String> args = server.getList("args", String.class);
                Map<String, String> env = new HashMap<>();
                JSONObject envJson = server.getJSONObject("env");
                if (envJson != null) {
                    envJson.forEach((k, v) -> env.put(k, String.valueOf(v)));
                }
                resolvedConnections.put(serverName, new ResolvedConnection(
                        "stdio",
                        null,
                        null,
                        command,
                        env.isEmpty() ? Map.of() : Map.copyOf(env),
                        args == null ? List.of() : List.copyOf(args),
                        Map.of()
                ));
            }
        }
    }

    private void closeAll() {
        for (String serverName : new ArrayList<>(clients.keySet())) {
            closeClient(serverName);
        }
    }

    /**
     * 关闭并移除指定服务的 MCP 客户端。
     */
    private void closeClient(String serverName) {
        McpSyncClient removed = clients.remove(serverName);
        if (removed != null) {
            try {
                removed.closeGracefully();
            } catch (Exception e) {
                log.debug("Close MCP client failed: {}", serverName, e);
            }
        }
    }

    /**
     * 兼容两类配置：
     * 1) url 仅 baseUrl + endpoint 单独配置
     * 2) url 已包含完整路径（如 https://host/sse），endpoint 为空
     */
    private HttpTarget normalizeHttpTarget(String rawUrl, String configuredEndpoint, String defaultEndpoint) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("MCP http url is empty.");
        }
        String endpoint = (configuredEndpoint == null || configuredEndpoint.isBlank()) ? null : configuredEndpoint;
        try {
            URI uri = URI.create(rawUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (scheme == null || host == null) {
                String fallbackEndpoint = endpoint == null ? defaultEndpoint : endpoint;
                return new HttpTarget(rawUrl.trim(), fallbackEndpoint.startsWith("/") ? fallbackEndpoint : "/" + fallbackEndpoint);
            }
            String baseUrl = port > 0 ? (scheme + "://" + host + ":" + port) : (scheme + "://" + host);
            String path = uri.getPath();
            if (endpoint == null && path != null && !path.isBlank() && !"/".equals(path)) {
                endpoint = path;
            }
            if (endpoint == null || endpoint.isBlank()) {
                endpoint = defaultEndpoint;
            }
            return new HttpTarget(baseUrl, endpoint.startsWith("/") ? endpoint : "/" + endpoint);
        } catch (Exception ex) {
            String fallbackEndpoint = endpoint == null ? defaultEndpoint : endpoint;
            return new HttpTarget(rawUrl.trim(), fallbackEndpoint.startsWith("/") ? fallbackEndpoint : "/" + fallbackEndpoint);
        }
    }

    /**
     * 构造更易定位问题的连接失败提示信息。
     */
    private String buildConnectErrorDetail(String serverName, Exception e) {
        StringBuilder detail = new StringBuilder();
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String rootMessage = root.getMessage() == null ? "" : root.getMessage();
        detail.append(rootMessage);
        if (rootMessage.contains("Invalid SSE response. Status code: 404")) {
            detail.append(" | hint: MCP SSE endpoint likely invalid or expired record id.");
            ResolvedConnection conn = resolvedConnections.get(serverName);
            if (conn != null) {
                if ("sse".equals(conn.type())) {
                    detail.append(" resolvedSse=").append(conn.baseUrl()).append(conn.endpoint());
                } else if ("streamable_http".equals(conn.type())) {
                    detail.append(" resolvedStreamable=").append(conn.baseUrl()).append(conn.endpoint());
                }
            }
        }
        return detail.toString();
    }

    private record HttpTarget(String baseUrl, String endpoint) {
    }

    public record ResolvedConnection(String type,
                                     String baseUrl,
                                     String endpoint,
                                     String command,
                                     Map<String, String> env,
                                     List<String> args,
                                     Map<String, String> headers) {
    }

    public record ConnectionMeta(String type, String endpoint) {
    }
}
