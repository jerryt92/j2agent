package io.github.jerryt92.j2agent.config.provider;

import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.Map;

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

    @Test
    void supports_lmStudio_returnsTrue() {
        assertTrue(LlmThinkingSupport.supports(ProviderTypes.LLM_LM_STUDIO));
    }

    @Test
    void applyLmStudio_withOn_setsReasoningEffortHighAndBudget() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("test");
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setThinkingMode("on");
        cfg.setThinkingBudgetTokens(8192);

        LlmThinkingSupport.applyLmStudio(builder, cfg, AgentThinkingOverride.USE_PROVIDER_DEFAULT);

        OpenAiChatOptions options = builder.build();
        assertEquals(LlmThinkingSupport.LM_STUDIO_REASONING_EFFORT_ON, options.getReasoningEffort());
        assertEquals(8192, options.getExtraBody().get("reasoning_tokens"));
    }

    @Test
    void applyLmStudio_withOn_usesDefaultBudgetWhenUnset() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("test");
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setThinkingMode("on");

        LlmThinkingSupport.applyLmStudio(builder, cfg, AgentThinkingOverride.USE_PROVIDER_DEFAULT);

        OpenAiChatOptions options = builder.build();
        assertEquals(LlmThinkingSupport.DEFAULT_THINKING_BUDGET, options.getExtraBody().get("reasoning_tokens"));
    }

    @Test
    void applyLmStudio_withOff_setsReasoningEffortLow() {
        OpenAiChatOptions.Builder builder = OpenAiChatOptions.builder().model("test");
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setThinkingMode("on");

        LlmThinkingSupport.applyLmStudio(builder, cfg, AgentThinkingOverride.OFF);

        OpenAiChatOptions options = builder.build();
        assertEquals(LlmThinkingSupport.LM_STUDIO_REASONING_EFFORT_OFF, options.getReasoningEffort());
        @SuppressWarnings("unchecked")
        Map<String, Object> templateKwargs = (Map<String, Object>) options.getExtraBody().get("chat_template_kwargs");
        assertEquals(false, templateKwargs.get("enable_thinking"));
    }

    @Test
    void buildSyncCallOptions_lmStudio_shouldDisableThinking() {
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setProviderType(ProviderTypes.LLM_LM_STUDIO);
        cfg.setThinkingMode("on");

        ChatOptions options = LlmThinkingSupport.buildSyncCallOptions(cfg, 0.0, 512, AgentThinkingOverride.OFF);

        assertTrue(options instanceof OpenAiChatOptions);
        assertEquals(LlmThinkingSupport.LM_STUDIO_REASONING_EFFORT_OFF,
                ((OpenAiChatOptions) options).getReasoningEffort());
    }
}
