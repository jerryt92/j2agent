package io.github.jerryt92.j2agent.service.rag;

import io.github.jerryt92.j2agent.config.rag.SimpleRagTaskConfig;
import io.github.jerryt92.j2agent.mapper.SimpleRagCollectionStateMapper;
import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.model.KnowledgeRetrieveItemDto;
import io.github.jerryt92.j2agent.model.po.SimpleRagCollectionStatePo;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.rag.inf.AbstractSimpleRagRetriever;
import io.github.jerryt92.j2agent.service.rag.knowledge.bo.KnowledgeVectorBo;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeTextChunkParser;
import io.github.jerryt92.j2agent.service.rag.retrieval.Retriever;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import io.github.jerryt92.j2agent.utils.HashUtil;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SimpleRag 独立同步服务。复用知识库 Markdown parser，使用 Milvus collection 存储 Agent 随附资料。
 */
@Slf4j
@Service
public class SimpleRagStoreSyncService {

    public static final String COLLECTION_PREFIX = "simple_rag_";
    private static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final int ERROR_MESSAGE_LIMIT = 2048;

    private final ApplicationContext applicationContext;
    private final EmbeddingService embeddingService;
    private final VectorDatabaseService vectorDatabaseService;
    private final KnowledgeTextChunkParser parser;
    private final SimpleRagCollectionStateMapper stateMapper;
    private final TaskExecutor taskExecutor;

    public SimpleRagStoreSyncService(ApplicationContext applicationContext,
                                     EmbeddingService embeddingService,
                                     VectorDatabaseService vectorDatabaseService,
                                     KnowledgeTextChunkParser parser,
                                     SimpleRagCollectionStateMapper stateMapper,
                                     @Qualifier(SimpleRagTaskConfig.SIMPLE_RAG_TASK_EXECUTOR) TaskExecutor taskExecutor) {
        this.applicationContext = applicationContext;
        this.embeddingService = embeddingService;
        this.vectorDatabaseService = vectorDatabaseService;
        this.parser = parser;
        this.stateMapper = stateMapper;
        this.taskExecutor = taskExecutor;
    }

