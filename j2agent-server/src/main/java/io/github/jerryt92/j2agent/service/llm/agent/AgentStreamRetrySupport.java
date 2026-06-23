package io.github.jerryt92.j2agent.service.llm.agent;

import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.service.llm.AgentTurnStateMachine;
import org.springframework.web.reactive.function.client.WebClientRequestException;

/**
 * Agent 流式 LLM 重试判定（与 {@link io.github.jerryt92.j2agent.service.llm.ChatService} 直进路径一致）。
 */
public final class AgentStreamRetrySupport {

    private AgentStreamRetrySupport() {
    }

    public static boolean isConnectionResetByPeer(Throwable t) {
        Throwable current = t;
        int depth = 0;
        while (current != null && depth < 16) {
            if (isConnectionResetSignal(current)) {
                return true;
            }
            if (current.getCause() == null || current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 仅在「首 token 之前」且未落入工具调用/文本流式输出阶段时重试，避免重复 tool 调用/重复落库。
     */
    public static boolean isStillThinkingAndEmpty(StringBuilder streamedContent,
                                                  StringBuilder streamedReasoning,
                                                  AgentTurnStateMachine stateMachine,
                                                  Object streamedTextLock,
                                                  Object turnLock) {
        int contentLen;
        int reasoningLen;
        synchronized (streamedTextLock) {
            contentLen = streamedContent.length();
            reasoningLen = streamedReasoning.length();
        }
        if (contentLen != 0 || reasoningLen != 0) {
            return false;
        }
        synchronized (turnLock) {
            return stateMachine.getState() == AgentState.THINKING;
        }
    }

    private static boolean isConnectionResetSignal(Throwable t) {
        if (t instanceof WebClientRequestException) {
            return messageIndicatesConnectionReset(t.getMessage())
                    || (t.getCause() != null && messageIndicatesConnectionReset(t.getCause().getMessage()));
        }
        String className = t.getClass().getName();
        if (className.contains("NativeIoException")) {
            return messageIndicatesConnectionReset(t.getMessage());
        }
        return messageIndicatesConnectionReset(t.getMessage())
                || String.valueOf(t).contains("Connection reset by peer");
    }

    private static boolean messageIndicatesConnectionReset(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("Connection reset by peer")
                || message.contains("Connection reset")
                || message.contains("recvAddress");
    }
}
