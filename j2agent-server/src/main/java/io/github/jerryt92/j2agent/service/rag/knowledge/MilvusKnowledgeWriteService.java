package io.github.jerryt92.j2agent.service.rag.knowledge;

import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.bo.KnowledgeVectorBo;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.ContentSegmentChunker;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoSyncGuard;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeTextChunkParser;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import io.github.jerryt92.j2agent.service.rag.vdb.milvus.MilvusSchemaDefinition;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识向量写入服务。
 */
@Slf4j
@Service
public class MilvusKnowledgeWriteService {
    private static final int MAX_TEXT_LENGTH = maxLengthOf(MilvusSchemaDefinition.FIELD_TEXT);
    private static final int MAX_HEADING_PATH_LENGTH = maxLengthOf(MilvusSchemaDefinition.FIELD_HEADING_PATH);

    private final EmbeddingService embeddingService;
    private final VectorDatabaseService vectorDatabaseService;
    private final KnowledgeTextChunkService knowledgeTextChunkService;
    private final ContentSegmentChunker contentSegmentChunker;

    /**
     * 表示当前批次向量化因 Embedding 配置/服务不可用而降级，供上层进行可恢复处理。
     */
    public static class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 同步被协调器 guard 或线程中断终止。
     */
    public static class SyncInterruptedException extends RuntimeException {
        public SyncInterruptedException(String message) {
            super(message);
        }
    }

    private record VectorSegmentDraft(String text, String type, String textChunkId, String headingPath) {
    }

    public MilvusKnowledgeWriteService(EmbeddingService embeddingService,
                                       VectorDatabaseService vectorDatabaseService,
                                       KnowledgeTextChunkService knowledgeTextChunkService,
                                       ContentSegmentChunker contentSegmentChunker) {
        this.embeddingService = embeddingService;
        this.vectorDatabaseService = vectorDatabaseService;
        this.knowledgeTextChunkService = knowledgeTextChunkService;
        this.contentSegmentChunker = contentSegmentChunker;
    }

    /**
     * 将逻辑文本块写入 MySQL 并展开为多向量后 upsert 到 Milvus。
     *
     * @return 写入的逻辑 text_chunk 条数
     */
    public int upsertTextChunks(List<KnowledgeTextChunkParser.TextChunk> chunks,
                                String sourceFile,
                                String fileSha256,
                                String collectionTag,
                                List<String> partitionNames) {
        return upsertTextChunks(chunks, sourceFile, fileSha256, collectionTag, partitionNames, null);
    }

