package io.github.jerryt92.j2agent.event;

/**
 * 模型提供商配置发生变化（CRUD 或切换当前生效项）时触发，由 AI 运行时监听并热重载。
 */
public class ProviderConfigChangedEvent {

    /** 受影响的 api_type，例如 {@code llm} / {@code embedding} */
    private final String apiType;

    /** 是否切换了当前生效项 */
    private final boolean activeSwitched;

    public ProviderConfigChangedEvent(String apiType, boolean activeSwitched) {
        this.apiType = apiType;
        this.activeSwitched = activeSwitched;
    }

    public String getApiType() {
        return apiType;
    }

    public boolean isActiveSwitched() {
        return activeSwitched;
    }
}
