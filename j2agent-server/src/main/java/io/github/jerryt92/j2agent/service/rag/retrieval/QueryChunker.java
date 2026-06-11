package io.github.jerryt92.j2agent.service.rag.retrieval;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.config.rag.RetrieveProperties;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 将超长检索 query 切为若干段，每段不超过 Embedding 输入上限。
 * <p>重叠字符数与入库共用 {@link KnowledgeRepoProperties#getContentSegmentOverlapChars()}。</p>
 */
@Component
public class QueryChunker {

    private static final int NEWLINE_BREAK_LOOKBACK = 200;

    private final RetrieveProperties retrieveProperties;
    private final KnowledgeRepoProperties knowledgeRepoProperties;

    public QueryChunker(RetrieveProperties retrieveProperties,
                        KnowledgeRepoProperties knowledgeRepoProperties) {
        this.retrieveProperties = retrieveProperties;
        this.knowledgeRepoProperties = knowledgeRepoProperties;
    }

    /**
     * 若未超长则返回单元素列表；否则按配置切分（含重叠与最大段数）。
     */
    public List<String> chunk(String text) {
        if (text == null) {
            return Collections.emptyList();
        }
        if (text.isEmpty()) {
            return List.of("");
        }
        int maxLen = retrieveProperties.getMaxEmbeddingInputChars();
        if (maxLen <= 0 || text.length() <= maxLen) {
            return List.of(text);
        }
        int overlap = Math.max(0, knowledgeRepoProperties.getContentSegmentOverlapChars());
        int maxChunks = Math.max(1, retrieveProperties.getMaxQueryChunks());
        List<String> chunks = new ArrayList<>();
        int pos = 0;
        while (pos < text.length() && chunks.size() < maxChunks) {
            boolean lastSlot = chunks.size() == maxChunks - 1;
            if (lastSlot && pos + maxLen < text.length()) {
                int tailStart = Math.max(0, text.length() - maxLen);
                chunks.add(text.substring(tailStart));
                break;
            }
            int end = Math.min(pos + maxLen, text.length());
            end = preferBreakAtNewline(text, pos, end);
            chunks.add(text.substring(pos, end));
            if (end >= text.length()) {
                break;
            }
            int nextPos = end - overlap;
            if (nextPos <= pos) {
                nextPos = end;
            }
            pos = nextPos;
        }
        return chunks;
    }

    /**
     * 在 chunk 尾部附近优先于换行处断开。
     */
    private static int preferBreakAtNewline(String text, int start, int end) {
        if (end >= text.length() || end <= start) {
            return end;
        }
        int searchFrom = Math.max(start, end - NEWLINE_BREAK_LOOKBACK);
        int nl = text.lastIndexOf('\n', end - 1);
        if (nl >= searchFrom && nl > start) {
            return nl + 1;
        }
        return end;
    }

    /**
     * 是否会对该文本切出多段。
     */
    public boolean requiresMultiChunk(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        int maxLen = retrieveProperties.getMaxEmbeddingInputChars();
        return maxLen > 0 && text.length() > maxLen;
    }
}
