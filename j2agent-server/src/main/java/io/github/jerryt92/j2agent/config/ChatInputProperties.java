package io.github.jerryt92.j2agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户单轮输入长度上限（入库与发往 LLM 一致）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "j2agent.chat-input")
public class ChatInputProperties {

    /**
     * 单条用户消息最大字符数（Java {@code String#length}），超出则截断。
     */
    private int maxUserMessageLength = 32_768;
}
