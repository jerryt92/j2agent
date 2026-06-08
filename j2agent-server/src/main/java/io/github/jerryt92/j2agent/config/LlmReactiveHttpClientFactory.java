package io.github.jerryt92.j2agent.config;

import io.netty.channel.ChannelOption;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * LLM 出站流式 HTTP 客户端工厂：统一连接池空闲/生命周期与 HTTP/1.1，降低复用已失效连接导致的 RST。
 */
public final class LlmReactiveHttpClientFactory {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_IDLE_SECONDS = 30;
    private static final int MAX_LIFE_SECONDS = 300;
    private static final int EVICT_BACKGROUND_SECONDS = 30;
    private static final int MAX_CONNECTIONS = 200;
    private static final int PENDING_ACQUIRE_MAX_COUNT = 500;
    private static final int PENDING_ACQUIRE_TIMEOUT_SECONDS = 45;

    private static final ConcurrentMap<String, ConnectionProvider> CONNECTION_PROVIDERS = new ConcurrentHashMap<>();

    private LlmReactiveHttpClientFactory() {
    }

    /**
     * 为指定 LLM 提供商创建带独立连接池的 {@link WebClient.Builder}。
     * 同一 {@code poolName} 复用共享 {@link ConnectionProvider}，避免 thinking override 等场景每次重建模型时新建连接池。
     *
     * @param poolName 连接池名称，不同 provider 应使用不同名称以隔离连接
     */
    public static WebClient.Builder createWebClientBuilder(String poolName) {
        ConnectionProvider connectionProvider = CONNECTION_PROVIDERS.computeIfAbsent(poolName,
                LlmReactiveHttpClientFactory::createConnectionProvider);
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .protocol(HttpProtocol.HTTP11)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS);
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
    }

    private static ConnectionProvider createConnectionProvider(String poolName) {
        return ConnectionProvider.builder(poolName)
                .maxConnections(MAX_CONNECTIONS)
                .pendingAcquireMaxCount(PENDING_ACQUIRE_MAX_COUNT)
                .pendingAcquireTimeout(Duration.ofSeconds(PENDING_ACQUIRE_TIMEOUT_SECONDS))
                .maxIdleTime(Duration.ofSeconds(MAX_IDLE_SECONDS))
                .maxLifeTime(Duration.ofSeconds(MAX_LIFE_SECONDS))
                .evictInBackground(Duration.ofSeconds(EVICT_BACKGROUND_SECONDS))
                .build();
    }
}
