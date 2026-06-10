package io.github.jerryt92.j2agent.service.rag.query;

import io.github.jerryt92.j2agent.service.rag.query.ConditionalQueryTransformer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConditionalQueryTransformerTest {

    @Test
    void shouldSkipDelegateWhenPredicateFalse() {
        QueryTransformer delegate = mock(QueryTransformer.class);
        ConditionalQueryTransformer transformer = new ConditionalQueryTransformer(delegate, query -> false);
        Query query = new Query("hello");

        Query result = transformer.transform(query);

        assertSame(query, result);
        verify(delegate, never()).transform(query);
    }

    @Test
    void shouldRunDelegateWhenPredicateTrue() {
        QueryTransformer delegate = mock(QueryTransformer.class);
        ConditionalQueryTransformer transformer = new ConditionalQueryTransformer(delegate, query -> true);
        Query query = new Query("hello");
        Query transformed = new Query("rewritten");
        when(delegate.transform(query)).thenReturn(transformed);

        Query result = transformer.transform(query);

        assertSame(transformed, result);
        verify(delegate).transform(query);
    }

    @Test
    void shouldSkipWhenMultimodalEnrichedFlagSet() {
        QueryTransformer delegate = mock(QueryTransformer.class);
        ConditionalQueryTransformer transformer = new ConditionalQueryTransformer(
                delegate,
                q -> !Boolean.TRUE.equals(q.context().get(QueryTransformContextKeys.MULTIMODAL_ENRICHED)),
                "compression");
        Query query = Query.builder()
                .text("from image")
                .context(Map.of(QueryTransformContextKeys.MULTIMODAL_ENRICHED, true))
                .build();

        Query result = transformer.transform(query);

        assertSame(query, result);
        verify(delegate, never()).transform(query);
    }
}
