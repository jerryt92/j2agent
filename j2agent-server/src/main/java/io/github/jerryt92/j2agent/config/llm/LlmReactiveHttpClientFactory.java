package io.github.jerryt92.j2agent.config.llm;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * LLM 出站 HTTP 客户端工厂：统一连接池、HTTP/1.1 与读/写超时。
 * WebClient（流式）与 RestClient（Anthropic 同步 call）共用同一 {@link HttpClient} 配置。
 */
public final class LlmReactiveHttpClientFactory {

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
     */
    public static WebClient.Builder createWebClientBuilder(String poolName) {
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(createHttpClient(poolName)));
    }

    /**
     * 为 Anthropic 同步 {@code call()} 创建 {@link RestClient.Builder}（与 WebClient 共用超时与连接池）。
     */
    public static RestClient.Builder createRestClientBuilder(String poolName) {
        ReactorClientHttpRequestFactory requestFactory = new ReactorClientHttpRequestFactory(createHttpClient(poolName));
        requestFactory.setConnectTimeout(Duration.ofMillis(LlmSyncTimeouts.CONNECT_TIMEOUT_MILLIS));
        requestFactory.setReadTimeout(LlmSyncTimeouts.responseReadTimeout());
        return RestClient.builder().requestFactory(requestFactory);
    }

    static HttpClient createHttpClient(String poolName) {
        ConnectionProvider connectionProvider = CONNECTION_PROVIDERS.computeIfAbsent(poolName,
                LlmReactiveHttpClientFactory::createConnectionProvider);
        return HttpClient.create(connectionProvider)
                .protocol(HttpProtocol.HTTP11)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, LlmSyncTimeouts.CONNECT_TIMEOUT_MILLIS)
                .responseTimeout(LlmSyncTimeouts.responseReadTimeout())
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(
                                LlmSyncTimeouts.RESPONSE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(
                                LlmSyncTimeouts.RESPONSE_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)));
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