    public int upsertTextChunks(List<KnowledgeTextChunkParser.TextChunk> chunks,
                                String sourceFile,
                                String fileSha256,
                                String collectionTag,
                                List<String> partitionNames,
                                KnowledgeRepoSyncGuard guard) {
        if (chunks == null || chunks.isEmpty()) {
            return 0;
        }
        knowledgeTextChunkService.batchUpsert(chunks, sourceFile, fileSha256, collectionTag);
        List<VectorSegmentDraft> drafts = expandVectorDrafts(chunks);
        List<VectorSegmentDraft> validDrafts = filterValidDrafts(drafts, sourceFile, collectionTag);
        if (validDrafts.isEmpty()) {
            log.warn("Milvus 知识写入跳过: 所有向量分片均不满足字段长度限制, collection={}, sourceFile={}, textChunk数={}",
                    collectionTag, sourceFile, chunks.size());
            return chunks.size();
        }
        int batchSize = Math.max(1, embeddingService.resolveEmbeddingBatchSize());
        List<List<VectorSegmentDraft>> batches = ListUtils.partition(validDrafts, batchSize);
        int totalUpserted = 0;
        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            ensureUpsertMayContinue(guard, sourceFile);
            List<VectorSegmentDraft> batch = batches.get(batchIndex);
            int batchNumber = batchIndex + 1;
            List<KnowledgeVectorBo> vectors = embedBatch(batch, sourceFile, fileSha256, collectionTag, batchNumber, batches.size());
            ensureUpsertMayContinue(guard, sourceFile);
            vectorDatabaseService.putData(collectionTag, vectors, partitionNames);
            totalUpserted += vectors.size();
        }
        log.debug("知识库写入完成: collection={}, sourceFile={}, textChunks={}, milvusVectors={}",
                collectionTag, sourceFile, chunks.size(), totalUpserted);
        return chunks.size();
    }

    private List<VectorSegmentDraft> expandVectorDrafts(List<KnowledgeTextChunkParser.TextChunk> chunks) {
        List<VectorSegmentDraft> drafts = new ArrayList<>();
        for (KnowledgeTextChunkParser.TextChunk chunk : chunks) {
            drafts.add(new VectorSegmentDraft(
                    chunk.headingPath(),
                    KnowledgeSegmentType.TITLE,
                    chunk.textChunkId(),
                    chunk.headingPath()
            ));
            if (!chunk.emptyBody()) {
                for (String slice : contentSegmentChunker.chunk(chunk.textChunk())) {
                    if (StringUtils.isBlank(slice)) {
                        continue;
                    }
                    drafts.add(new VectorSegmentDraft(
                            slice,
                            KnowledgeSegmentType.CONTENT_SEGMENT,
                            chunk.textChunkId(),
                            chunk.headingPath()
                    ));
                }
            }
        }
        return drafts;
    }

    private void ensureUpsertMayContinue(KnowledgeRepoSyncGuard guard, String sourceFile) {
        if (guard != null && !guard.shouldContinue()) {
            throw new SyncInterruptedException("知识库向量化被中断: " + sourceFile);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new SyncInterruptedException("知识库向量化批次被中断: " + sourceFile);
        }
    }

    private List<KnowledgeVectorBo> embedBatch(List<VectorSegmentDraft> drafts,
                                               String sourceFile,
                                               String fileSha256,
                                               String collectionTag,
                                               int batchNumber,
                                               int totalBatches) {
        List<String> inputTexts = drafts.stream().map(VectorSegmentDraft::text).toList();
        EmbeddingModel.EmbeddingsResponse embeddingsResponse;
        try {
            embeddingsResponse = embeddingService.embed(
                    new EmbeddingModel.EmbeddingsRequest().setInput(inputTexts)
            );
        } catch (IllegalStateException | WebClientResponseException | WebClientRequestException e) {
            throw new EmbeddingUnavailableException("Embedding 不可用，跳过本批次向量化: " + sourceFile, e);
        }
        if (embeddingsResponse == null || embeddingsResponse.getData() == null) {
            log.error("知识向量分片向量化失败: collection={}, sourceFile={}, batch={}/{}, 请求条数={}, 返回为空",
                    collectionTag, sourceFile, batchNumber, totalBatches, drafts.size());
            throw new IllegalStateException("知识向量分片向量化失败，返回为空: " + sourceFile);
        }
        if (embeddingsResponse.getData().size() != drafts.size()) {
            log.error("知识向量分片向量化数量不匹配: collection={}, sourceFile={}, batch={}/{}, 请求条数={}, 返回条数={}",
                    collectionTag, sourceFile, batchNumber, totalBatches, drafts.size(), embeddingsResponse.getData().size());
            throw new IllegalStateException("知识向量分片向量化数量不匹配: " + sourceFile);
        }
        List<KnowledgeVectorBo> vectors = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            VectorSegmentDraft draft = drafts.get(i);
            EmbeddingModel.EmbeddingsItem embeddingItem = embeddingsResponse.getData().get(i);
            if (embeddingItem.getEmbeddings() == null || embeddingItem.getEmbeddings().length == 0) {
                log.error("知识向量分片向量为空: collection={}, sourceFile={}, batch={}/{}, headingPath={}, type={}",
                        collectionTag, sourceFile, batchNumber, totalBatches, draft.headingPath(), draft.type());
                throw new IllegalStateException("知识向量分片向量为空: " + sourceFile);
            }
            List<Float> vector = new ArrayList<>(embeddingItem.getEmbeddings().length);
            for (float value : embeddingItem.getEmbeddings()) {
                vector.add(value);
            }
            vectors.add(new KnowledgeVectorBo()
                    .setSegmentId(UUIDv7Utils.randomUUIDv7())
                    .setTextChunkId(draft.textChunkId())
                    .setType(draft.type())
                    .setText(draft.text())
                    .setEmbeddingModel(embeddingItem.getEmbeddingModel())
                    .setEmbeddingProvider(embeddingItem.getEmbeddingProvider())
                    .setCheckEmbeddingHash(embeddingItem.getCheckEmbeddingHash())
                    .setEmbedding(vector)
                    .setSourceFile(sourceFile)
                    .setHeadingPath(draft.headingPath())
                    .setCollectionTag(collectionTag)
                    .setFileSha256(fileSha256)
                    .setUpdateTime(System.currentTimeMillis()));
        }
        return vectors;
    }

    private List<VectorSegmentDraft> filterValidDrafts(List<VectorSegmentDraft> drafts,
                                                       String sourceFile,
                                                       String collectionTag) {
        List<VectorSegmentDraft> validDrafts = new ArrayList<>();
        for (VectorSegmentDraft draft : drafts) {
            int textLength = byteLengthOf(draft.text());
            int headingLength = byteLengthOf(draft.headingPath());
            if (textLength > MAX_TEXT_LENGTH || headingLength > MAX_HEADING_PATH_LENGTH) {
                log.warn("知识库向量分片跳过: 字段 UTF-8 字节长度超过 Milvus 限制, collection={}, sourceFile={}, headingPath={}, type={}, textBytes={}/{}, headingBytes={}/{}",
                        collectionTag, sourceFile, draft.headingPath(), draft.type(), textLength, MAX_TEXT_LENGTH,
                        headingLength, MAX_HEADING_PATH_LENGTH);
                continue;
            }
            validDrafts.add(draft);
        }
        return validDrafts;
    }

    private int byteLengthOf(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int maxLengthOf(String fieldName) {
        return MilvusSchemaDefinition.defaultFields(0).stream()
                .filter(field -> fieldName.equals(field.getName()))
                .findFirst()
                .map(MilvusSchemaDefinition.FieldDef::getMaxLength)
                .orElse(Integer.MAX_VALUE);
    }

    /**
     * 删除指定 collection 下指定源文件的全部分片（Milvus + MySQL）。
     */
    public void deleteBySourceFile(String collectionName, String sourceFile) {
        vectorDatabaseService.deleteBySourceFile(collectionName, sourceFile);
        knowledgeTextChunkService.deleteBySourceFile(sourceFile);
    }

    public void dropCollection(String collectionName) {
        vectorDatabaseService.dropCollection(collectionName);
    }

    public boolean hasCollection(String collectionName) {
        return vectorDatabaseService.hasCollection(collectionName);
    }

    public void dropAllCollections() {
        vectorDatabaseService.dropAllCollections();
    }
}
