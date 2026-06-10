package io.github.jerryt92.j2agent.service.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时清扫无心跳的流式进行中 Redis 登记，防止中断后 counter 残留。
 */
@Slf4j
@Component
public class ActiveChatTurnWatchdog {

    private final ActiveChatTurnRegistry activeChatTurnRegistry;

    public ActiveChatTurnWatchdog(ActiveChatTurnRegistry activeChatTurnRegistry) {
        this.activeChatTurnRegistry = activeChatTurnRegistry;
    }

    @Scheduled(fixedDelayString = "${j2agent.active-chat-turn.sweeper-interval-seconds:60}000")
    public void sweepStaleTurns() {
        try {
            activeChatTurnRegistry.sweepStaleTurns();
        } catch (Throwable t) {
            log.error("Active chat turn sweeper failed", t);
        }
    }
}
