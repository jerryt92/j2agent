package io.github.jerryt92.j2agent.config.chat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 流式进行中 Redis 登记看门狗配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "j2agent.active-chat-turn")
public class ActiveChatTurnProperties {

    /**
     * 心跳 key TTL（秒）；无续期超过此时间视为流式已结束。
     */
    private int heartbeatTtlSeconds = 30;

    /**
     * ChatService 客户端续期节流间隔（秒）。
     */
    private int heartbeatTouchIntervalSeconds = 10;

    /**
     * 定时清扫孤儿 counter/set 的间隔（秒）。
     */
    private int sweeperIntervalSeconds = 60;

    /**
     * counter key 兜底 TTL（小时），防止 unregister 完全遗漏。
     */
    private int keyFallbackTtlHours = 24;
}
