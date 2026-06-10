package io.github.jerryt92.j2agent.service.rag.query;

import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FaultTolerantQueryTransformerTest {

    @Test
    void shouldPassthroughWhenDelegateFails() {
        QueryTransformer failing = mock(QueryTransformer.class);
        when(failing.transform(any())).thenThrow(new RuntimeException("timeout"));
        FaultTolerantQueryTransformer transformer = new FaultTolerantQueryTransformer("rewrite", failing);
        Query query = Query.builder().text("你是谁").build();

        Query result = transformer.transform(query);

        assertEquals("你是谁", result.text());
    }
}
