package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.model.ChatRequestDto;
import io.github.jerryt92.j2agent.service.llm.agent.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.agent.AiAgent;
import org.apache.commons.lang3.StringUtils;

/**
 * 解析单轮对话最终生效的深度思考策略。
 *
 * <p>优先级：聊天请求 {@code thinkingMode} &gt; Agent 默认 &gt; 提供商配置（由 {@code USE_PROVIDER_DEFAULT} 回退）。
 */
public final class ThinkingOverrideResolver {

    private ThinkingOverrideResolver() {
    }

    /**
     * 合并聊天请求与 Agent 默认，得到本轮应绑定到 {@link ThinkingOverrideRegistry} 的策略。
     */
    public static AgentThinkingOverride resolve(ChatRequestDto request, AiAgent agent) {
        AgentThinkingOverride fromRequest = parseRequestThinkingMode(request);
        if (fromRequest != null) {
            return fromRequest;
        }
        if (agent == null) {
            return AgentThinkingOverride.USE_PROVIDER_DEFAULT;
        }
        return agent.getThinkingOverride();
    }

    private static AgentThinkingOverride parseRequestThinkingMode(ChatRequestDto request) {
        if (request == null) {
            return null;
        }
        ChatRequestDto.ThinkingModeEnum thinkingMode = request.getThinkingMode();
        if (thinkingMode == null) {
            return null;
        }
        return switch (thinkingMode) {
            case PROVIDER_DEFAULT -> AgentThinkingOverride.PROVIDER_DEFAULT;
            case TRUE -> AgentThinkingOverride.ON;
            case FALSE -> AgentThinkingOverride.OFF;
        };
    }

    /**
     * 解析 WebSocket/JSON 原始字符串（兼容尚未生成枚举前的反序列化）。
     */
    public static AgentThinkingOverride fromModeKey(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "provider_default", "auto" -> AgentThinkingOverride.PROVIDER_DEFAULT;
            case "on" -> AgentThinkingOverride.ON;
            case "off" -> AgentThinkingOverride.OFF;
            default -> null;
        };
    }
}
