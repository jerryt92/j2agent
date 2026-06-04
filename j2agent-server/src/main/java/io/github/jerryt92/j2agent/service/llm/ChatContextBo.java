package io.github.jerryt92.j2agent.service.llm;

import lombok.Data;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * ChatContextBo 承载会话元数据和消息列表。
 */
@Data
public class ChatContextBo {

    /**
     * 会话上下文ID（业务主键）
     */
    private final String contextId;

    /**
     * 会话所属用户ID
     */
    private final String userId;

    /**
     * 智能体 ID（记忆隔离维度）
     */
    private final String agentId;

    /**
     * 会话标题（通常取首条用户消息摘要）
     */
    private String title;

    /**
     * 记忆管理版本号
     */
    private Integer memoryVersion;

    /**
     * 当前会话最后一条消息索引
     */
    private Integer lastMessageIndex;

    /**
     * 会话更新时间戳（毫秒）
     */
    private Long updateTime;

    /**
     * Spring AI 消息列表（USER/ASSISTANT 等）
     */
    private List<Message> messages;

    public ChatContextBo(String contextId, String userId, String agentId, String title, Integer memoryVersion, Integer lastMessageIndex, Long updateTime, List<Message> messages) {
        this.contextId = contextId;
        this.userId = userId;
        this.agentId = agentId;
        this.title = title;
        this.memoryVersion = memoryVersion;
        this.lastMessageIndex = lastMessageIndex;
        this.updateTime = updateTime;
        this.messages = messages;
    }
}
