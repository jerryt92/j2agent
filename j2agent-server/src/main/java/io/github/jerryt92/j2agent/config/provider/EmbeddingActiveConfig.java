package io.github.jerryt92.j2agent.config.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 当前生效的 Embedding 配置。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class EmbeddingActiveConfig {
    /** 提供商类型：open-ai / ollama */
    private String providerType;

    /** 模型名称 */
    private String modelName;
    /** 服务地址 */
    private String baseUrl;
    /** API Key（用作 Authorization）；Ollama 可空 */
    private String apiKey;

    /** OpenAI 兼容的 embeddings 路径 */
    private String embeddingsPath;

    /** Ollama 模型驻留时间（秒） */
    private Integer keepAliveSeconds;

    /** 单次 Embedding API 请求的最大 input 条数；未配置时运行时默认 10 */
    private Integer embeddingBatchSize;

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getEmbeddingsPath() {
        return embeddingsPath;
    }

    public void setEmbeddingsPath(String embeddingsPath) {
        this.embeddingsPath = embeddingsPath;
    }

    public Integer getKeepAliveSeconds() {
        return keepAliveSeconds;
    }

    public void setKeepAliveSeconds(Integer keepAliveSeconds) {
        this.keepAliveSeconds = keepAliveSeconds;
    }

    public Integer getEmbeddingBatchSize() {
        return embeddingBatchSize;
    }

    public void setEmbeddingBatchSize(Integer embeddingBatchSize) {
        this.embeddingBatchSize = embeddingBatchSize;
    }
}
