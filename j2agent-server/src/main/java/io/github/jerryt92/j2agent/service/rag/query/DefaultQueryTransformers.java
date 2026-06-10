package io.github.jerryt92.j2agent.service.rag.query;

import io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentService;
import io.github.jerryt92.j2agent.service.llm.LlmSyncService;
import io.github.jerryt92.j2agent.service.rag.query.ConditionalQueryTransformer;
import io.github.jerryt92.j2agent.service.rag.query.FaultTolerantQueryTransformer;
import io.github.jerryt92.j2agent.service.rag.query.LoggingQueryTransformer;
import io.github.jerryt92.j2agent.service.rag.query.QueryTransformContextKeys;
import io.github.jerryt92.j2agent.service.rag.query.QueryTransformPredicates;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 构建默认 Query 改写链（Multimodal → Compression → Rewrite）。
 */
@Component
public class DefaultQueryTransformers {

    private static final int QUERY_TRANSFORM_MAX_TOKENS = 512;

    private final LlmSyncService llmSyncService;

    public DefaultQueryTransformers(LlmSyncService llmSyncService) {
        this.llmSyncService = llmSyncService;
    }

    public QueryTransformer[] build(ChatAttachmentService chatAttachmentService) {
        ChatClient.Builder rewriteClient = llmSyncService.chatClientBuilder()
                .defaultOptions(ChatOptions.builder()
                        .temperature(0.0)
                        .maxTokens(QUERY_TRANSFORM_MAX_TOKENS)
                        .build());

        QueryTransformer multimodal = new LoggingQueryTransformer("multimodal",
                MultimodalQueryTransformer.builder()
                        .llmSyncService(llmSyncService)
                        .chatAttachmentService(chatAttachmentService)
                        .markEnrichedForSkip(true)
                        .build());

        Predicate<org.springframework.ai.rag.Query> runTextTransforms = query ->
                !QueryTransformPredicates.shouldSkipTextTransforms(query)
                && !Boolean.TRUE.equals(query.context().get(QueryTransformContextKeys.MULTIMODAL_ENRICHED));

        List<QueryTransformer> transformers = new ArrayList<>();
        transformers.add(multimodal);
        transformers.add(new ConditionalQueryTransformer(
                new LoggingQueryTransformer("compression",
                        new FaultTolerantQueryTransformer("compression",
                                CompressionQueryTransformer.builder().chatClientBuilder(rewriteClient).build())),
                query -> runTextTransforms.test(query) && QueryTransformPredicates.needsCompression(query),
                "compression"));
        transformers.add(new ConditionalQueryTransformer(
                new LoggingQueryTransformer("rewrite",
                        new FaultTolerantQueryTransformer("rewrite",
                                RewriteQueryTransformer.builder().chatClientBuilder(rewriteClient).build())),
                query -> runTextTransforms.test(query) && QueryTransformPredicates.needsRewrite(query),
                "rewrite"));

        return transformers.toArray(new QueryTransformer[0]);
    }
}
