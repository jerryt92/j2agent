package io.github.jerryt92.j2agent.service.rag.query;

/**
 * Query 改写链在 {@link org.springframework.ai.rag.Query#context()} 中使用的键。
 */
public final class QueryTransformContextKeys {

    /**
     * 多模态步骤已基于图片产出检索文本时为 {@code true}。
     */
    public static final String MULTIMODAL_ENRICHED = "rag.query.multimodalEnriched";

    /**
     * 纯图视觉改写失败或未产出时为 {@code true}，检索器应跳过检索而非用占位符检索。
     */
    public static final String SKIP_RETRIEVAL = "rag.query.skipRetrieval";

    private QueryTransformContextKeys() {
    }
}
