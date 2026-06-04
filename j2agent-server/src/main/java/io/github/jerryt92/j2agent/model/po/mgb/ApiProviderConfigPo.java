package io.github.jerryt92.j2agent.model.po.mgb;

/**
 * 与表 {@code api_provider_config} 对应的 PO，承载 LLM/Embedding 等不同 api_type 的提供商配置。
 */
public class ApiProviderConfigPo {
    /** 主键 */
    private Long id;
    /** API 类型，如 {@code llm} / {@code embedding} */
    private String apiType;
    /** 配置名称（同一 api_type 下唯一） */
    private String configName;
    /** 提供商类型，如 {@code open-ai} / {@code anthropic} / {@code ollama} / {@code vllm} */
    private String providerType;
    /** 提供商差异化字段的 JSON 文本 */
    private String configJson;
    /** 是否启用：1 启用，0 禁用 */
    private Byte enabled;
    /** 是否为该 api_type 当前生效项：1 生效，0 否 */
    private Byte isCurrent;
    /** 描述 */
    private String description;
    /** 创建时间（毫秒） */
    private Long createTime;
    /** 更新时间（毫秒） */
    private Long updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getApiType() {
        return apiType;
    }

    public void setApiType(String apiType) {
        this.apiType = apiType == null ? null : apiType.trim();
    }

    public String getConfigName() {
        return configName;
    }

    public void setConfigName(String configName) {
        this.configName = configName == null ? null : configName.trim();
    }

    public String getProviderType() {
        return providerType;
    }

    public void setProviderType(String providerType) {
        this.providerType = providerType == null ? null : providerType.trim();
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public Byte getEnabled() {
        return enabled;
    }

    public void setEnabled(Byte enabled) {
        this.enabled = enabled;
    }

    public Byte getIsCurrent() {
        return isCurrent;
    }

    public void setIsCurrent(Byte isCurrent) {
        this.isCurrent = isCurrent;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description == null ? null : description.trim();
    }

    public Long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(Long createTime) {
        this.createTime = createTime;
    }

    public Long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(Long updateTime) {
        this.updateTime = updateTime;
    }
}
