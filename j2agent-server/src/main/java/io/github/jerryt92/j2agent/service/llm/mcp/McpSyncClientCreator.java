package io.github.jerryt92.j2agent.service.llm.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.net.http.HttpRequest;
import java.time.Duration;

@Component
public class McpSyncClientCreator {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;

    public McpSyncClientCreator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 基于已解析连接配置创建并初始化同步 MCP 客户端。
     */
    public McpSyncClient createAndInitialize(McpClientManager.ResolvedConnection connection) {
        McpClientTransport transport = createTransport(connection);
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .build();
        client.initialize();
        return client;
    }

    /**
     * 按连接类型选择对应 transport。
     */
    private McpClientTransport createTransport(McpClientManager.ResolvedConnection connection) {
        return switch (connection.type()) {
            case "stdio" -> createStdioTransport(connection);
            case "streamable_http" -> createStreamableTransport(connection);
            case "sse" -> createSseTransport(connection);
            default -> throw new IllegalArgumentException("Unsupported MCP connection type: " + connection.type());
        };
    }

    private McpClientTransport createStdioTransport(McpClientManager.ResolvedConnection connection) {
        ServerParameters.Builder builder = ServerParameters.builder(connection.command());
        if (!CollectionUtils.isEmpty(connection.args())) {
            builder.args(connection.args());
        }
        if (!CollectionUtils.isEmpty(connection.env())) {
            builder.env(connection.env());
        }
        return new StdioClientTransport(builder.build(), new JacksonMcpJsonMapper(objectMapper));
    }

    private McpClientTransport createStreamableTransport(McpClientManager.ResolvedConnection connection) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        if (!CollectionUtils.isEmpty(connection.headers())) {
            connection.headers().forEach(requestBuilder::header);
        }
        return HttpClientStreamableHttpTransport.builder(connection.baseUrl())
                .endpoint(connection.endpoint())
                .requestBuilder(requestBuilder)
                .build();
    }

    private McpClientTransport createSseTransport(McpClientManager.ResolvedConnection connection) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
        if (!CollectionUtils.isEmpty(connection.headers())) {
            connection.headers().forEach(requestBuilder::header);
        }
        return HttpClientSseClientTransport.builder(connection.baseUrl())
                .sseEndpoint(connection.endpoint())
                .requestBuilder(requestBuilder)
                .build();
    }
}
