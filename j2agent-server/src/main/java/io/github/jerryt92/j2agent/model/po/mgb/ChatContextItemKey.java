package io.github.jerryt92.j2agent.model.po.mgb;

/**
 * Primary key for chat_context_item: message_id only.
 */
public class ChatContextItemKey {
    private String messageId;

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId == null ? null : messageId.trim();
    }
}