    /**
     * 应用就绪后提交 SimpleRag 后台同步。
     */
    @Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE - 40)
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        synchronizeSimpleRagRetrievers();
    }

    /**
     * 提交后台同步任务，不阻塞调用方。
     */
    public void synchronizeSimpleRagRetrievers() {
        try {
            taskExecutor.execute(this::synchronizeSimpleRagRetrieversInBackground);
        } catch (RuntimeException e) {
            log.warn("SimpleRag background synchronization rejected", e);
        }
    }

    /**
     * 串行执行 SimpleRag 全量同步：清理陈旧 collection/状态并刷新当前 retriever。
     */
    synchronized void synchronizeSimpleRagRetrieversInBackground() {
        Map<String, SimpleRagRetrieverBinding> retrievers = simpleRagRetrieverBindingsByName();
        Set<String> activeCollections = new LinkedHashSet<>();
        for (String storeName : retrievers.keySet()) {
            activeCollections.add(toCollectionName(storeName));
        }
        Set<String> staleCollections = listExistingSimpleRagCollections();
        staleCollections.removeAll(activeCollections);
        for (String staleCollection : staleCollections) {
            log.info("Removing stale SimpleRag collection: {}", staleCollection);
            vectorDatabaseService.dropCollection(staleCollection);
            stateMapper.deleteByCollectionName(staleCollection);
        }
        Set<String> staleStateCollections = new LinkedHashSet<>(stateMapper.selectAllCollectionNames());
        staleStateCollections.removeAll(activeCollections);
        for (String staleStateCollection : staleStateCollections) {
            if (StringUtils.startsWith(staleStateCollection, COLLECTION_PREFIX)
                    && !staleCollections.contains(staleStateCollection)) {
                stateMapper.deleteByCollectionName(staleStateCollection);
            }
        }
        for (SimpleRagRetrieverBinding binding : retrievers.values()) {
            try {
                SimpleRagRefreshResult result = refresh(binding.retriever(), binding.ownerAgentId());
                log.info("SimpleRag refresh result: store={}, collection={}, success={}, docs={}",
                        result.storeName(), result.collectionName(), result.success(), result.documentCount());
            } catch (Exception e) {
                log.warn("SimpleRag refresh failed: store={}, ownerAgentId={}",
                        binding.retriever().ragStoreName(), binding.ownerAgentId(), e);
            }
        }
    }

    /**
     * 从指定 SimpleRag collection 检索文档。
     */
    public List<Document> retrieveFromStore(AbstractSimpleRagRetriever retriever, Query query) {
        String queryText = Retriever.resolveQueryText(query);
        if (StringUtils.isBlank(queryText)) {
            return List.of();
        }
        if (!embeddingService.isReady()) {
            log.warn("SimpleRag retrieval skipped, embedding unavailable: store={}", retriever.ragStoreName());
            return List.of();
        }
        SimpleRagRetrieverParams params = retriever.resolveParams();
        EmbeddingModel.EmbeddingsResponse response = embeddingService.embed(
                new EmbeddingModel.EmbeddingsRequest().setInput(List.of(queryText)));
        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            return List.of();
        }
        String collectionName = toCollectionName(retriever.ragStoreName());
        float[] queryVector = response.getData().getFirst().getEmbeddings();
        configureVectorDatabase(params);
        List<EmbeddingModel.EmbeddingsQueryItem> hits = vectorDatabaseService.hybridRetrieval(
                collectionName,
                queryText,
                queryVector,
                params.topK(),
                params.metricType().name(),
                params.denseWeight(),
                params.sparseWeight());
        return hits.stream().map(this::toDocument).toList();
    }

    /**
     * 刷新单个 SimpleRag collection；已 COMPLETED 则跳过重建。
     */
    public SimpleRagRefreshResult refresh(AbstractSimpleRagRetriever retriever, String ownerAgentId) {
        String storeName = retriever.ragStoreName();
        String collectionName = toCollectionName(storeName);
        SimpleRagCollectionStatePo state = stateMapper.selectByCollectionName(collectionName);
        if (state != null && STATUS_COMPLETED.equals(state.getSyncStatus())) {
            return new SimpleRagRefreshResult(storeName, collectionName, true,
                    "SimpleRag refresh skipped: already completed",
                    state.getDocumentCount() == null ? 0 : state.getDocumentCount());
        }

        markInProgress(collectionName, ownerAgentId);

        try {
            vectorDatabaseService.dropCollection(collectionName);
            vectorDatabaseService.createCollectionIfAbsent(collectionName);
            if (!embeddingService.isReady()) {
                throw new IllegalStateException("Embedding service is unavailable: " + embeddingService.getLastProbeError());
            }
            SimpleRagRetrieverParams params = retriever.resolveParams();
            configureVectorDatabase(params);
            List<SimpleRagMarkdownResource> resources = loadSimpleRagMarkdownResources(retriever);
            if (resources.isEmpty()) {
                markCompleted(collectionName, ownerAgentId, 0);
                return new SimpleRagRefreshResult(storeName, collectionName, true, "SimpleRag refreshed: empty", 0);
            }
            List<KnowledgeTextChunkParser.TextChunk> chunks = parseSimpleRagResources(retriever, resources);
            if (chunks.isEmpty()) {
                markCompleted(collectionName, ownerAgentId, 0);
                return new SimpleRagRefreshResult(storeName, collectionName, true, "SimpleRag refreshed: empty chunks", 0);
            }
            EmbeddingModel.EmbeddingsResponse response = embeddingService.embed(
                    new EmbeddingModel.EmbeddingsRequest().setInput(chunks.stream()
                            .map(KnowledgeTextChunkParser.TextChunk::textChunk)
                            .toList()));
            if (response == null || response.getData() == null || response.getData().size() != chunks.size()) {
                throw new IllegalStateException("Embedding response size mismatch");
            }
            List<KnowledgeVectorBo> vectors = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                vectors.add(toVector(collectionName, chunks.get(i), response.getData().get(i)));
            }
            vectorDatabaseService.putData(collectionName, vectors);
            markCompleted(collectionName, ownerAgentId, vectors.size());
            return new SimpleRagRefreshResult(storeName, collectionName, true, "SimpleRag refreshed", vectors.size());
        } catch (RuntimeException e) {
            String message = rootCauseMessage(e);
            markFailed(collectionName, ownerAgentId, message);
            log.warn("SimpleRag refresh failed: store={}, collection={}, reason={}",
                    storeName, collectionName, message, e);
            return new SimpleRagRefreshResult(storeName, collectionName, false, message, 0);
        }
    }

    /**
     * 删除指定业务库对应的 Milvus collection 与状态记录。
     */
    public void remove(String storeName) {
        String collectionName = toCollectionName(storeName);
        vectorDatabaseService.dropCollection(collectionName);
        stateMapper.deleteByCollectionName(collectionName);
    }

    /**
     * 失效指定 Agent 的 SimpleRag：drop collection 并删除状态，供后续同步重建。
     */
    public void invalidateByOwnerAgentIds(Collection<String> ownerAgentIds) {
        if (ownerAgentIds == null || ownerAgentIds.isEmpty()) {
            return;
        }
        Set<String> uniqueAgentIds = new LinkedHashSet<>();
        for (String ownerAgentId : ownerAgentIds) {
            if (StringUtils.isNotBlank(ownerAgentId)) {
                uniqueAgentIds.add(ownerAgentId.trim());
            }
        }
        for (String ownerAgentId : uniqueAgentIds) {
            List<String> collections = stateMapper.selectCollectionNamesByOwnerAgentId(ownerAgentId);
            if (collections == null || collections.isEmpty()) {
                continue;
            }
            for (String collectionName : collections) {
                if (StringUtils.isBlank(collectionName)) {
                    continue;
                }
                log.info("Invalidating SimpleRag for plugin replace: ownerAgentId={}, collection={}",
                        ownerAgentId, collectionName);
                vectorDatabaseService.dropCollection(collectionName);
            }
            stateMapper.deleteByOwnerAgentId(ownerAgentId);
        }
    }

    /**
     * 将业务库名转换为带前缀的 Milvus collection 名。
     */
    public String toCollectionName(String storeName) {
        String normalized = StringUtils.trimToEmpty(storeName);
        if (StringUtils.isBlank(normalized)) {
            throw new IllegalArgumentException("SimpleRag store name must not be blank");
        }
        return normalized.startsWith(COLLECTION_PREFIX) ? normalized : COLLECTION_PREFIX + normalized;
    }

    private void configureVectorDatabase(SimpleRagRetrieverParams params) {
        Integer dimension = embeddingService.getDimension();
        if (dimension == null || dimension <= 0) {
            throw new IllegalStateException("Embedding dimension is unavailable for SimpleRag");
        }
        KnowledgeRetrieveItemDto.MetricTypeEnum metricType = params == null || params.metricType() == null
                ? KnowledgeRetrieveItemDto.MetricTypeEnum.COSINE
                : params.metricType();
        vectorDatabaseService.reBuildVectorDatabase(dimension, metricType.name());
    }

    private Set<String> listExistingSimpleRagCollections() {
        Set<String> names = new LinkedHashSet<>();
        for (String collectionName : vectorDatabaseService.listCollections()) {
            if (StringUtils.startsWith(collectionName, COLLECTION_PREFIX)) {
                names.add(collectionName);
            }
        }
        return names;
    }

    private void markInProgress(String collectionName, String ownerAgentId) {
        upsertState(collectionName, ownerAgentId, STATUS_IN_PROGRESS, 0, null);
    }

    private void markCompleted(String collectionName, String ownerAgentId, int documentCount) {
        upsertState(collectionName, ownerAgentId, STATUS_COMPLETED, documentCount, null);
    }

    private void markFailed(String collectionName, String ownerAgentId, String errorMessage) {
        upsertState(collectionName, ownerAgentId, STATUS_FAILED, 0, abbreviateError(errorMessage));
    }

    private void upsertState(String collectionName,
                             String ownerAgentId,
                             String syncStatus,
                             int documentCount,
                             String errorMessage) {
        long now = System.currentTimeMillis();
        SimpleRagCollectionStatePo po = new SimpleRagCollectionStatePo();
        po.setId(UUIDv7Utils.randomUUIDv7());
        po.setCollectionName(collectionName);
        po.setOwnerAgentId(ownerAgentId);
        po.setSyncStatus(syncStatus);
        po.setDocumentCount(documentCount);
        po.setErrorMessage(errorMessage);
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        stateMapper.upsert(po);
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return StringUtils.defaultIfBlank(current.getMessage(), current.getClass().getSimpleName());
    }

    private String abbreviateError(String value) {
        String normalized = StringUtils.defaultString(value);
        if (normalized.length() <= ERROR_MESSAGE_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, ERROR_MESSAGE_LIMIT);
    }

    private List<SimpleRagMarkdownResource> loadSimpleRagMarkdownResources(AbstractSimpleRagRetriever retriever) {
        String rootPath = normalizeResourcePath(retriever.simpleRagResourcePath());
        if (StringUtils.isBlank(rootPath)) {
            return List.of();
        }
        try {
            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver(retriever.getClass().getClassLoader());
            Resource[] matchedResources = resolver.getResources("classpath*:" + rootPath + "/**/*.md");
            List<Resource> sortedResources = new ArrayList<>(List.of(matchedResources));
            sortedResources.sort(Comparator.comparing(this::resourceSortKey));
            List<SimpleRagMarkdownResource> markdownResources = new ArrayList<>();
            for (Resource resource : sortedResources) {
                if (!resource.isReadable()) {
                    continue;
                }
                String sourcePath = resolveSourcePath(rootPath, resource);
                String text = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                markdownResources.add(new SimpleRagMarkdownResource(sourcePath, text, filenameTitle(sourcePath)));
            }
            return markdownResources;
        } catch (IOException e) {
            log.warn("Failed to load SimpleRag markdown resources: root={}", rootPath, e);
            return List.of();
        }
    }

    private Document toDocument(EmbeddingModel.EmbeddingsQueryItem item) {
        return Document.builder()
                .text(StringUtils.defaultIfBlank(item.getTextChunk(), item.getText()))
                .score((double) item.getScore())
                .build();
    }

    private String normalizeResourcePath(String resourcePath) {
        String normalized = StringUtils.trimToEmpty(resourcePath)
                .replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String resourceSortKey(Resource resource) {
        try {
            URL url = resource.getURL();
            return url == null ? StringUtils.defaultString(resource.getFilename()) : url.toExternalForm();
        } catch (IOException e) {
            return StringUtils.defaultString(resource.getFilename());
        }
    }

    private String resolveSourcePath(String rootPath, Resource resource) {
        try {
            String external = resource.getURL().toExternalForm();
            int index = external.indexOf(rootPath);
            if (index >= 0) {
                return external.substring(index);
            }
        } catch (IOException ignored) {
            // fallback below
        }
        return rootPath + "/" + StringUtils.defaultString(resource.getFilename(), UUIDv7Utils.randomUUIDv7() + ".md");
    }

    private String filenameTitle(String sourcePath) {
        String normalized = StringUtils.defaultString(sourcePath).replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String filename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        if (filename.length() > ".md".length() && filename.regionMatches(true, filename.length() - 3, ".md", 0, 3)) {
            return filename.substring(0, filename.length() - 3);
        }
        return StringUtils.defaultIfBlank(filename, normalized);
    }

    private List<KnowledgeTextChunkParser.TextChunk> parseSimpleRagResources(AbstractSimpleRagRetriever retriever,
                                                                             List<SimpleRagMarkdownResource> resources) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        int minHeadingLevel = normalizeMinHeadingLevel(retriever.minHeadingLevel());
        boolean filenameAsTitle = retriever.filenameAsTitle();
        List<KnowledgeTextChunkParser.TextChunk> chunks = new ArrayList<>();
        for (SimpleRagMarkdownResource resource : resources) {
            if (resource == null || StringUtils.isBlank(resource.content())) {
                continue;
            }
            List<KnowledgeTextChunkParser.TextChunk> parsed = parser.parse(
                    resource.sourcePath(),
                    resource.content(),
                    minHeadingLevel,
                    filenameAsTitle,
                    resource.filenameTitle());
            chunks.addAll(parsed.stream()
                    .map(chunk -> normalizeSimpleRagChunk(resource, chunk))
                    .toList());
        }
        return chunks;
    }

    private int normalizeMinHeadingLevel(int minHeadingLevel) {
        if (minHeadingLevel < 1) {
            return 1;
        }
        if (minHeadingLevel > 3) {
            return 3;
        }
        return minHeadingLevel;
    }

    private KnowledgeTextChunkParser.TextChunk normalizeSimpleRagChunk(SimpleRagMarkdownResource resource,
                                                                       KnowledgeTextChunkParser.TextChunk chunk) {
        String chunkId = StringUtils.defaultIfBlank(
                chunk.textChunkId(),
                UUIDv7Utils.randomUUIDv7());
        String headingPath = StringUtils.defaultIfBlank(chunk.headingPath(), resource.filenameTitle());
        return new KnowledgeTextChunkParser.TextChunk(
                chunkId,
                headingPath,
                chunk.textChunk(),
                resource.sourcePath(),
                chunk.emptyBody());
    }

    private Map<String, SimpleRagRetrieverBinding> simpleRagRetrieverBindingsByName() {
        Map<String, SimpleRagRetrieverBinding> retrievers = new LinkedHashMap<>();
        for (AiAgent agent : applicationContext.getBeansOfType(AiAgent.class, true, false).values()) {
            DocumentRetriever documentRetriever = resolveDocumentRetriever(agent);
            if (documentRetriever instanceof AbstractSimpleRagRetriever simpleRagRetriever
                    && StringUtils.isNotBlank(simpleRagRetriever.ragStoreName())) {
                retrievers.put(simpleRagRetriever.ragStoreName(),
                        new SimpleRagRetrieverBinding(simpleRagRetriever, agent.getAgentId()));
            }
        }
        return retrievers;
    }

    private DocumentRetriever resolveDocumentRetriever(AiAgent agent) {
        try {
            return agent.resolveDocumentRetriever();
        } catch (Exception e) {
            log.warn("Failed to resolve document retriever for agent: {}", agent.getAgentId(), e);
            return null;
        }
    }

    private KnowledgeVectorBo toVector(String collectionName,
                                       KnowledgeTextChunkParser.TextChunk chunk,
                                       EmbeddingModel.EmbeddingsItem embedding) {
        return new KnowledgeVectorBo()
                .setSegmentId(UUIDv7Utils.randomUUIDv7())
                .setTextChunkId(chunk.textChunkId())
                .setType("simple-rag")
                .setText(chunk.textChunk())
                .setEmbeddingModel(embedding.getEmbeddingModel())
                .setEmbeddingProvider(embedding.getEmbeddingProvider())
                .setCheckEmbeddingHash(embedding.getCheckEmbeddingHash())
                .setEmbedding(toFloatList(embedding.getEmbeddings()))
                .setSourceFile(StringUtils.defaultIfBlank(chunk.sourcePath(), collectionName))
                .setHeadingPath(chunk.headingPath())
                .setCollectionTag(collectionName)
                .setFileSha256(sha256(chunk.textChunk()))
                .setUpdateTime(System.currentTimeMillis());
    }

    private static List<Float> toFloatList(float[] values) {
        List<Float> result = new ArrayList<>(values == null ? 0 : values.length);
        if (values != null) {
            for (float value : values) {
                result.add(value);
            }
        }
        return result;
    }

    private static String sha256(String value) {
        try {
            return HashUtil.getMessageDigest(
                    StringUtils.defaultString(value).getBytes(StandardCharsets.UTF_8),
                    HashUtil.MdAlgorithm.SHA256);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record SimpleRagRetrieverParams(
            int topK,
            KnowledgeRetrieveItemDto.MetricTypeEnum metricType,
            float denseWeight,
            float sparseWeight
    ) {
    }

    public record SimpleRagRefreshResult(
            String storeName,
            String collectionName,
            boolean success,
            String message,
            int documentCount
    ) {
    }

    private record SimpleRagRetrieverBinding(
            AbstractSimpleRagRetriever retriever,
            String ownerAgentId
    ) {
    }

    private record SimpleRagMarkdownResource(
            String sourcePath,
            String content,
            String filenameTitle
    ) {
    }
}
