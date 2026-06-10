package io.github.jerryt92.j2agent.service.rag.inf;

import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.service.rag.query.QueryTransformContextKeys;
import io.github.jerryt92.j2agent.service.rag.retrieval.Retriever;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 与 collection 绑定的知识库检索器抽象基类。
 */
@Slf4j
public abstract class AbstractCollectionKbRetriever implements DocumentRetriever {
    private static final String RAG_FALLBACK_TEXT = "知识库向量检索失败，请基于当前对话上下文继续回答，并在不确定时明确说明。";

    private final Retriever retriever;

    protected AbstractCollectionKbRetriever(Retriever retriever) {
        this.retriever = retriever;
    }

    /**
     * 返回当前检索器绑定的 collection 名称。
     */
    protected abstract String boundCollection();

    /**
     * 返回当前检索器绑定的分区列表，默认全分区。
     */
    protected List<String> boundPartitions() {
        return null;
    }

    /**
     * Spring AI 标准检索入口：优先读取 Query 文本，缺失时回退到历史中的最后一条用户消息。
     * <p>生成的 {@link Document} 正文为 {@link EmbeddingModel.EmbeddingsQueryItem#getAnswer()}，供排查时与接口返回的 {@code textChunk} 对齐（勿仅用 {@code outline}）。</p>
     */
    @Override
    public List<Document> retrieve(Query query) {
        if (query != null && Boolean.TRUE.equals(query.context().get(QueryTransformContextKeys.SKIP_RETRIEVAL))) {
            log.info("retrieval skipped: image-only query transform produced no valid query text");
            return Collections.emptyList();
        }
        String queryText = Retriever.resolveQueryText(query);
        if (StringUtils.isBlank(queryText)) {
            return Collections.emptyList();
        }
        if (isReactToolLoopQuery(query)) {
            return Collections.emptyList();
        }
        Retriever.RagChunksResult ragChunksResult = retriever.retrieveRagChunksResult(queryText, boundCollection(), boundPartitions());
        if (ragChunksResult.status() == Retriever.RetrievalStatus.FAILED) {
            log.warn("RAG 对话检索降级: collection={}, reason={}", boundCollection(), ragChunksResult.failureMessage());
            return List.of(buildFallbackDocument());
        }
        List<EmbeddingModel.EmbeddingsQueryItem> embeddingsQueryItems = ragChunksResult.items();
        if (embeddingsQueryItems.isEmpty()) {
            log.info("RAG 对话检索: collection={}, 分片命中数=0", boundCollection());
            return Collections.emptyList();
        }
        List<Document> documents = new ArrayList<>();
        int ordinal = 1;
        for (EmbeddingModel.EmbeddingsQueryItem item : embeddingsQueryItems) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("textChunkId", item.getTextChunkId());
            metadata.put("sourceFile", item.getSourceFile());
            metadata.put("chunkOrdinal", ordinal++);
            documents.add(Document.builder()
                    .text(item.getText())
                    .metadata(metadata)
                    .score((double) item.getScore())
                    .build());
        }
        log.info("RAG 对话检索: collection={}, 分片命中数={}, 生成Document数={}",
                boundCollection(), embeddingsQueryItems.size(), documents.size());
        return documents;
    }

    /**
     * 向模型注入检索失败提示，避免 RAG 报错直接打断会话流程。
     */
    private Document buildFallbackDocument() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("ragFallback", "milvus_retrieval_failed");
        metadata.put("sourceFile", "rag-system");
        return Document.builder()
                .text(RAG_FALLBACK_TEXT)
                .metadata(metadata)
                .score(0d)
                .build();
    }

    private static boolean isReactToolLoopQuery(Query query) {
        if (query == null || query.history() == null) {
            return false;
        }
        List<Message> history = query.history();
        int lastUserMessageIndex = -1;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof UserMessage) {
                lastUserMessageIndex = i;
                break;
            }
        }
        for (int i = lastUserMessageIndex + 1; i < history.size(); i++) {
            Message message = history.get(i);
            if (message instanceof ToolResponseMessage) {
                return true;
            }
            if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
                return true;
            }
        }
        return false;
    }
}
