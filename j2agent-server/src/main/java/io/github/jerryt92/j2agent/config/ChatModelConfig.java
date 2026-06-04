package io.github.jerryt92.j2agent.config;

import io.github.jerryt92.j2agent.service.providerconfig.ActiveProviderHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatModel 装配：对外仅暴露一个可热更新的路由 ChatModel。
 */
@Configuration
public class ChatModelConfig {

    @Autowired
    private ActiveProviderHolder activeProviderHolder;

    /**
     * 实现按当前 LLM 配置在 OpenAI 兼容 / vLLM / Anthropic / Ollama 间路由，并支持热更新。
     */
    @Bean
    public ReloadableRoutingChatModel openAiChatModel() {
        return new ReloadableRoutingChatModel(activeProviderHolder);
    }
}
