package io.github.jerryt92.j2agent.service.rag.retrieval;

import io.github.jerryt92.j2agent.rag.AbstractCollectionKbRetriever;
import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.model.KnowledgeRetrieveItemDto;
import io.github.jerryt92.j2agent.model.Translator;
import io.github.jerryt92.j2agent.service.PropertiesService;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMaintenanceCoordinator;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import io.github.jerryt92.j2agent.utils.MathCalculatorUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletionException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用检索引擎：负责检索参数解析、混合检索调用与分数归一化。
 */
@Slf4j
@Service
public class Retriever {

    /**
     * 检索日志中查询文本预览最大字符数，避免日志过长
     */
    private static final int QUERY_PREVIEW_MAX_CHARS = 200;

    private final EmbeddingService embeddingService;
    private final VectorDatabaseService vectorDatabaseService;
    private final PropertiesService propertiesService;
    private final QueryChunker queryChunker;
    private final KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator;

    /**
     * RAG 检索结果状态：区分正常、空命中与向量库失败降级。
     */
    public enum RetrievalStatus {
        SUCCESS,
        EMPTY,
        FAILED
    }

    public Retriever(EmbeddingService embeddingService,
                     VectorDatabaseService vectorDatabaseService,
                     PropertiesService propertiesService,
                     QueryChunker queryChunker,
                     KnowledgeRepoMaintenanceCoordinator maintenanceCoordinator) {
        this.embeddingService = embeddingService;
        this.vectorDatabaseService = vectorDatabaseService;
        this.propertiesService = propertiesService;
        this.queryChunker = queryChunker;
        this.maintenanceCoordinator = maintenanceCoordinator;
    }

    /**
     * 解析 Spring AI Query 的实际检索词，兼容部分场景仅通过 history 传入用户问题。
     */
    public static String resolveQueryText(Query query) {
        if (query == null) {
            return null;
        }
        if (StringUtils.isNotBlank(query.text())) {
            return query.text();
        }
        List<Message> history = query.history();
        if (history == null || history.isEmpty()) {
            return null;
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            Message message = history.get(i);
            if (message instanceof UserMessage userMessage) {
                return userMessage.getText();
            }
        }
        return null;
    }

    /**
     * 基于指定 collection 与分区执行相似度检索并做归一化与阈值过滤。
     */
    public List<EmbeddingModel.EmbeddingsQueryItem> similarityRetrieval(String queryText,
                                                                        KnowledgeRetrieveItemDto.MetricTypeEnum metricType,
                                                                        int topK,
                                                                        String metricScoreCompareExpr,
                                                                        String collection,
                                                                        List<String> partitionNames) {
        SearchOutcome outcome = searchAndNormalize(queryText, metricType, topK, collection, partitionNames, "RAG检索");
        List<EmbeddingModel.EmbeddingsQueryItem> result = new ArrayList<>();
        for (EmbeddingModel.EmbeddingsQueryItem item : outcome.items()) {
            String calculateExpressionResult = MathCalculatorUtil.calculateExpression(item.getScore() + metricScoreCompareExpr);
            if (StringUtils.isBlank(metricScoreCompareExpr) || "true".equals(calculateExpressionResult)) {
                result.add(item);
            }
        }
        log.info("RAG检索结果: collection={}, 向量库命中数={}, 阈值过滤后条数={}, queryChunks={}",
                collection, outcome.rawHitCount(), result.size(), outcome.queryChunks());
        return result;
    }

