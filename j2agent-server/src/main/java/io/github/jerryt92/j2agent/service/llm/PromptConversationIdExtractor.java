package io.github.jerryt92.j2agent.service.llm;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

/**
 * 从 {@link Prompt} 中提取 {@link ChatMemory#CONVERSATION_ID}，供运行时深度思考注册表查找。
 */
public final class PromptConversationIdExtractor {

    private PromptConversationIdExtractor() {
    }

    /**
     * 从 RAG {@link Query} 的 history 或 context 中提取 conversationId。
     */
    public static String extract(Query query) {
        if (query == null) {
            return null;
        }
        Map<String, Object> context = query.context();
        if (context != null) {
            Object fromContext = context.get(ChatMemory.CONVERSATION_ID);
            if (fromContext != null) {
                String conversationId = fromContext.toString();
                if (StringUtils.isNotBlank(conversationId)) {
                    return conversationId;
                }
            }
        }
        List<Message> history = query.history();
        if (history == null || history.isEmpty()) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (!(message instanceof UserMessage userMessage)) {
                continue;
            }
            String conversationId = readConversationId(userMessage.getMetadata());
            if (conversationId != null) {
                return conversationId;
            }
        }
        return null;
    }

    /**
     * 逆序扫描用户消息，取最近一条带 conversationId 的 metadata。
     */
    public static String extract(Prompt prompt) {
        if (prompt == null) {
            return null;
        }
        List<Message> instructions = prompt.getInstructions();
        if (instructions == null || instructions.isEmpty()) {
            return null;
        }
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message message = instructions.get(i);
            if (!(message instanceof UserMessage userMessage)) {
                continue;
            }
            String conversationId = readConversationId(userMessage.getMetadata());
            if (conversationId != null) {
                return conversationId;
            }
        }
        return null;
    }

    private static String readConversationId(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Object raw = metadata.get(ChatMemory.CONVERSATION_ID);
        if (raw == null) {
            return null;
        }
        String conversationId = raw.toString();
        return StringUtils.isNotBlank(conversationId) ? conversationId : null;
    }
}
