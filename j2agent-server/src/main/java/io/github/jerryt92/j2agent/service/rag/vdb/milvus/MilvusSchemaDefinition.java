package io.github.jerryt92.j2agent.service.rag.vdb.milvus;

import io.milvus.v2.common.DataType;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Milvus 集合字段与索引定义。
 */
public final class MilvusSchemaDefinition {
    private MilvusSchemaDefinition() {
    }

    public static final String FIELD_SEGMENT_ID = "segment_id";
    public static final String FIELD_TEXT_CHUNK_ID = "text_chunk_id";
    public static final String FIELD_EMBEDDING_MODEL = "embedding_model";
    public static final String FIELD_EMBEDDING_PROVIDER = "embedding_provider";
    public static final String FIELD_CHECK_EMBEDDING_HASH = "check_embedding_hash";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_QUESTION = "question";
    public static final String FIELD_ANSWER = "answer";
    public static final String FIELD_SOURCE_FILE = "source_file";
    public static final String FIELD_HEADING_PATH = "heading_path";
    public static final String FIELD_COLLECTION_TAG = "collection_tag";
    public static final String FIELD_FILE_SHA256 = "file_sha256";
    public static final String FIELD_EMBEDDING = "embedding";
    public static final String FIELD_SPARSE = "sparse";

    /**
     * 返回默认字段定义。
     */
    public static List<FieldDef> defaultFields(int dimension) {
        return List.of(
                FieldDef.builder().name(FIELD_SEGMENT_ID).dataType(DataType.VarChar).maxLength(64).primaryKey(true).description("分片主键").build(),
                FieldDef.builder().name(FIELD_TEXT_CHUNK_ID).dataType(DataType.VarChar).maxLength(64).description("文本块ID").build(),
                FieldDef.builder().name(FIELD_EMBEDDING_MODEL).dataType(DataType.VarChar).maxLength(128).description("嵌入模型").build(),
                FieldDef.builder().name(FIELD_EMBEDDING_PROVIDER).dataType(DataType.VarChar).maxLength(128).description("嵌入提供方").build(),
                FieldDef.builder().name(FIELD_CHECK_EMBEDDING_HASH).dataType(DataType.VarChar).maxLength(64).description("模型校验哈希").build(),
                FieldDef.builder().name(FIELD_TEXT).dataType(DataType.VarChar).maxLength(8192).description("检索文本").enableAnalyzer(true).build(),
                FieldDef.builder().name(FIELD_QUESTION).dataType(DataType.VarChar).maxLength(2048).description("问题").build(),
                FieldDef.builder().name(FIELD_ANSWER).dataType(DataType.VarChar).maxLength(8192).description("答案").build(),
                FieldDef.builder().name(FIELD_SOURCE_FILE).dataType(DataType.VarChar).maxLength(2048).description("源文件路径").build(),
                FieldDef.builder().name(FIELD_HEADING_PATH).dataType(DataType.VarChar).maxLength(2048).description("标题路径").build(),
                FieldDef.builder().name(FIELD_COLLECTION_TAG).dataType(DataType.VarChar).maxLength(128).description("目录collection标记").build(),
                FieldDef.builder().name(FIELD_FILE_SHA256).dataType(DataType.VarChar).maxLength(64).description("文件sha256").build(),
                FieldDef.builder().name(FIELD_EMBEDDING).dataType(DataType.FloatVector).dimension(dimension).description("稠密向量").build(),
                FieldDef.builder().name(FIELD_SPARSE).dataType(DataType.SparseFloatVector).description("BM25稀疏向量").build()
        );
    }

    /**
     * 检索默认输出字段。
     */
    public static List<String> outputFields() {
        return List.of(
                FIELD_SEGMENT_ID,
                FIELD_TEXT_CHUNK_ID,
                FIELD_EMBEDDING_MODEL,
                FIELD_EMBEDDING_PROVIDER,
                FIELD_TEXT,
                FIELD_QUESTION,
                FIELD_ANSWER,
                FIELD_SOURCE_FILE,
                FIELD_HEADING_PATH
        );
    }

    /**
     * Milvus 字段定义对象。
     */
    @Getter
    @Builder
    public static class FieldDef {
        private String name;
        private DataType dataType;
        private Integer maxLength;
        private Integer dimension;
        private boolean primaryKey;
        private boolean autoId;
        private boolean enableAnalyzer;
        private String description;
    }
}

