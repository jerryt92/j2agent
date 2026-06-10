package io.github.jerryt92.j2agent.service.rag.query;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryTransformPredicatesTest {

    @Test
    void shouldSkipCompressionForSingleTurn() {
        Query query = Query.builder()
                .text("你是谁")
                .history(List.of(UserMessage.builder().text("你是谁").build()))
                .build();

        assertFalse(QueryTransformPredicates.needsCompression(query));
    }

    @Test
    void shouldRunCompressionForMultiTurn() {
        Query query = Query.builder()
                .text("它怎么配置")
                .history(List.of(
                        UserMessage.builder().text("介绍一下产品").build(),
                        UserMessage.builder().text("好的").build(),
                        UserMessage.builder().text("它怎么配置").build()))
                .build();

        assertTrue(QueryTransformPredicates.needsCompression(query));
    }

    @Test
    void shouldSkipRewriteForShortStandaloneQuery() {
        Query query = Query.builder()
                .text("你是谁")
                .history(List.of(UserMessage.builder().text("你是谁").build()))
                .build();

        assertFalse(QueryTransformPredicates.needsRewrite(query));
    }

    @Test
    void shouldRunRewriteWhenQueryHasPronounReference() {
        Query query = Query.builder()
                .text("它呢")
                .history(List.of(UserMessage.builder().text("它呢").build()))
                .build();

        assertTrue(QueryTransformPredicates.needsRewrite(query));
    }

    @Test
    void shouldSkipTextTransformsWhenSkipRetrievalFlagSet() {
        Query query = Query.builder()
                .text(QueryUserMessageSupport.IMAGE_ONLY_QUERY_PLACEHOLDER)
                .context(java.util.Map.of(QueryTransformContextKeys.SKIP_RETRIEVAL, true))
                .build();

        assertFalse(QueryTransformPredicates.needsRewrite(query));
        assertFalse(QueryTransformPredicates.needsCompression(query));
    }
}
