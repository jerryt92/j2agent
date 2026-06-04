package io.github.jerryt92.j2agent.service.llm.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 运行时窗口记忆：{@link #get} 仅返回最近 N 条供 LLM；{@link #add} 仅增量追加到底层仓储，不删库内历史。
 * <p>
 * 与 {@link CompositeKeyChatMemoryRepository} 的 append-only {@code saveAll} 配合；前端历史经
 * {@link org.springframework.ai.chat.memory.ChatMemoryRepository#findByConversationId} 直读全量。
 */
public final class AppendOnlyWindowChatMemory implements ChatMemory {

    private final ChatMemoryRepository chatMemoryRepository;
    private final int maxMessages;

    public AppendOnlyWindowChatMemory(ChatMemoryRepository chatMemoryRepository, int maxMessages) {
        Assert.notNull(chatMemoryRepository, "chatMemoryRepository cannot be null");
        Assert.isTrue(maxMessages > 0, "maxMessages must be greater than 0");
        this.chatMemoryRepository = chatMemoryRepository;
        this.maxMessages = maxMessages;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        Assert.notNull(messages, "messages cannot be null");
        Assert.noNullElements(messages, "messages cannot contain null elements");
        if (messages.isEmpty()) {
            return;
        }
        this.chatMemoryRepository.saveAll(conversationId, messages);
    }

    @Override
    public List<Message> get(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        List<Message> all = this.chatMemoryRepository.findByConversationId(conversationId);
        List<Message> replayable = filterReplayable(all);
        return MessageWindowTrimmer.trimToWindow(replayable, this.maxMessages);
    }

    /**
     * 过滤出可安全送入 LLM 的消息：移除无正文、无 tool_calls 的空 assistant 消息。
     * <p>
     * 部分 assistant 行仅含 reasoningContent（深度思考中断补偿），对 UI 历史有价值但不应参与回放
     * ——Provider 要求 assistant 消息至少提供 content、tool_calls 或等价字段。
     */
    private static List<Message> filterReplayable(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages == null ? List.of() : messages;
        }
        List<Message> result = new ArrayList<>(messages.size());
        for (Message m : messages) {
            if (m instanceof AssistantMessage am) {
                if (!am.hasToolCalls() && !StringUtils.hasText(am.getText())) {
                    continue;
                }
            }
            result.add(m);
        }
        return result;
    }

    @Override
    public void clear(String conversationId) {
        Assert.hasText(conversationId, "conversationId cannot be null or empty");
        this.chatMemoryRepository.deleteByConversationId(conversationId);
    }
}
