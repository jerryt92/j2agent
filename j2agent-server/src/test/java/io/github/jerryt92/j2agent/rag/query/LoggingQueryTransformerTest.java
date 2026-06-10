package io.github.jerryt92.j2agent.service.rag.query;

import io.github.jerryt92.j2agent.service.rag.query.LoggingQueryTransformer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoggingQueryTransformerTest {

    @Test
    void shouldLogTransformViaDelegate() {
        QueryTransformer delegate = mock(QueryTransformer.class);
        Query input = new Query("口语 登录不了");
        Query output = new Query("用户无法登录系统");
        when(delegate.transform(input)).thenReturn(output);

        Query result = new LoggingQueryTransformer("rewrite", delegate).transform(input);

        assertEquals("用户无法登录系统", result.text());
    }
}
