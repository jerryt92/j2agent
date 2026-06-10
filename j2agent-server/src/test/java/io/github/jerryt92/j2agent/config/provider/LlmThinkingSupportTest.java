package io.github.jerryt92.j2agent.config.provider;

import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmThinkingSupportTest {

    @Test
    void resolveMode_withOffOverride_returnsOffRegardlessOfConfig() {
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setThinkingMode("on");

        assertEquals(LlmThinkingSupport.MODE_OFF,
                LlmThinkingSupport.resolveMode(cfg, AgentThinkingOverride.OFF));
    }

    @Test
    void resolveMode_withoutOverride_usesProviderConfig() {
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setThinkingMode("on");

        assertEquals(LlmThinkingSupport.MODE_ON,
                LlmThinkingSupport.resolveMode(cfg, AgentThinkingOverride.USE_PROVIDER_DEFAULT));
    }

    @Test
    void applyAnthropic_withOff_setsThinkingDisabled() {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder().model("test");
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setThinkingMode("on");

        LlmThinkingSupport.applyAnthropic(builder, cfg, AgentThinkingOverride.OFF);

        AnthropicChatOptions options = builder.build();
        assertEquals(AnthropicApi.ThinkingType.DISABLED, options.getThinking().type());
        assertNull(options.getThinking().budgetTokens());
    }

    @Test
    void applyAnthropic_withProviderDefaultOverride_doesNotSetThinking() {
        AnthropicChatOptions.Builder builder = AnthropicChatOptions.builder().model("test");
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setThinkingMode("on");

        LlmThinkingSupport.applyAnthropic(builder, cfg, AgentThinkingOverride.PROVIDER_DEFAULT);

        assertNull(builder.build().getThinking());
    }

    @Test
    void applyOllama_withOff_disablesThinking() {
        OllamaChatOptions.Builder builder = OllamaChatOptions.builder().model("test");
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setThinkingMode("on");

        LlmThinkingSupport.applyOllama(builder, cfg, AgentThinkingOverride.OFF);

        assertEquals(ThinkOption.ThinkBoolean.DISABLED, builder.build().getThinkOption());
    }

    @Test
    void buildSyncCallOptions_anthropic_shouldDisableThinking() {
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setProviderType("anthropic");
        cfg.setThinkingMode("on");

        ChatOptions options = LlmThinkingSupport.buildSyncCallOptions(cfg, 0.0, 512, AgentThinkingOverride.OFF);

        assertTrue(options instanceof AnthropicChatOptions);
        AnthropicChatOptions anthropic = (AnthropicChatOptions) options;
        assertEquals(AnthropicApi.ThinkingType.DISABLED, anthropic.getThinking().type());
    }

    @Test
    void buildSyncCallOptions_ollama_shouldDisableThinking() {
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setProviderType("ollama");
        cfg.setThinkingMode("on");

        ChatOptions options = LlmThinkingSupport.buildSyncCallOptions(cfg, 0.0, 512, AgentThinkingOverride.OFF);

        assertTrue(options instanceof OllamaChatOptions);
        assertEquals(ThinkOption.ThinkBoolean.DISABLED, ((OllamaChatOptions) options).getThinkOption());
    }
}
