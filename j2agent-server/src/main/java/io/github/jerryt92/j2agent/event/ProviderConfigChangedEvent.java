package io.github.jerryt92.j2agent.event;

/**
 * 模型提供商配置发生变化（CRUD 或切换当前生效项）时触发，由 AI 运行时监听并热重载。
 */
public class ProviderConfigChangedEvent {

    /** 受影响的 api_type，例如 {@code llm} / {@code embedding} */
    private final String apiType;

    /** 是否切换了当前生效项 */
    private final boolean activeSwitched;

    /** Embedding 运行时连接参数是否变化（需 re-probe 与完全重建） */
    private final boolean embeddingRuntimeChanged;

    public ProviderConfigChangedEvent(String apiType, boolean activeSwitched) {
        this(apiType, activeSwitched, false);
    }

    public ProviderConfigChangedEvent(String apiType, boolean activeSwitched, boolean embeddingRuntimeChanged) {
        this.apiType = apiType;
        this.activeSwitched = activeSwitched;
        this.embeddingRuntimeChanged = embeddingRuntimeChanged;
    }

    public String getApiType() {
        return apiType;
    }

    public boolean isActiveSwitched() {
        return activeSwitched;
    }

    public boolean isEmbeddingRuntimeChanged() {
        return embeddingRuntimeChanged;
    }
}