    /**
     * 混合检索核心：超长 query 多段向量化与融合，返回已归一化、条数不超过 topK 的命中列表。
     */
    private SearchOutcome searchAndNormalize(String queryText,
                                             KnowledgeRetrieveItemDto.MetricTypeEnum metricType,
                                             int topK,
                                             String collection,
                                             List<String> partitionNames,
                                             String logPrefix) {
        if (StringUtils.isBlank(queryText) || StringUtils.isBlank(collection)) {
            return new SearchOutcome(Collections.emptyList(), 0, 1, RetrievalStatus.EMPTY, null);
        }
        List<String> chunks = queryChunker.chunk(queryText);
        if (chunks.isEmpty()) {
            return new SearchOutcome(Collections.emptyList(), 0, 0, RetrievalStatus.EMPTY, null);
        }
        float[] weights = loadWeights();
        List<String> effectivePartitions = partitionNames == null || partitionNames.isEmpty() ? null : partitionNames;
        String metricTypeName = metricType == null ? null : metricType.name();
        int queryChunks = chunks.size();
        log.info("{}请求: collection={}, topK={}, metricType={}, denseWeight={}, sparseWeight={}, partitions={}, queryChars={}, queryChunks={}, queryPreview={}",
                logPrefix, collection, topK, metricTypeName, weights[0], weights[1], effectivePartitions,
                queryText.length(), queryChunks, previewForLog(queryText));

        SearchExecutionResult executionResult;
        if (queryChunks == 1) {
            executionResult = hybridSearchOneChunk(chunks.getFirst(), collection, topK, metricTypeName, weights, effectivePartitions);
        } else {
            executionResult = hybridSearchMultiChunk(chunks, collection, topK, metricTypeName, weights, effectivePartitions);
        }
        List<EmbeddingModel.EmbeddingsQueryItem> rawHits = executionResult.items();
        int rawHitCount = rawHits == null ? 0 : rawHits.size();
        if (executionResult.failed()) {
            return new SearchOutcome(Collections.emptyList(), 0, queryChunks, RetrievalStatus.FAILED, executionResult.failureMessage());
        }
        if (rawHits == null || rawHits.isEmpty()) {
            log.warn("{}: 查询向量化或混合检索无结果, collection={}", logPrefix, collection);
            return new SearchOutcome(Collections.emptyList(), 0, queryChunks, RetrievalStatus.EMPTY, null);
        }
        boolean multiChunk = queryChunks > 1;
        SearchExecutionResult normalizeResult = normalizeHitScores(rawHits, metricType, weights, collection, effectivePartitions, multiChunk, chunks.getFirst(), metricTypeName);
        if (normalizeResult.failed()) {
            return new SearchOutcome(Collections.emptyList(), 0, queryChunks, RetrievalStatus.FAILED, normalizeResult.failureMessage());
        }
        return new SearchOutcome(rawHits, rawHitCount, queryChunks, RetrievalStatus.SUCCESS, null);
    }

    private float[] loadWeights() {
        RetrieverParams params = RetrieverParams.from(propertiesService);
        return new float[]{params.denseWeight(), params.sparseWeight()};
    }

    private SearchExecutionResult hybridSearchOneChunk(String chunkText,
                                                       String collection,
                                                       int topK,
                                                       String metricTypeName,
                                                       float[] weights,
                                                       List<String> partitionNames) {
        EmbeddingModel.EmbeddingsResponse embed = safeEmbed(new EmbeddingModel.EmbeddingsRequest().setInput(List.of(chunkText)));
        if (embed == null || embed.getData() == null || embed.getData().isEmpty()) {
            return SearchExecutionResult.success(Collections.emptyList());
        }
        return safeHybridRetrieval(collection, chunkText, embed.getData().getFirst().getEmbeddings(),
                topK, metricTypeName, weights, partitionNames, "singleChunk");
    }

