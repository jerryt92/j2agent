package io.github.jerryt92.j2agent.config.runtime;

import io.github.jerryt92.j2agent.event.PropertiesUpdatedEvent;
import io.github.jerryt92.j2agent.service.AiRuntimeReloadService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class PropertiesReloadListener {

    private final AiRuntimeReloadService aiRuntimeReloadService;

    public PropertiesReloadListener(AiRuntimeReloadService aiRuntimeReloadService) {
        this.aiRuntimeReloadService = aiRuntimeReloadService;
    }

    @EventListener
    public void handlePropertiesUpdated(PropertiesUpdatedEvent event) {
        try {
            aiRuntimeReloadService.reloadOnPropertiesUpdated(event.getPropertyNames());
        } catch (Exception e) {
            log.error("Reload AI properties failed for: {}", event.getPropertyNames(), e);
        }
    }
}
