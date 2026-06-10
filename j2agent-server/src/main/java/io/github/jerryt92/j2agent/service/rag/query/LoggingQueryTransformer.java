package io.github.jerryt92.j2agent.service.rag.query;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;

import java.util.Objects;

/**
 * 记录 QueryTransformer 单步改写前后的 query 文本。
 */
@Slf4j
public final class LoggingQueryTransformer implements QueryTransformer {

    private final String step;
    private final QueryTransformer delegate;

    public LoggingQueryTransformer(String step, QueryTransformer delegate) {
        this.step = step;
        this.delegate = delegate;
    }

    @Override
    public Query transform(Query query) {
        String before = QueryTransformLogSupport.queryText(query);
        Query result = delegate.transform(query);
        String after = QueryTransformLogSupport.queryText(result);
        if (Objects.equals(before, after)) {
            log.info("query transform [{}]: unchanged | {}",
                    step, QueryTransformLogSupport.preview(after));
        } else {
            log.info("query transform [{}]: {} -> {}",
                    step,
                    QueryTransformLogSupport.preview(before),
                    QueryTransformLogSupport.preview(after));
        }
        return result;
    }
}
