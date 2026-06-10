package io.github.jerryt92.j2agent.config.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 回合结束后「建议追问」生成策略配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "j2agent.follow-up-suggestions")
public class FollowUpSuggestionProperties {

    /**
     * 是否启用模型生成建议追问。
     */
    private boolean enabled = true;

    /**
     * 等待模型返回的最大秒数，超时则放弃发送 NOTICE。
     */
    private int timeoutSeconds = 12;

    /**
     * 最多返回几条建议（截断）。
     */
    private int maxItems = 5;

    /**
     * 单条建议最大字符数。
     */
    private int maxItemLength = 120;
}