    private SearchExecutionResult hybridSearchMultiChunk(List<String> chunks,
                                                         String collection,
                                                         int topK,
                                                         String metricTypeName,
                                                         float[] weights,
                                                         List<String> partitionNames) {
        EmbeddingModel.EmbeddingsResponse embed = safeEmbed(new EmbeddingModel.EmbeddingsRequest().setInput(chunks));
        if (embed == null || embed.getData() == null || embed.getData().size() != chunks.size()) {
            log.warn("多段 query 向量化数量不匹配: chunks={}, returned={}",
                    chunks.size(), embed == null || embed.getData() == null ? 0 : embed.getData().size());
            return SearchExecutionResult.success(Collections.emptyList());
        }
        int perChunkTopK = Math.max(topK, (int) Math.ceil(topK * 1.5 / chunks.size()));
        List<EmbeddingModel.EmbeddingsQueryItem> allHits = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = embed.getData().get(i).getEmbeddings();
            if (vector == null || vector.length == 0) {
                continue;
            }
            SearchExecutionResult chunkResult = safeHybridRetrieval(collection, chunks.get(i), vector,
                    perChunkTopK, metricTypeName, weights, partitionNames, "multiChunk[" + i + "]");
            if (chunkResult.failed()) {
                return chunkResult;
            }
            if (chunkResult.items() != null) {
                allHits.addAll(chunkResult.items());
            }
        }
        return SearchExecutionResult.success(mergeChunkHits(allHits, topK, weights[0], weights[1]));
    }

    /**
     * 按 segment 去重，保留原始混合分最高的一条，再取 topK。
     */
    private List<EmbeddingModel.EmbeddingsQueryItem> mergeChunkHits(List<EmbeddingModel.EmbeddingsQueryItem> hits,
                                                                    int topK,
                                                                    float denseWeight,
                                                                    float sparseWeight) {
        Map<String, EmbeddingModel.EmbeddingsQueryItem> bestByKey = new LinkedHashMap<>();
        for (EmbeddingModel.EmbeddingsQueryItem item : hits) {
            if (item == null) {
                continue;
            }
            String key = dedupeKey(item);
            EmbeddingModel.EmbeddingsQueryItem existing = bestByKey.get(key);
            if (existing == null || rawHybridScore(item, denseWeight, sparseWeight) > rawHybridScore(existing, denseWeight, sparseWeight)) {
                bestByKey.put(key, item);
            }
        }
        List<EmbeddingModel.EmbeddingsQueryItem> merged = new ArrayList<>(bestByKey.values());
        merged.sort((a, b) -> Float.compare(
                rawHybridScore(b, denseWeight, sparseWeight),
                rawHybridScore(a, denseWeight, sparseWeight)));
        if (merged.size() > topK) {
            return new ArrayList<>(merged.subList(0, topK));
        }
        return merged;
    }

    private static String dedupeKey(EmbeddingModel.EmbeddingsQueryItem item) {
        if (StringUtils.isNotBlank(item.getHash())) {
            return item.getHash();
        }
        if (StringUtils.isNotBlank(item.getTextChunkId())) {
            return item.getTextChunkId();
        }
        return (item.getSourceFile() != null ? item.getSourceFile() : "")
                + "|" + (item.getQuestion() != null ? item.getQuestion() : "");
    }

    private static float rawHybridScore(EmbeddingModel.EmbeddingsQueryItem item, float denseWeight, float sparseWeight) {
        if (item.getHybridScore() != null) {
            return item.getHybridScore();
        }
        float dense = item.getDenseScore() != null ? item.getDenseScore() : 0f;
        float sparse = item.getSparseScore() != null ? item.getSparseScore() : 0f;
        return dense * denseWeight + sparse * sparseWeight;
    }

    private SearchExecutionResult normalizeHitScores(List<EmbeddingModel.EmbeddingsQueryItem> hits,
                                                     KnowledgeRetrieveItemDto.MetricTypeEnum metricType,
                                                     float[] weights,
                                                     String collection,
                                                     List<String> partitionNames,
                                                     boolean multiChunk,
                                                     String referenceFallbackText,
                                                     String metricTypeName) {
        Float denseScoreMax;
        Float sparseScoreMax;
        if (multiChunk) {
            denseScoreMax = resolveMaxDenseScore(hits);
            sparseScoreMax = resolveMaxSparseScore(hits);
        } else {
            String referenceText = resolveReferenceText(hits, referenceFallbackText);
            ReferenceMaxScoresResult referenceMaxScoresResult = resolveReferenceMaxScores(referenceText, metricTypeName, weights, collection, partitionNames);
            if (referenceMaxScoresResult.failed()) {
                return SearchExecutionResult.failed(referenceMaxScoresResult.failureMessage());
            }
            Float[] referenceMaxScores = referenceMaxScoresResult.maxScores();
            denseScoreMax = referenceMaxScores[0];
            sparseScoreMax = resolveMaxSparseScore(hits);
        }
        Float l2MaxScore = metricType == KnowledgeRetrieveItemDto.MetricTypeEnum.L2
                ? resolveMaxDenseScore(hits)
                : null;
        for (EmbeddingModel.EmbeddingsQueryItem item : hits) {
            float densePercent = normalizeDenseScore(item.getDenseScore(), metricType, denseScoreMax, l2MaxScore);
            float sparsePercent = normalizeSparseScore(item.getSparseScore(), sparseScoreMax);
            float hybridPercent = clampPercent(densePercent * weights[0] + sparsePercent * weights[1]);
            item.setDenseScore(densePercent)
                    .setSparseScore(sparsePercent)
                    .setHybridScore(hybridPercent)
                    .setScore(hybridPercent);
        }
        return SearchExecutionResult.success(hits);
    }

    private record SearchOutcome(List<EmbeddingModel.EmbeddingsQueryItem> items,
                                 int rawHitCount,
                                 int queryChunks,
                                 RetrievalStatus status,
                                 String failureMessage) {
    }

    /**
     * RAG 检索对外返回体：携带命中列表与失败标志，供上游判断是否注入降级提示。
     */
    public record RagChunksResult(List<EmbeddingModel.EmbeddingsQueryItem> items,
                                  RetrievalStatus status,
                                  String failureMessage) {
    }

    private record SearchExecutionResult(List<EmbeddingModel.EmbeddingsQueryItem> items, boolean failed, String failureMessage) {
        private static SearchExecutionResult success(List<EmbeddingModel.EmbeddingsQueryItem> items) {
            return new SearchExecutionResult(items == null ? Collections.emptyList() : items, false, null);
        }

        private static SearchExecutionResult failed(String failureMessage) {
            return new SearchExecutionResult(Collections.emptyList(), true, failureMessage);
        }
    }

    private record ReferenceMaxScoresResult(Float[] maxScores, boolean failed, String failureMessage) {
        private static ReferenceMaxScoresResult success(Float[] maxScores) {
            return new ReferenceMaxScoresResult(maxScores, false, null);
        }

        private static ReferenceMaxScoresResult failed(String failureMessage) {
            return new ReferenceMaxScoresResult(new Float[]{null, null}, true, failureMessage);
        }
    }

    /**
     * 读取配置后在指定 collection 上执行文档检索路径所需的混合检索。
     * <p>供 {@link AbstractCollectionKbRetriever} 等 Spring AI {@code DocumentRetriever} 使用；结果已按阈值过滤。</p>
     */
    public List<EmbeddingModel.EmbeddingsQueryItem> retrieveRagChunks(String queryText, String collection, List<String> partitionNames) {
        return retrieveRagChunksResult(queryText, collection, partitionNames).items();
    }

    /**
     * 返回带状态的 RAG 检索结果，供上游识别 Milvus 失败并注入降级提示。
     */
    public RagChunksResult retrieveRagChunksResult(String queryText, String collection, List<String> partitionNames) {
        if (StringUtils.isBlank(collection)) {
            return new RagChunksResult(Collections.emptyList(), RetrievalStatus.EMPTY, null);
        }
        if (maintenanceCoordinator.isExclusiveSyncActive()) {
            return new RagChunksResult(Collections.emptyList(), RetrievalStatus.FAILED,
                    "知识库维护重建进行中，检索暂时不可用");
        }
        RetrieverParams params = RetrieverParams.from(propertiesService);
        SearchOutcome outcome = searchAndNormalize(
                queryText, params.metricType(), params.topK(), collection, partitionNames, "RAG检索");
        return new RagChunksResult(outcome.items(), outcome.status(), outcome.failureMessage());
    }

    /**
     * 按指定 collection 进行命中测试检索。
     */
    public List<KnowledgeRetrieveItemDto> retrieveKnowledge(String queryText, Integer topK, String collection) {
        return retrieveKnowledge(queryText, topK, collection, null);
    }

    /**
     * 按指定 collection 与可选 Milvus 分区列表检索。
     *
     * @param partitionNames 非空时仅在对应分区内检索；null 或空表示全 collection。
     */
    public List<KnowledgeRetrieveItemDto> retrieveKnowledge(String queryText, Integer topK, String collection, List<String> partitionNames) {
        List<KnowledgeRetrieveItemDto> retrieveResult = new ArrayList<>();
        if (StringUtils.isBlank(queryText) || StringUtils.isBlank(collection)) {
            return retrieveResult;
        }
        if (maintenanceCoordinator.isExclusiveSyncActive()) {
            log.warn("知识库维护重建进行中，命中测试返回空结果: collection={}", collection);
            return retrieveResult;
        }
        RetrieverParams params = RetrieverParams.from(propertiesService);
        int safeTopK = topK == null ? params.topK() : topK;
        KnowledgeRetrieveItemDto.MetricTypeEnum metricType = params.metricType();
        SearchOutcome outcome = searchAndNormalize(queryText, metricType, safeTopK, collection, partitionNames, "RAG知识检索");
        for (EmbeddingModel.EmbeddingsQueryItem item : outcome.items()) {
            String calculateExpressionResult = MathCalculatorUtil.calculateExpression(item.getScore() + params.metricScoreCompareExpr());
            retrieveResult.add(Translator.translateToEmbeddingsQueryItemDto(
                    item, !"true".equals(calculateExpressionResult), metricType, embeddingService.getDimension()));
        }
        log.info("RAG知识检索结果: collection={}, 向量库命中数={}, 返回DTO条数={}, queryChunks={}",
                collection, outcome.rawHitCount(), retrieveResult.size(), outcome.queryChunks());
        return retrieveResult;
    }

    /**
     * 日志中截断查询文本，避免过长占用磁盘。
     */
    private static String previewForLog(String queryText) {
        if (queryText == null) {
            return "";
        }
        String normalized = queryText.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= QUERY_PREVIEW_MAX_CHARS) {
            return normalized;
        }
        return normalized.substring(0, QUERY_PREVIEW_MAX_CHARS) + "...";
    }

    /**
     * 解析用于参考归一化的文本，优先命中结果中的答案文本。
     */
    private String resolveReferenceText(List<EmbeddingModel.EmbeddingsQueryItem> embeddingsQueryItems, String fallbackText) {
        if (embeddingsQueryItems == null || embeddingsQueryItems.isEmpty()) {
            return fallbackText;
        }
        EmbeddingModel.EmbeddingsQueryItem firstItem = embeddingsQueryItems.get(0);
        if (firstItem != null && StringUtils.isNotBlank(firstItem.getAnswer())) {
            return firstItem.getAnswer();
        }
        if (firstItem != null && StringUtils.isNotBlank(firstItem.getText())) {
            return firstItem.getText();
        }
        return fallbackText;
    }

    /**
     * 以参考文本计算用于归一化的稠密/稀疏最大分。
     */
    private ReferenceMaxScoresResult resolveReferenceMaxScores(String referenceText, String metricType, float[] weights, String collection, List<String> partitionNames) {
        if (StringUtils.isBlank(referenceText)) {
            return ReferenceMaxScoresResult.success(new Float[]{null, null});
        }
        EmbeddingModel.EmbeddingsRequest embeddingsRequest = new EmbeddingModel.EmbeddingsRequest()
                .setInput(Collections.singletonList(referenceText));
        EmbeddingModel.EmbeddingsResponse embed = safeEmbed(embeddingsRequest);
        if (embed == null || embed.getData() == null || embed.getData().isEmpty()) {
            return ReferenceMaxScoresResult.success(new Float[]{null, null});
        }
        if (StringUtils.isBlank(collection)) {
            return ReferenceMaxScoresResult.success(new Float[]{null, null});
        }
        SearchExecutionResult referenceSearchResult = safeHybridRetrieval(
                collection, referenceText, embed.getData().getFirst().getEmbeddings(),
                1, metricType, weights, partitionNames, "referenceScore");
        if (referenceSearchResult.failed()) {
            return ReferenceMaxScoresResult.failed(referenceSearchResult.failureMessage());
        }
        List<EmbeddingModel.EmbeddingsQueryItem> referenceItems = referenceSearchResult.items();
        if (referenceItems.isEmpty()) {
            return ReferenceMaxScoresResult.success(new Float[]{null, null});
        }
        EmbeddingModel.EmbeddingsQueryItem referenceItem = referenceItems.getFirst();
        return ReferenceMaxScoresResult.success(new Float[]{referenceItem.getDenseScore(), referenceItem.getSparseScore()});
    }

    /**
     * 对 Milvus 检索异常统一做日志与失败标记，避免异常向上打断流式链路。
     */
    private SearchExecutionResult safeHybridRetrieval(String collection,
                                                      String queryText,
                                                      float[] vector,
                                                      int topK,
                                                      String metricTypeName,
                                                      float[] weights,
                                                      List<String> partitionNames,
                                                      String stage) {
        try {
            List<EmbeddingModel.EmbeddingsQueryItem> hits = vectorDatabaseService.hybridRetrieval(
                    collection, queryText, vector, topK, metricTypeName, weights[0], weights[1], partitionNames);
            return SearchExecutionResult.success(hits == null ? Collections.emptyList() : hits);
        } catch (RuntimeException e) {
            if (isMilvusRetrievalException(e)) {
                String failureMessage = "知识库向量检索失败（Milvus 不可用）";
                log.error("Milvus混合检索失败: stage={}, collection={}, topK={}, metricType={}, partitions={}, queryPreview={}, reason={}",
                        stage, collection, topK, metricTypeName, partitionNames, previewForLog(queryText), rootCauseMessage(e), e);
                return SearchExecutionResult.failed(failureMessage);
            }
            throw e;
        }
    }

    private boolean isMilvusRetrievalException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            if (className.contains("MilvusClientException")) {
                return true;
            }
            if (!(current instanceof CompletionException)) {
                current = current.getCause();
            } else {
                current = current.getCause();
            }
        }
        return false;
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? "" : current.getMessage();
    }

    /**
     * 对 Embedding 不可用场景做运行期降级，返回 null 由上游统一走空结果分支。
     */
    private EmbeddingModel.EmbeddingsResponse safeEmbed(EmbeddingModel.EmbeddingsRequest request) {
        try {
            return embeddingService.embed(request);
        } catch (IllegalStateException | WebClientResponseException | WebClientRequestException e) {
            log.warn("Embedding 不可用，检索降级为无向量结果: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 按度量类型归一化稠密向量分值。
     */
    private float normalizeDenseScore(Float value, KnowledgeRetrieveItemDto.MetricTypeEnum metricType, Float denseMax, Float l2Max) {
        if (value == null) {
            return 0f;
        }
        if (metricType == null) {
            return normalizeByMax(value, denseMax);
        }
        return switch (metricType) {
            case IP, COSINE -> clampPercent(((value + 1f) / 2f) * 100f);
            case JACCARD -> clampPercent(value * 100f);
            case L2 -> normalizeL2Score(value, l2Max);
        };
    }

    /**
     * 归一化稀疏向量分值。
     */
    private float normalizeSparseScore(Float value, Float sparseMax) {
        return normalizeByMax(value, sparseMax);
    }

    /**
     * 按最大值做线性归一化。
     */
    private float normalizeByMax(Float value, Float maxValue) {
        if (value == null) {
            return 0f;
        }
        if (maxValue == null || maxValue <= 0f) {
            return 0f;
        }
        return clampPercent((value / maxValue) * 100f);
    }

    /**
     * 使用指数衰减归一化 L2 距离分值。
     */
    private float normalizeL2Score(Float value, Float maxValue) {
        if (value == null) {
            return 0f;
        }
        if (maxValue == null || maxValue <= 0f) {
            return value <= 0f ? 100f : 0f;
        }
        float ratio = value / maxValue;
        if (ratio < 0f) {
            ratio = 0f;
        }
        float expMax = (float) Math.exp(-1f);
        float expValue = (float) Math.exp(-ratio);
        float normalized = (expValue - expMax) / (1f - expMax);
        return clampPercent(normalized * 100f);
    }

    /**
     * 计算稠密向量分值最大值。
     */
    private Float resolveMaxDenseScore(List<EmbeddingModel.EmbeddingsQueryItem> embeddingsQueryItems) {
        if (embeddingsQueryItems == null || embeddingsQueryItems.isEmpty()) {
            return null;
        }
        Float maxScore = null;
        for (EmbeddingModel.EmbeddingsQueryItem item : embeddingsQueryItems) {
            Float score = item == null ? null : item.getDenseScore();
            if (score != null) {
                maxScore = maxScore == null ? score : Math.max(maxScore, score);
            }
        }
        return maxScore;
    }

    /**
     * 计算稀疏向量分值最大值。
     */
    private Float resolveMaxSparseScore(List<EmbeddingModel.EmbeddingsQueryItem> embeddingsQueryItems) {
        if (embeddingsQueryItems == null || embeddingsQueryItems.isEmpty()) {
            return null;
        }
        Float maxScore = null;
        for (EmbeddingModel.EmbeddingsQueryItem item : embeddingsQueryItems) {
            Float score = item == null ? null : item.getSparseScore();
            if (score != null) {
                maxScore = maxScore == null ? score : Math.max(maxScore, score);
            }
        }
        return maxScore;
    }

    /**
     * 将百分比分值限制在 0-100。
     */
    private float clampPercent(float value) {
        if (value < 0f) {
            return 0f;
        }
        if (value > 100f) {
            return 100f;
        }
        return value;
    }
}
