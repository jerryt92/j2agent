package io.github.jerryt92.j2agent.service.llm.agent;

/**
 * Agent 级深度思考覆盖策略。
 *
 * <p>子类在 {@link AiAgent#getThinkingOverride()} 中返回对应枚举值即可。
 *
 * <p>{@link #USE_PROVIDER_DEFAULT} 表示沿用管理端 LLM 配置中的 {@code thinkingMode}；
 * {@link #PROVIDER_DEFAULT} 表示强制「不向 API 传 thinking/think」（与配置项「提供商默认」一致）。
 */
public enum AgentThinkingOverride {

    /** 不覆盖，使用「设置 → LLM 接口」中配置的 thinkingMode */
    USE_PROVIDER_DEFAULT,

    /** 覆盖为提供商默认：不向模型显式下发 thinking/think 参数 */
    PROVIDER_DEFAULT,

    /** 覆盖为 on：开启深度思考（Anthropic 的 token 预算仍取自提供商配置或默认 10240） */
    ON,

    /** 覆盖为 off：显式关闭深度思考 */
    OFF;

    /**
     * 是否覆盖提供商默认配置。
     */
    public boolean overridesProvider() {
        return this != USE_PROVIDER_DEFAULT;
    }

    /**
     * 转为运行时 thinkingMode 字符串（provider_default / on / off）。
     */
    public String toModeKey() {
        return switch (this) {
            case USE_PROVIDER_DEFAULT -> null;
            case PROVIDER_DEFAULT -> "provider_default";
            case ON -> "on";
            case OFF -> "off";
        };
    }
}
