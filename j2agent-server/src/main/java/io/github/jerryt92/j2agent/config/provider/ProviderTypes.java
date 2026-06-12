package io.github.jerryt92.j2agent.config.provider;

import java.util.Set;

/**
 * 提供商配置相关的常量与允许值清单。
 */
public final class ProviderTypes {

    /** api_type 取值 */
    public static final String API_TYPE_LLM = "llm";
    public static final String API_TYPE_EMBEDDING = "embedding";
    public static final Set<String> SUPPORTED_API_TYPES = Set.of(API_TYPE_LLM, API_TYPE_EMBEDDING);

    /** LLM provider_type 取值 */
    public static final String LLM_OPEN_AI = "open-ai";
    public static final String LLM_VLLM = "vllm";
    public static final String LLM_ANTHROPIC = "anthropic";
    public static final String LLM_OLLAMA = "ollama";
    public static final String LLM_LM_STUDIO = "lm-studio";
    public static final Set<String> SUPPORTED_LLM_PROVIDERS =
            Set.of(LLM_OPEN_AI, LLM_VLLM, LLM_ANTHROPIC, LLM_OLLAMA, LLM_LM_STUDIO);

    /** Embedding provider_type 取值 */
    public static final String EMB_OPEN_AI = "open-ai";
    public static final String EMB_OLLAMA = "ollama";
    public static final Set<String> SUPPORTED_EMBEDDING_PROVIDERS =
            Set.of(EMB_OPEN_AI, EMB_OLLAMA);

    private ProviderTypes() {
    }

    /** 判断 apiType 是否合法 */
    public static boolean isSupportedApiType(String apiType) {
        return apiType != null && SUPPORTED_API_TYPES.contains(apiType);
    }

    /** 判断 providerType 在指定 apiType 下是否合法 */
    public static boolean isSupportedProvider(String apiType, String providerType) {
        if (providerType == null) {
            return false;
        }
        return switch (apiType == null ? "" : apiType) {
            case API_TYPE_LLM -> SUPPORTED_LLM_PROVIDERS.contains(providerType);
            case API_TYPE_EMBEDDING -> SUPPORTED_EMBEDDING_PROVIDERS.contains(providerType);
            default -> false;
        };
    }
}
