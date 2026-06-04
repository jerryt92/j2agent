package io.github.jerryt92.j2agent.service.embedding;

import io.github.jerryt92.j2agent.config.RetrieveProperties;
import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.service.providerconfig.ActiveProviderHolder;
import io.github.jerryt92.j2agent.service.providerconfig.EmbeddingActiveConfig;
import io.github.jerryt92.j2agent.service.providerconfig.ProviderTypes;
import io.github.jerryt92.j2agent.utils.HashUtil;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import javax.net.ssl.SSLException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Embedding 服务：从 {@link ActiveProviderHolder} 读取当前 Embedding 配置，按 provider 调用对应 API。
 */
@Slf4j
@Service
public class EmbeddingService {
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int READ_WRITE_TIMEOUT_SECONDS = 60;
    private static final int RESPONSE_TIMEOUT_SECONDS = 60;
    private static final int BLOCK_TIMEOUT_SECONDS = 65;
    private static final int MAX_IDLE_SECONDS = 30;
    private static final int MAX_LIFE_SECONDS = 300;
    /** Ollama embedding API 路径 */
    private static final String OLLAMA_EMBEDDINGS_PATH = "/api/embed";

    // 用于标记数据所属嵌入模型/版本
    @Getter
    private String checkEmbeddingHash;
    @Getter
    private Integer dimension;
    public final EmbeddingModel.EmbeddingsRequest checkEmbeddingsRequest =
            new EmbeddingModel.EmbeddingsRequest().setInput(List.of("test"));

    private final ActiveProviderHolder activeProviderHolder;
    private final RetrieveProperties retrieveProperties;
    private volatile WebClient webClient;
    private volatile String embeddingsPath;

    public EmbeddingService(ActiveProviderHolder activeProviderHolder,
                            RetrieveProperties retrieveProperties) {
        this.activeProviderHolder = activeProviderHolder;
        this.retrieveProperties = retrieveProperties;
        rebuildClient();
    }

