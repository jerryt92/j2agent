package io.github.jerryt92.j2agent.service.rag.knowledge;

import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.bo.KnowledgeVectorBo;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.MarkdownQaParser;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import io.github.jerryt92.j2agent.service.rag.vdb.milvus.MilvusSchemaDefinition;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.ListUtils;
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
    private static final int EMBEDDING_BATCH_SIZE = 10;
    private static final int MAX_QUESTION_LENGTH = maxLengthOf(MilvusSchemaDefinition.FIELD_QUESTION);
    private static final int MAX_ANSWER_LENGTH = maxLengthOf(MilvusSchemaDefinition.FIELD_ANSWER);
    private static final int MAX_TEXT_LENGTH = maxLengthOf(MilvusSchemaDefinition.FIELD_TEXT);
    private final EmbeddingService embeddingService;
    private final VectorDatabaseService vectorDatabaseService;

    /**
     * 表示当前批次向量化因 Embedding 配置/服务不可用而降级，供上层进行可恢复处理。
     */
    public static class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public MilvusKnowledgeWriteService(EmbeddingService embeddingService, VectorDatabaseService vectorDatabaseService) {
        this.embeddingService = embeddingService;
        this.vectorDatabaseService = vectorDatabaseService;
    }

    /**
     * 将 QA 分片转换为向量后写入 Milvus。
     *
     * @param partitionNames info.json 中的 Milvus 分区名列表；空则写入默认分区。
     */
    public int upsertQaSegments(List<MarkdownQaParser.QaSegment> segments,
                                 String sourceFile,
                                 String fileSha256,
                                 String collectionTag,
                                 List<String> partitionNames) {
        if (segments == null || segments.isEmpty()) {
            return 0;
        }
        List<MarkdownQaParser.QaSegment> validSegments = filterValidSegments(segments, sourceFile, collectionTag);
        if (validSegments.isEmpty()) {
            log.warn("Milvus 知识写入跳过: 所有分片均不满足字段长度限制, collection={}, sourceFile={}, 原始分片数={}",
                    collectionTag, sourceFile, segments.size());
            return 0;
        }
        int totalUpserted = 0;
        List<List<MarkdownQaParser.QaSegment>> batches = ListUtils.partition(validSegments, EMBEDDING_BATCH_SIZE);
        for (int batchIndex = 0; batchIndex < batches.size(); batchIndex++) {
            List<MarkdownQaParser.QaSegment> batch = batches.get(batchIndex);
            int batchNumber = batchIndex + 1;
            List<KnowledgeVectorBo> vectors = embedBatch(batch, sourceFile, fileSha256, collectionTag, batchNumber, batches.size());
            vectorDatabaseService.putData(collectionTag, vectors, partitionNames);
            totalUpserted += vectors.size();
        }
        return totalUpserted;
    }

    /**
     * 将单批 QA 分片向量化并转换为 Milvus 写入对象。
     */
    private List<KnowledgeVectorBo> embedBatch(List<MarkdownQaParser.QaSegment> segments,
                                               String sourceFile,
                                               String fileSha256,
                                               String collectionTag,
                                               int batchNumber,
                                               int totalBatches) {
        List<String> inputTexts = segments.stream().map(this::buildText).toList();
        EmbeddingModel.EmbeddingsResponse embeddingsResponse;
        try {
            embeddingsResponse = embeddingService.embed(
                    new EmbeddingModel.EmbeddingsRequest().setInput(inputTexts)
            );
        } catch (IllegalStateException | WebClientResponseException | WebClientRequestException e) {
            throw new EmbeddingUnavailableException("Embedding 不可用，跳过本批次向量化: " + sourceFile, e);
        }
        if (embeddingsResponse == null || embeddingsResponse.getData() == null) {
            log.error("QA 分片向量化失败: collection={}, sourceFile={}, batch={}/{}, 请求条数={}, 返回为空",
                    collectionTag, sourceFile, batchNumber, totalBatches, segments.size());
            throw new IllegalStateException("QA 分片向量化失败，返回为空: " + sourceFile);
        }
        if (embeddingsResponse.getData().size() != segments.size()) {
            log.error("QA 分片向量化数量不匹配: collection={}, sourceFile={}, batch={}/{}, 请求条数={}, 返回条数={}",
                    collectionTag, sourceFile, batchNumber, totalBatches, segments.size(), embeddingsResponse.getData().size());
            throw new IllegalStateException("QA 分片向量化数量不匹配: " + sourceFile);
        }
        List<KnowledgeVectorBo> vectors = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            MarkdownQaParser.QaSegment segment = segments.get(i);
            EmbeddingModel.EmbeddingsItem embeddingItem = embeddingsResponse.getData().get(i);
            if (embeddingItem.getEmbeddings() == null || embeddingItem.getEmbeddings().length == 0) {
                log.error("QA 分片向量为空: collection={}, sourceFile={}, batch={}/{}, headingPath={}",
                        collectionTag, sourceFile, batchNumber, totalBatches, segment.headingPath());
                throw new IllegalStateException("QA 分片向量为空: " + sourceFile);
            }
            List<Float> vector = new ArrayList<>(embeddingItem.getEmbeddings().length);
            for (float value : embeddingItem.getEmbeddings()) {
                vector.add(value);
            }
            vectors.add(new KnowledgeVectorBo()
                    .setSegmentId(segment.segmentId())
                    .setTextChunkId(segment.segmentId())
                    .setQuestion(segment.question())
                    .setAnswer(segment.answer())
                    .setText(segment.question() + "\n" + segment.answer())
                    .setEmbeddingModel(embeddingItem.getEmbeddingModel())
                    .setEmbeddingProvider(embeddingItem.getEmbeddingProvider())
                    .setCheckEmbeddingHash(embeddingItem.getCheckEmbeddingHash())
                    .setEmbedding(vector)
                    .setSourceFile(sourceFile)
                    .setHeadingPath(segment.headingPath())
                    .setCollectionTag(collectionTag)
                    .setFileSha256(fileSha256)
                    .setUpdateTime(System.currentTimeMillis()));
        }
        return vectors;
    }

    /**
     * 过滤不满足 Milvus 字段长度限制的分片，避免单条坏数据阻断整批写入。
     */
    private List<MarkdownQaParser.QaSegment> filterValidSegments(List<MarkdownQaParser.QaSegment> segments,
                                                                 String sourceFile,
                                                                 String collectionTag) {
        List<MarkdownQaParser.QaSegment> validSegments = new ArrayList<>();
        for (MarkdownQaParser.QaSegment segment : segments) {
            String text = buildText(segment);
            int questionLength = byteLengthOf(segment.question());
            int answerLength = byteLengthOf(segment.answer());
            int textLength = byteLengthOf(text);
            if (questionLength > MAX_QUESTION_LENGTH || answerLength > MAX_ANSWER_LENGTH || textLength > MAX_TEXT_LENGTH) {
                log.warn("知识库分片跳过: 字段 UTF-8 字节长度超过 Milvus 限制, collection={}, sourceFile={}, headingPath={}, questionBytes={}/{}, answerBytes={}/{}, textBytes={}/{}",
                        collectionTag, sourceFile, segment.headingPath(), questionLength, MAX_QUESTION_LENGTH,
                        answerLength, MAX_ANSWER_LENGTH, textLength, MAX_TEXT_LENGTH);
                continue;
            }
            validSegments.add(segment);
        }
        return validSegments;
    }

    /**
     * 构造 Milvus 检索文本。
     */
    private String buildText(MarkdownQaParser.QaSegment segment) {
        return segment.question() + "\n" + segment.answer();
    }

    /**
     * 计算 UTF-8 字节长度，Milvus VarChar 长度限制按字节生效。
     */
    private int byteLengthOf(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 从 Milvus schema 定义中读取字段长度限制。
     */
    private static int maxLengthOf(String fieldName) {
        return MilvusSchemaDefinition.defaultFields(0).stream()
                .filter(field -> fieldName.equals(field.getName()))
                .findFirst()
                .map(MilvusSchemaDefinition.FieldDef::getMaxLength)
                .orElse(Integer.MAX_VALUE);
    }

    /**
     * 删除指定 collection 下指定源文件的全部分片。
     */
    public void deleteBySourceFile(String collectionName, String sourceFile) {
        vectorDatabaseService.deleteBySourceFile(collectionName, sourceFile);
    }

    /**
     * 删除指定物理 collection。
     */
    public void dropCollection(String collectionName) {
        vectorDatabaseService.dropCollection(collectionName);
    }

    /**
     * 判断指定物理 collection 是否存在。
     */
    public boolean hasCollection(String collectionName) {
        return vectorDatabaseService.hasCollection(collectionName);
    }

    /**
     * 删除 Milvus 实例中的全部 collection，供完全重建使用。
     */
    public void dropAllCollections() {
        vectorDatabaseService.dropAllCollections();
    }
}

