package io.github.jerryt92.j2agent.service.rag.query;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.util.StringUtils;

import java.util.function.Predicate;

/**
 * 按条件决定是否执行委托 {@link QueryTransformer}。
 */
@Slf4j
public final class ConditionalQueryTransformer implements QueryTransformer {

    private final QueryTransformer delegate;
    private final Predicate<Query> predicate;
    private final String step;

    public ConditionalQueryTransformer(QueryTransformer delegate, Predicate<Query> predicate) {
        this(delegate, predicate, null);
    }

    public ConditionalQueryTransformer(QueryTransformer delegate, Predicate<Query> predicate, String step) {
        this.delegate = delegate;
        this.predicate = predicate;
        this.step = step;
    }

    @Override
    public Query transform(Query query) {
        if (query == null || !predicate.test(query)) {
            if (StringUtils.hasText(step)) {
                log.info("query transform [{}]: skipped", step);
            }
            return query;
        }
        return delegate.transform(query);
    }
}
