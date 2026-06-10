package io.github.jerryt92.j2agent.service.llm;

import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.config.provider.ActiveProviderHolder;
import io.github.jerryt92.j2agent.config.provider.LlmActiveConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmSyncServiceTest {

    @Test
    void shouldReloadWithThinkingOff() {
        ActiveProviderHolder holder = mock(ActiveProviderHolder.class);
        LlmActiveConfig cfg = new LlmActiveConfig();
        cfg.setProviderType("open-ai");
        cfg.setBaseUrl("http://localhost");
        cfg.setModelName("test-model");
        when(holder.getActiveLlm()).thenReturn(cfg);

        assertDoesNotThrow(() -> {
            LlmSyncService service = new LlmSyncService(holder);
            service.reload();
        });
    }

    @Test
    void shouldUseOffForSyncThinking() {
        assertEquals(AgentThinkingOverride.OFF, LlmSyncService.syncThinkingOverride());
    }
}
