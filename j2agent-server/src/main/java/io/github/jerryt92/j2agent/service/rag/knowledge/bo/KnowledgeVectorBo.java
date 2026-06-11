package io.github.jerryt92.j2agent.service.rag.knowledge.bo;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Collections;
import java.util.List;

/**
 * 知识向量通用业务对象。
 */
@Data
@Accessors(chain = true)
public class KnowledgeVectorBo {
    /**
     * 分片主键（UUIDv7，每条 Milvus 向量独立）。
     */
    private String segmentId;
    /**
     * 逻辑文本块 ID（UUIDv7，多条向量共享）。
     */
    private String textChunkId;
    /**
     * 分片类型：title / content_segment。
     */
    private String type;
    /**
     * 检索文本（title 或 content_segment 窗口切片）。
     */
    private String text;
    /**
     * embedding 模型名。
     */
    private String embeddingModel;
    /**
     * embedding 提供方。
     */
    private String embeddingProvider;
    /**
     * embedding 模型一致性校验哈希。
     */
    private String checkEmbeddingHash;
    /**
     * 稠密向量。
     */
    private List<Float> embedding = Collections.emptyList();
    /**
     * 源文件路径。
     */
    private String sourceFile;
    /**
     * 标题链路径。
     */
    private String headingPath;
    /**
     * collection 标签（目录映射）。
     */
    private String collectionTag;
    /**
     * 文件 sha256。
     */
    private String fileSha256;
    /**
     * 更新时间戳。
     */
    private Long updateTime;

    /**
     * 返回 Milvus 检索/嵌入文本。
     */
    public String buildText() {
        return text == null ? "" : text;
    }
}
