package io.github.jerryt92.j2agent.service.rag.query;

import org.springframework.ai.rag.Query;
import org.springframework.util.StringUtils;

/**
 * Query 改写日志用的文本预览与规范化。
 */
public final class QueryTransformLogSupport {

    private static final int PREVIEW_MAX_CHARS = 160;

    private QueryTransformLogSupport() {
    }

    public static String queryText(Query query) {
        return query == null ? "" : nullToEmpty(query.text());
    }

    public static String preview(String text) {
        String normalized = displayText(text);
        if (normalized.isEmpty()) {
            return "(empty)";
        }
        if (normalized.length() <= PREVIEW_MAX_CHARS) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_MAX_CHARS) + "...";
    }

    public static String displayText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String trimmed = text.trim();
        if (QueryUserMessageSupport.isImageOnlyQueryPlaceholder(trimmed)) {
            return "(image-only)";
        }
        return trimmed.replace('\n', ' ');
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }
}
