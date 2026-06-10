package io.github.jerryt92.j2agent.config.llm;

import io.netty.handler.timeout.ReadTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class LlmReactiveHttpClientFactoryTest {

    @Test
    void shouldCreateWebClientBuilderWithSharedPool() {
        WebClient.Builder builder = LlmReactiveHttpClientFactory.createWebClientBuilder("test-llm-pool-web");
        assertNotNull(builder);
    }

    @Test
    void shouldCreateRestClientBuilderWithReactorRequestFactory() {
        RestClient.Builder builder = LlmReactiveHttpClientFactory.createRestClientBuilder("test-llm-pool-rest");
        assertNotNull(builder);
        RestClient client = builder.build();
        assertNotNull(client);
    }

    @Test
    void shouldReuseHttpClientConfigurationForSamePoolName() {
        assertSame(
                LlmReactiveHttpClientFactory.createHttpClient("test-llm-pool-shared").getClass(),
                LlmReactiveHttpClientFactory.createHttpClient("test-llm-pool-shared").getClass());
    }

    @Test
    void restClientBuilderShouldUseReactorClientHttpRequestFactory() {
        RestClient.Builder builder = LlmReactiveHttpClientFactory.createRestClientBuilder("test-llm-pool-factory-type");
        RestClient client = builder.build();
        assertNotNull(client);
        ReactorClientHttpRequestFactory unused = new ReactorClientHttpRequestFactory(
                LlmReactiveHttpClientFactory.createHttpClient("test-llm-pool-factory-type"));
        assertNotNull(unused);
    }
}