    /**
     * 按当前 Embedding 配置重建底层 WebClient 与请求路径；配置不可用时仅打印日志、保留旧客户端。
     */
    public void rebuildClient() {
        EmbeddingActiveConfig cfg = activeProviderHolder.getActiveEmbedding();
        if (cfg == null) {
            // 无当前配置时清空客户端状态，避免沿用旧连接导致误判为可用。
            webClient = null;
            embeddingsPath = null;
            log.warn("未找到生效中的 Embedding 配置，跳过 WebClient 重建");
            return;
        }
        SslContext sslContext;
        try {
            sslContext = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                    .build();
            SslContext finalSslContext = sslContext;

            ConnectionProvider connectionProvider = ConnectionProvider.builder("embedding-http")
                    .maxIdleTime(Duration.ofSeconds(MAX_IDLE_SECONDS))
                    .maxLifeTime(Duration.ofSeconds(MAX_LIFE_SECONDS))
                    .evictInBackground(Duration.ofSeconds(30))
                    .build();
            HttpClient httpClient = HttpClient.create(connectionProvider)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                    .responseTimeout(Duration.ofSeconds(RESPONSE_TIMEOUT_SECONDS))
                    .doOnConnected(conn -> conn
                            .addHandlerLast(new ReadTimeoutHandler(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            .addHandlerLast(new WriteTimeoutHandler(READ_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)))
                    .secure(t -> t.sslContext(finalSslContext));

            WebClient.Builder builder = WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(httpClient));

            String provider = StringUtils.defaultIfBlank(cfg.getProviderType(), ProviderTypes.EMB_OPEN_AI);
            switch (provider) {
                case ProviderTypes.EMB_OPEN_AI:
                    if (StringUtils.isNotBlank(cfg.getApiKey())) {
                        builder.defaultHeader("Authorization", "Bearer " + cfg.getApiKey());
                    }
                    webClient = builder.baseUrl(StringUtils.defaultString(cfg.getBaseUrl())).build();
                    embeddingsPath = StringUtils.defaultIfBlank(cfg.getEmbeddingsPath(), "/v1/embeddings");
                    break;
                case ProviderTypes.EMB_OLLAMA:
                default:
                    if (StringUtils.isNotBlank(cfg.getApiKey())) {
                        builder.defaultHeader("Authorization", "Bearer " + cfg.getApiKey());
                    }
                    webClient = builder.baseUrl(StringUtils.defaultString(cfg.getBaseUrl())).build();
                    embeddingsPath = OLLAMA_EMBEDDINGS_PATH;
                    break;
            }
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 启动期探针：用 "test" 输入获取向量维度与哈希，作为知识库一致性校验依据。
     */
    public void init() {
        // 每次探针前先重置，避免配置失效后继续持有旧维度误导后续流程。
        dimension = null;
        checkEmbeddingHash = null;
        try {
            EmbeddingModel.EmbeddingsResponse response = embed(checkEmbeddingsRequest);
            if (response != null && !response.getData().isEmpty()) {
                EmbeddingModel.EmbeddingsItem testEmbed = response.getData().getFirst();
                dimension = testEmbed.getEmbeddings().length;
                checkEmbeddingHash = HashUtil.getMessageDigest(
                        Arrays.toString(testEmbed.getEmbeddings()).getBytes(),
                        HashUtil.MdAlgorithm.SHA256);
            } else {
                log.warn("Init failed: Unable to fetch embedding for test input.");
            }
        } catch (IllegalStateException e) {
            // 启动期允许 Embedding 未配置，避免阻塞服务启动；实际调用时再按需报错
            log.warn("Init skipped: Embedding config unavailable.", e);
        } catch (WebClientResponseException | WebClientRequestException e) {
            log.warn("Init failed: Embedding service unavailable.", e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 判断是否存在生效中的 embedding 配置（仅配置态，不代表连通或可用）。
     */
    public boolean hasActiveEmbeddingConfig() {
        return activeProviderHolder.getActiveEmbedding() != null;
    }

    /**
     * 判断 embedding 运行时是否就绪：需要有生效配置、客户端和已探测到维度。
     */
    public boolean isReady() {
        return hasActiveEmbeddingConfig()
                && webClient != null
                && dimension != null
                && dimension > 0;
    }

    public EmbeddingModel.EmbeddingsResponse embed(EmbeddingModel.EmbeddingsRequest embeddingsRequest) {
        EmbeddingActiveConfig cfg = activeProviderHolder.getActiveEmbedding();
        if (cfg == null || webClient == null) {
            throw new IllegalStateException("Embedding 当前无可用配置，请在「设置 → Embedding 接口」中启用一项");
        }
        List<String> sanitizedInput = sanitizeEmbeddingInputs(embeddingsRequest.getInput());
        EmbeddingModel.EmbeddingsRequest request = new EmbeddingModel.EmbeddingsRequest().setInput(sanitizedInput);
        List<EmbeddingModel.EmbeddingsItem> embeddingsItems = new ArrayList<>();
        String provider = StringUtils.defaultIfBlank(cfg.getProviderType(), ProviderTypes.EMB_OPEN_AI);
        switch (provider) {
            case ProviderTypes.EMB_OPEN_AI:
                handleOpenAIEmbeddings(cfg, request, embeddingsItems);
                break;
            case ProviderTypes.EMB_OLLAMA:
            default:
                handleOllamaEmbeddings(cfg, request, embeddingsItems);
                break;
        }
        return new EmbeddingModel.EmbeddingsResponse().setData(embeddingsItems);
    }

    /**
     * 防御性截断单条 Embedding 输入，避免超出厂商上限（如百炼 8192）。
     */
    private List<String> sanitizeEmbeddingInputs(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        int maxLen = retrieveProperties.getMaxEmbeddingInputChars();
        List<String> result = new ArrayList<>(inputs.size());
        for (String input : inputs) {
            if (input == null) {
                result.add("");
                continue;
            }
            if (maxLen <= 0 || input.length() <= maxLen) {
                result.add(input);
                continue;
            }
            log.warn("Embedding input truncated from {} to {}", input.length(), maxLen);
            result.add(input.substring(0, maxLen));
        }
        return result;
    }

    private void handleOpenAIEmbeddings(EmbeddingActiveConfig cfg,
                                        EmbeddingModel.EmbeddingsRequest embeddingsRequest,
                                        List<EmbeddingModel.EmbeddingsItem> embeddingsItems) {
        List<List<String>> partitionInputs = ListUtils.partition(embeddingsRequest.getInput(), 10);
        for (List<String> partitionInput : partitionInputs) {
            OpenAiApi.EmbeddingRequest<List<String>> openAIEmbeddingsRequest =
                    new OpenAiApi.EmbeddingRequest<>(partitionInput, cfg.getModelName());
            OpenAiApi.EmbeddingList<OpenAiApi.Embedding> openAIEmbeddingsResponse = webClient.post()
                    .uri(embeddingsPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(openAIEmbeddingsRequest)
                    .retrieve()
                    .bodyToMono(new ParameterizedTypeReference<OpenAiApi.EmbeddingList<OpenAiApi.Embedding>>() {
                    })
                    .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));

            if (openAIEmbeddingsResponse != null && openAIEmbeddingsResponse.data() != null) {
                for (int i = 0; i < openAIEmbeddingsResponse.data().size(); i++) {
                    embeddingsItems.add(new EmbeddingModel.EmbeddingsItem()
                            .setEmbeddingProvider(cfg.getProviderType())
                            .setEmbeddingModel(cfg.getModelName())
                            .setCheckEmbeddingHash(checkEmbeddingHash)
                            .setText(partitionInput.get(i))
                            .setEmbeddings(openAIEmbeddingsResponse.data().get(i).embedding()));
                }
            }
        }
        log.info("finish embeddings: {}", embeddingsItems.size());
    }

    private void handleOllamaEmbeddings(EmbeddingActiveConfig cfg,
                                        EmbeddingModel.EmbeddingsRequest embeddingsRequest,
                                        List<EmbeddingModel.EmbeddingsItem> embeddingsItems) {
        List<List<String>> partitionedInputs = ListUtils.partition(embeddingsRequest.getInput(), 10);
        int keepAliveSeconds = cfg.getKeepAliveSeconds() == null ? 0 : Math.max(cfg.getKeepAliveSeconds(), 0);
        String keepAlive = keepAliveSeconds + "s";
        for (List<String> partitionInput : partitionedInputs) {
            OllamaApi.EmbeddingsRequest ollamaEmbeddingsRequest = new OllamaApi.EmbeddingsRequest(
                    cfg.getModelName(),
                    partitionInput,
                    keepAlive,
                    null,
                    null,
                    null
            );

            OllamaApi.EmbeddingsResponse ollamaEmbeddingsResponse = webClient.post()
                    .uri(embeddingsPath)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(ollamaEmbeddingsRequest)
                    .retrieve()
                    .bodyToMono(OllamaApi.EmbeddingsResponse.class)
                    .block(Duration.ofSeconds(BLOCK_TIMEOUT_SECONDS));

            if (ollamaEmbeddingsResponse != null && ollamaEmbeddingsResponse.embeddings() != null) {
                List<float[]> embeddings = ollamaEmbeddingsResponse.embeddings();
                for (int i = 0; i < embeddings.size(); i++) {
                    float[] floats = embeddings.get(i);
                    embeddingsItems.add(new EmbeddingModel.EmbeddingsItem()
                            .setEmbeddingProvider(cfg.getProviderType())
                            .setEmbeddingModel(cfg.getModelName())
                            .setCheckEmbeddingHash(checkEmbeddingHash)
                            .setText(partitionInput.get(i))
                            .setEmbeddings(floats));
                }
            }
        }
        log.info("finish embeddings: {}", embeddingsItems.size());
    }
}
