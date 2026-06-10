package io.github.jerryt92.j2agent.service.rag.query;

import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentService;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 {@link Query} 解析最后一条用户消息及其多模态附件。
 */
@Slf4j
public final class QueryUserMessageSupport {

    /**
     * 纯图消息在检索 Advisor 构建 {@link Query} 前使用的不可见占位 text（Spring AI {@link Query} 要求 text 非空）。
     * 对用户展示为空白；{@link MultimodalQueryTransformer} 会将其视为无用户文字。
     */
    public static final String IMAGE_ONLY_QUERY_PLACEHOLDER = "\u200B";

    private QueryUserMessageSupport() {
    }

    public static boolean isImageOnlyQueryPlaceholder(String text) {
        return IMAGE_ONLY_QUERY_PLACEHOLDER.equals(text != null ? text.trim() : null);
    }

    /**
     * 纯图且无用户文字时，为最后一条 {@link UserMessage} 注入占位 text，避免检索 Advisor 构建 Query 失败。
     */
    public static ChatClientRequest patchRequestForImageOnlyRag(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return request;
        }
        UserMessage userMessage = request.prompt().getUserMessage();
        if (userMessage == null || StringUtils.hasText(userMessage.getText()) || !hasRetrievalInput(userMessage)) {
            return request;
        }
        log.info("query transform: patching image-only user message with invisible placeholder for Query.text");
        UserMessage patched = UserMessage.builder()
                .text(IMAGE_ONLY_QUERY_PLACEHOLDER)
                .metadata(userMessage.getMetadata())
                .media(userMessage.getMedia())
                .build();
        List<Message> instructions = new ArrayList<>(request.prompt().getInstructions());
        for (int i = instructions.size() - 1; i >= 0; i--) {
            if (instructions.get(i) instanceof UserMessage) {
                instructions.set(i, patched);
                break;
            }
        }
        return request.mutate()
                .prompt(request.prompt().mutate().messages(instructions).build())
                .build();
    }

    public static UserMessage resolveLastUserMessage(Query query) {
        return resolveLastUserMessage(query, null);
    }

    /**
     * 解析最后一条用户消息；若仅有 metadata attachments 而无 {@link UserMessage#getMedia()}，尝试加载 Media。
     */
    public static UserMessage resolveLastUserMessage(Query query, ChatAttachmentService attachmentService) {
        UserMessage userMessage = resolveLastUserMessageWithoutMediaFallback(query);
        return enrichUserMessageMedia(userMessage, attachmentService);
    }

    private static UserMessage resolveLastUserMessageWithoutMediaFallback(Query query) {
        if (query == null) {
            return null;
        }
        List<Message> history = query.history();
        if (history != null && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                Message message = history.get(i);
                if (message instanceof UserMessage userMessage) {
                    return userMessage;
                }
            }
        }
        String text = query.text();
        if (StringUtils.hasText(text)) {
            return UserMessage.builder().text(text).build();
        }
        return null;
    }

    static UserMessage enrichUserMessageMedia(UserMessage userMessage, ChatAttachmentService attachmentService) {
        if (userMessage == null || attachmentService == null || !CollectionUtils.isEmpty(userMessage.getMedia())) {
            return userMessage;
        }
        List<ChatAttachmentDto> attachments = ChatMemoryMessageCodec.attachmentsFromUserMessage(userMessage);
        if (attachments.isEmpty()) {
            return userMessage;
        }
        try {
            return UserMessage.builder()
                    .text(userMessage.getText())
                    .metadata(userMessage.getMetadata())
                    .media(attachmentService.toMedia(attachments))
                    .build();
        } catch (RuntimeException e) {
            log.warn("query transform: failed to load media from attachments metadata", e);
            return userMessage;
        }
    }

    public static String resolveUserText(Query query, UserMessage userMessage) {
        String text = null;
        if (userMessage != null && StringUtils.hasText(userMessage.getText())) {
            text = userMessage.getText().trim();
        } else if (query != null && StringUtils.hasText(query.text())) {
            text = query.text().trim();
        }
        if (text == null || isImageOnlyQueryPlaceholder(text)) {
            return "";
        }
        return text;
    }

    public static boolean hasRetrievalInput(Query query) {
        return hasRetrievalInput(resolveLastUserMessage(query), query != null ? query.text() : null);
    }

    public static boolean hasRetrievalInput(UserMessage userMessage) {
        return hasRetrievalInput(userMessage, userMessage != null ? userMessage.getText() : null);
    }

    private static boolean hasRetrievalInput(UserMessage userMessage, String fallbackText) {
        if (userMessage != null) {
            if (StringUtils.hasText(userMessage.getText()) && !isImageOnlyQueryPlaceholder(userMessage.getText())) {
                return true;
            }
            if (!CollectionUtils.isEmpty(userMessage.getMedia())) {
                return true;
            }
            if (!ChatMemoryMessageCodec.attachmentsFromUserMessage(userMessage).isEmpty()) {
                return true;
            }
        }
        return StringUtils.hasText(fallbackText) && !isImageOnlyQueryPlaceholder(fallbackText);
    }

    public static Query withTextAndContext(Query query, String text, Map<String, Object> contextEntries) {
        Map<String, Object> mergedContext = new HashMap<>();
        if (query.context() != null) {
            mergedContext.putAll(query.context());
        }
        if (contextEntries != null) {
            mergedContext.putAll(contextEntries);
        }
        return Query.builder()
                .text(text)
                .history(query.history())
                .context(mergedContext)
                .build();
    }
}
