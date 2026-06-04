package io.github.jerryt92.j2agent.service.providerconfig;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * LLM「提供商类型 + 模型名 + baseUrl」组合校验。
 */
public final class LlmProviderModelCompatibility {

    /** 百炼 Anthropic 兼容 baseUrl 示例 */
    private static final String DASHSCOPE_ANTHROPIC_BASE_URL = "https://dashscope.aliyuncs.com/apps/anthropic";

    private static final Pattern ANTHROPIC_MODEL =
            Pattern.compile("claude", Pattern.CASE_INSENSITIVE);

    private static final Pattern OPEN_AI_COMPAT_MODEL =
            Pattern.compile("qwen|deepseek|glm|ernie|gpt-|moonshot|yi-|abab|hunyuan|spark", Pattern.CASE_INSENSITIVE);

    private LlmProviderModelCompatibility() {
    }

    /**
     * 保存或启用 LLM 配置前校验；不通过时返回错误说明。
     */
    public static Optional<String> validate(String providerType, String modelName, String baseUrl) {
        if (StringUtils.isBlank(providerType) || StringUtils.isBlank(modelName)) {
            return Optional.empty();
        }
        String model = modelName.trim();
        if (ProviderTypes.LLM_ANTHROPIC.equals(providerType)
                && OPEN_AI_COMPAT_MODEL.matcher(model).find()
                && !ANTHROPIC_MODEL.matcher(model).find()
                && !isDashScopeAnthropicCompat(baseUrl)) {
            return Optional.of("模型「" + model + "」若走百炼 Anthropic 兼容，baseUrl 请填 "
                    + DASHSCOPE_ANTHROPIC_BASE_URL
                    + "；若走 OpenAI 兼容请改提供商为「OpenAI 兼容」。");
        }
        return Optional.empty();
    }

    /**
     * 流式响应被全部过滤为空时的补充说明。
     */
    public static String emptyStreamHint(String providerType, String modelName, String baseUrl) {
        Optional<String> validation = validate(providerType, modelName, baseUrl);
        if (validation.isPresent()) {
            return validation.get();
        }
        if (ProviderTypes.LLM_ANTHROPIC.equals(providerType) && isDashScopeAnthropicCompat(baseUrl)) {
            return "请确认 baseUrl 为百炼 Anthropic 兼容地址（如 " + DASHSCOPE_ANTHROPIC_BASE_URL
                    + "），模型名与 API Key 已在控制台开通；completionsPath 对 Anthropic 无效可忽略。";
        }
        if (ProviderTypes.LLM_ANTHROPIC.equals(providerType)) {
            return "Anthropic兼容 提供商请使用官方 api.anthropic.com 或百炼 /apps/anthropic 兼容地址。";
        }
        return "请核对 baseUrl、API Key、模型名是否在对应平台可用。";
    }

    public static String emptyStreamHint(String providerType, String modelName) {
        return emptyStreamHint(providerType, modelName, null);
    }

    /**
     * 从 config_json 中读取 modelName（忽略大小写键名）。
     */
    public static String readModelName(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return "";
        }
        Object v = config.get("modelName");
        if (v == null) {
            v = config.get("model_name");
        }
        return v == null ? "" : v.toString().trim();
    }

    /**
     * 从 config_json 中读取 baseUrl。
     */
    public static String readBaseUrl(Map<String, Object> config) {
        if (config == null) {
            return "";
        }
        Object v = config.get("baseUrl");
        return v == null ? "" : v.toString().trim();
    }

    private static boolean isDashScopeAnthropicCompat(String baseUrl) {
        if (StringUtils.isBlank(baseUrl)) {
            return false;
        }
        String lower = baseUrl.toLowerCase();
        return lower.contains("dashscope") && lower.contains("/apps/anthropic");
    }
}
