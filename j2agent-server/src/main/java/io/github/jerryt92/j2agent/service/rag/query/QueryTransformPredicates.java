package io.github.jerryt92.j2agent.service.rag.query;

import org.springframework.ai.rag.Query;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Query 改写链的条件判断（单轮跳过、短句跳过等）。
 */
public final class QueryTransformPredicates {

    private static final int REWRITE_SKIP_MAX_LENGTH = 80;
    private static final Pattern PRONOUN_OR_REFERENCE = Pattern.compile(
            "(它|这个|那个|上面|刚才|之前|这里|那里|这样|那样|how\\s+about|what\\s+about|that\\s+one|the\\s+previous)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private QueryTransformPredicates() {
    }

    /**
     * 多轮 history 存在时才需要 history 压缩。
     */
    public static boolean needsCompression(Query query) {
        if (query == null || shouldSkipTextTransforms(query)) {
            return false;
        }
        List<?> history = query.history();
        return !CollectionUtils.isEmpty(history) && history.size() > 1;
    }

    /**
     * 多模态已 enriched 时跳过；单轮短句且无指代时可跳过 query 改写。
     */
    public static boolean needsRewrite(Query query) {
        if (query == null || shouldSkipTextTransforms(query)) {
            return false;
        }
        if (Boolean.TRUE.equals(query.context().get(QueryTransformContextKeys.MULTIMODAL_ENRICHED))) {
            return false;
        }
        if (needsCompression(query)) {
            return true;
        }
        String text = query.text();
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String trimmed = text.trim();
        if (trimmed.length() > REWRITE_SKIP_MAX_LENGTH) {
            return true;
        }
        return PRONOUN_OR_REFERENCE.matcher(trimmed).find();
    }

    static boolean shouldSkipTextTransforms(Query query) {
        if (Boolean.TRUE.equals(query.context().get(QueryTransformContextKeys.SKIP_RETRIEVAL))) {
            return true;
        }
        return QueryUserMessageSupport.isImageOnlyQueryPlaceholder(query.text());
    }
}
