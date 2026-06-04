package io.github.jerryt92.j2agent.service.llm.reasoning;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 将 Spring AI {@link AssistantMessage}（+ 可选 {@link ChatGenerationMetadata}）
 * 拆分为「最终回答」与「深度思考」两路文本，供 {@code MessageDto.content} / {@code MessageDto.reasoningContent} 写入。
 *
 * <p>metadata 形态识别与归一化委托 {@link SpringAiReasoningMetadataAdapter}；
 * 本类负责流式增量处理（{@link ReasoningSnapshotTracker}）及 raw chunk XML 切分（{@link ThinkingStreamSplitter}）。</p>
 */
public final class AssistantMessageReasoningExtractor {

    /**
     * 流式双通道文本片段。
     *
     * @param answerDelta    最终回答增量（写入 {@code MessageDto.content}）
     * @param reasoningDelta 深度思考增量（写入 {@code MessageDto.reasoningContent}）
     */
    public record TextParts(String answerDelta, String reasoningDelta) {
    }

    private AssistantMessageReasoningExtractor() {
    }

    /**
     * 从单条流式 {@link AssistantMessage} 拆分 answer / reasoning 增量。
     *
     * @param generationMetadata 可选 Generation 层 metadata（Ollama 等）
     * @param tracker            累积 reasoning 快照转增量；每轮流式应新建实例
     */
    public static TextParts splitStreamingDelta(AssistantMessage message,
                                                ChatGenerationMetadata generationMetadata,
                                                ReasoningSnapshotTracker tracker) {
        if (message == null) {
            return null;
        }
        Map<String, Object> metadata = message.getMetadata() != null ? message.getMetadata() : Map.of();
        String text = blankToNull(message.getText());

        SpringAiReasoningMetadataAdapter.AdaptedReasoning adapted =
                SpringAiReasoningMetadataAdapter.adaptStreamChunk(message, generationMetadata);
        if (adapted == null) {
            if (SpringAiReasoningMetadataAdapter.shouldSkipChunk(metadata, message, text)) {
                return null;
            }
            String answerDelta = text;
            if (answerDelta == null) {
                return null;
            }
            return parts(answerDelta, null);
        }
        if (adapted.thinkingBlock()) {
            return parts(null, adapted.reasoningContent());
        }

        String reasoningDelta = adapted.reasoningContent();
        if (adapted.cumulative() && tracker != null && reasoningDelta != null) {
            reasoningDelta = tracker.toDelta(reasoningDelta);
        }
        String answerDelta = text;
        if (answerDelta == null && reasoningDelta == null) {
            return null;
        }
        return parts(answerDelta, reasoningDelta);
    }

    /**
     * Graph 未包装为 {@link AssistantMessage} 时，从 raw chunk 字符串拆分（支持 {@code <thinking>} XML 标签）。
     */
    public static TextParts splitRawChunk(String chunk, ThinkingStreamSplitter splitter) {
        if (!StringUtils.hasText(chunk) || splitter == null) {
            return null;
        }
        ThinkingStreamSplitter.ChunkSplit split = splitter.append(chunk);
        if (split.reasoningDelta() == null && split.answerDelta() == null) {
            return null;
        }
        return parts(split.answerDelta(), split.reasoningDelta());
    }

    /** 提取完整深度思考文本（历史回放 / 持久化）。 */
    public static String extractFullReasoning(AssistantMessage message) {
        return extractFullReasoning(message, null);
    }

    /**
     * 提取完整深度思考文本（历史回放 / 持久化）。
     *
     * @param generationMetadata 可选 Generation 层 metadata
     */
    public static String extractFullReasoning(AssistantMessage message,
                                              ChatGenerationMetadata generationMetadata) {
        return SpringAiReasoningMetadataAdapter.adaptFullReasoning(message, generationMetadata);
    }

    /** 当前消息是否携带深度思考信号（供 {@code ReloadableRoutingChatModel} 过滤空 chunk）。 */
    public static boolean hasReasoningSignal(AssistantMessage message,
                                             ChatGenerationMetadata generationMetadata) {
        return SpringAiReasoningMetadataAdapter.hasReasoningMetadata(message, generationMetadata);
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static TextParts parts(String answerDelta, String reasoningDelta) {
        return new TextParts(answerDelta, reasoningDelta);
    }
}
