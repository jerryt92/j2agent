package io.github.jerryt92.j2agent.config.provider;

import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.HashMap;
import java.util.Map;

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

    /** 开启深度思考且未配置 budget 时使用的默认 token 预算（Anthropic budget_tokens / LM Studio reasoning_tokens） */
    public static final int DEFAULT_THINKING_BUDGET = 4096;

    /** LM Studio reasoning_effort：开启深度思考 */
    public static final String LM_STUDIO_REASONING_EFFORT_ON = "high";
    /** LM Studio reasoning_effort：关闭深度思考 */
    public static final String LM_STUDIO_REASONING_EFFORT_OFF = "low";

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
                || ProviderTypes.LLM_OLLAMA.equals(providerType)
                || ProviderTypes.LLM_LM_STUDIO.equals(providerType);
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
     * 构建同步 LLM 调用的 {@link ChatOptions}，按 provider 显式应用深度思考策略。
     *
     * <p>Anthropic → {@code thinking: disabled}；Ollama → {@code disableThinking()}；
     * LM Studio → {@code reasoning_effort: low}；OpenAI 兼容 / vLLM 不传 thinking 参数。
     */
    public static ChatOptions buildSyncCallOptions(LlmActiveConfig cfg,
                                                   double temperature,
                                                   int maxTokens,
                                                   AgentThinkingOverride thinkingOverride) {
        if (cfg == null) {
            return ChatOptions.builder()
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .build();
        }
        String provider = StringUtils.isBlank(cfg.getProviderType())
                ? ProviderTypes.LLM_OPEN_AI
                : cfg.getProviderType().trim();
        return switch (provider) {
            case ProviderTypes.LLM_ANTHROPIC -> {
                AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder()
                        .temperature(temperature)
                        .maxTokens(maxTokens);
                applyAnthropic(builder, cfg, thinkingOverride);
                yield builder.build();
            }
            case ProviderTypes.LLM_OLLAMA -> {
                OllamaChatOptions.Builder builder = OllamaChatOptions.builder()
                        .temperature(temperature)
                        .numPredict(maxTokens);
                applyOllama(builder, cfg, thinkingOverride);
                yield builder.build();
            }
            case ProviderTypes.LLM_LM_STUDIO -> {
                OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder()
                        .temperature(temperature)
                        .maxTokens(maxTokens);
                applyLmStudio(builder, cfg, thinkingOverride);
                yield builder.build();
            }
            default -> ChatOptions.builder()
                    .temperature(temperature)
                    .maxTokens(maxTokens)
                    .build();
        };
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
        int budget = resolveThinkingBudget(cfg);
        optionsBuilder.thinking(AnthropicApi.ThinkingType.ENABLED, budget);
    }

    /**
     * 将深度思考配置应用到 LM Studio OpenAI 兼容 ChatOptions。
     *
     * <p>映射为 {@code reasoning_effort} 与 {@code reasoning_tokens}（extraBody）。
     */
    public static void applyLmStudio(OpenAiChatOptions.Builder optionsBuilder,
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
            optionsBuilder.reasoningEffort(LM_STUDIO_REASONING_EFFORT_OFF);
            optionsBuilder.extraBody(Map.of(
                    "chat_template_kwargs", Map.of("enable_thinking", false)));
            return;
        }
        optionsBuilder.reasoningEffort(LM_STUDIO_REASONING_EFFORT_ON);
        Map<String, Object> extraBody = new HashMap<>();
        extraBody.put("reasoning_tokens", resolveThinkingBudget(cfg));
        optionsBuilder.extraBody(extraBody);
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

    private static int resolveThinkingBudget(LlmActiveConfig cfg) {
        Integer configured = cfg == null ? null : cfg.getThinkingBudgetTokens();
        if (configured != null && configured > 0) {
            return configured;
        }
        return DEFAULT_THINKING_BUDGET;
    }
}
