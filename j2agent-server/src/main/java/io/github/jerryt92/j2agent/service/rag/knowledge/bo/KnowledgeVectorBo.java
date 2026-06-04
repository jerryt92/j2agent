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
     * 分片主键。
     */
    private String segmentId;
    /**
     * 原始文本块ID（兼容旧接口字段）。
     */
    private String textChunkId;
    /**
     * 问题（标题链拼接）。
     */
    private String question;
    /**
     * 答案（正文）。
     */
    private String answer;
    /**
     * 检索文本（Q+A）。
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
     * 生成默认检索文本。
     */
    public String buildText() {
        if (text != null && !text.isBlank()) {
            return text;
        }
        String q = question == null ? "" : question.trim();
        String a = answer == null ? "" : answer.trim();
        return q + "\n" + a;
    }
}

