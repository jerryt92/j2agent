package io.github.jerryt92.j2agent.config.provider;

import io.github.jerryt92.j2agent.event.ProviderConfigChangedEvent;
import io.github.jerryt92.j2agent.service.AiRuntimeReloadService;
import io.github.jerryt92.j2agent.config.provider.ProviderTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 监听 {@link ProviderConfigChangedEvent}，对应 api_type 的运行时组件热更新。
 * Embedding 变更由 {@link io.github.jerryt92.j2agent.service.embedding.EmbeddingChangeOrchestrator} 编排。
 */
@Slf4j
@Component
public class ProviderConfigChangedListener {

    private final AiRuntimeReloadService aiRuntimeReloadService;

    public ProviderConfigChangedListener(AiRuntimeReloadService aiRuntimeReloadService) {
        this.aiRuntimeReloadService = aiRuntimeReloadService;
    }

    @EventListener
    public void handle(ProviderConfigChangedEvent event) {
        try {
            String apiType = event.getApiType();
            if (ProviderTypes.API_TYPE_LLM.equals(apiType)) {
                aiRuntimeReloadService.reloadLlmStack();
            }
        } catch (Exception e) {
            log.error("处理 ProviderConfigChangedEvent 失败: apiType={}", event.getApiType(), e);
        }
    }
}
