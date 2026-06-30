package io.github.jerryt92.j2agent.service.llm.chat;

/**
 * 用户中断或 WebSocket 关闭导致回合被取消。
 */
public class TurnCancelledException extends RuntimeException {

    private final String turnId;

    public TurnCancelledException(String turnId) {
        super("chat turn cancelled: " + turnId);
        this.turnId = turnId;
    }

    public String getTurnId() {
        return turnId;
    }
}
