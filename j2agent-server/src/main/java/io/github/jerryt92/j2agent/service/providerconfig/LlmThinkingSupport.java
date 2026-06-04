package io.github.jerryt92.j2agent.service.providerconfig;

import io.github.jerryt92.j2agent.service.llm.agent.AgentThinkingOverride;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * LLM 深度思考（推理链）能力：按提供商判断是否支持，并将配置映射到 Spring AI 标准选项。
 */
public final class LlmThinkingSupport {

    /** 历史别名，读配置时等价于 {@link #MODE_PROVIDER_DEFAULT} */
    private static final String LEGACY_MODE_AUTO = "auto";

    /** 配置取值：提供商默认（不向模型传 thinking/think，由服务或模型默认） */
    public static final String MODE_PROVIDER_DEFAULT = "provider_default";
    /** 配置取值：开启 */
    public static final String MODE_ON = "on";
    /** 配置取值：关闭 */
    public static final String MODE_OFF = "off";

    /** Anthropic 开启思考且未配置 budget 时使用的默认 budget_tokens */
    public static final int ANTHROPIC_DEFAULT_THINKING_BUDGET = 10240;

    private LlmThinkingSupport() {
    }

    /**
     * 当前提供商是否支持可配置的深度思考。
     */
    public static boolean supports(String providerType) {
        if (providerType == null) {
            return false;
        }
        return ProviderTypes.LLM_ANTHROPIC.equals(providerType)
                || ProviderTypes.LLM_OLLAMA.equals(providerType);
    }

    /**
     * 将配置中的 thinkingMode 规范为 provider_default / on / off。
     */
    public static String normalizeMode(String raw) {
        if (StringUtils.isBlank(raw)) {
            return MODE_PROVIDER_DEFAULT;
        }
        String mode = raw.trim().toLowerCase();
        if (LEGACY_MODE_AUTO.equals(mode)) {
            return MODE_PROVIDER_DEFAULT;
        }
        if (MODE_ON.equals(mode) || MODE_OFF.equals(mode) || MODE_PROVIDER_DEFAULT.equals(mode)) {
            return mode;
        }
        return MODE_PROVIDER_DEFAULT;
    }

    /**
     * 将深度思考配置应用到 Anthropic ChatOptions。
     */
    public static void applyAnthropic(AnthropicChatOptions.Builder optionsBuilder,
                                      LlmActiveConfig cfg,
                                      AgentThinkingOverride override) {
        if (optionsBuilder == null || cfg == null) {
            return;
        }
        String mode = resolveMode(cfg, override);
        if (MODE_PROVIDER_DEFAULT.equals(mode)) {
            return;
        }
        if (MODE_OFF.equals(mode)) {
            optionsBuilder.thinking(AnthropicApi.ThinkingType.DISABLED, null);
            return;
        }
        int budget = resolveAnthropicBudget(cfg, override);
        optionsBuilder.thinking(AnthropicApi.ThinkingType.ENABLED, budget);
    }

    /**
     * 将深度思考配置应用到 Ollama ChatOptions。
     */
    public static void applyOllama(OllamaChatOptions.Builder optionsBuilder,
                                   LlmActiveConfig cfg,
                                   AgentThinkingOverride override) {
        if (optionsBuilder == null || cfg == null) {
            return;
        }
        String mode = resolveMode(cfg, override);
        if (MODE_PROVIDER_DEFAULT.equals(mode)) {
            return;
        }
        if (MODE_ON.equals(mode)) {
            optionsBuilder.enableThinking();
        } else if (MODE_OFF.equals(mode)) {
            optionsBuilder.disableThinking();
        }
    }

    /**
     * 解析“最终生效模式”：优先 Agent 覆盖，未覆盖时使用提供商默认。
     */
    public static String resolveMode(LlmActiveConfig cfg, AgentThinkingOverride override) {
        if (override != null && override.overridesProvider()) {
            return normalizeMode(override.toModeKey());
        }
        return normalizeMode(cfg == null ? null : cfg.getThinkingMode());
    }

    private static int resolveAnthropicBudget(LlmActiveConfig cfg, AgentThinkingOverride override) {
        Integer configured = cfg == null ? null : cfg.getThinkingBudgetTokens();
        if (configured != null && configured > 0) {
            return configured;
        }
        return ANTHROPIC_DEFAULT_THINKING_BUDGET;
    }
}
