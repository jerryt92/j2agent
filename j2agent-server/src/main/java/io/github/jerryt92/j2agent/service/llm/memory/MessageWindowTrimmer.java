package io.github.jerryt92.j2agent.service.llm.memory;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 消息窗口裁剪：超出上限时从头部移除非 {@link SystemMessage}，与 Spring AI {@code MessageWindowChatMemory} 淘汰语义一致。
 */
public final class MessageWindowTrimmer {

    private MessageWindowTrimmer() {
    }

    /**
     * 将消息列表裁剪到不超过 {@code maxMessages} 条；{@link SystemMessage} 在淘汰时优先保留。
     */
    public static List<Message> trimToWindow(List<Message> messages, int maxMessages) {
        if (messages == null || messages.isEmpty() || messages.size() <= maxMessages) {
            return messages == null ? List.of() : new ArrayList<>(messages);
        }
        int messagesToRemove = messages.size() - maxMessages;
        List<Message> trimmed = new ArrayList<>();
        int removed = 0;
        for (Message message : messages) {
            if (message instanceof SystemMessage || removed >= messagesToRemove) {
                trimmed.add(message);
            } else {
                removed++;
            }
        }
        return trimmed;
    }
}
