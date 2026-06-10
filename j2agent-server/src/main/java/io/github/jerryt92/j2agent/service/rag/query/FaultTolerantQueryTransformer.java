package io.github.jerryt92.j2agent.service.rag.query;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

/**
 * Query 改写失败时回退输入 query，避免阻断检索与主对话。
 */
@Slf4j
public final class FaultTolerantQueryTransformer implements QueryTransformer {

    private final String step;
    private final QueryTransformer delegate;

    public FaultTolerantQueryTransformer(String step, QueryTransformer delegate) {
        this.step = step;
        this.delegate = delegate;
    }

    @Override
    public Query transform(Query query) {
        if (query == null) {
            return null;
        }
        try {
            return delegate.transform(query);
        } catch (RuntimeException e) {
            log.warn("query transform [{}]: failed, passthrough | {}",
                    step, QueryTransformLogSupport.preview(QueryTransformLogSupport.queryText(query)), e);
            return query;
        }
    }
}
