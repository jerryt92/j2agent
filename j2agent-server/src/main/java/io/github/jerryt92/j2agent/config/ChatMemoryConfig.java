package io.github.jerryt92.j2agent.config;

import io.github.jerryt92.j2agent.service.llm.memory.AppendOnlyWindowChatMemory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 默认聊天记忆配置：运行时窗口 + 仓储全量追加。
 */
@Configuration
public class ChatMemoryConfig {
    private static final int MEMORY_WINDOW_SIZE = 100;

    @Bean("defaultChatMemory")
    @Primary
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return new AppendOnlyWindowChatMemory(chatMemoryRepository, MEMORY_WINDOW_SIZE);
    }
}
