package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 将逻辑 text_chunk 正文按滑动窗口切分为 content_segment 嵌入片段。
 */
@Component
public class ContentSegmentChunker {

    private static final int NEWLINE_BREAK_LOOKBACK = 200;

    private final KnowledgeRepoProperties knowledgeRepoProperties;

    public ContentSegmentChunker(KnowledgeRepoProperties knowledgeRepoProperties) {
        this.knowledgeRepoProperties = knowledgeRepoProperties;
    }

    /**
     * 若未超长则返回单元素列表；否则按配置切分（含重叠，覆盖全文）。
     */
    public List<String> chunk(String text) {
        if (text == null) {
            return Collections.emptyList();
        }
        if (text.isEmpty()) {
            return List.of("");
        }
        int maxLen = Math.max(1, knowledgeRepoProperties.getContentSegmentChars());
        if (text.length() <= maxLen) {
            return List.of(text);
        }
        int overlap = Math.max(0, knowledgeRepoProperties.getContentSegmentOverlapChars());
        List<String> chunks = new ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
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
}
