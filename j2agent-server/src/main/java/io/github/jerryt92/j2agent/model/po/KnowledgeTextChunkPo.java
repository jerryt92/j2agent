package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

/**
 * 知识库逻辑文本块持久化实体。
 */
@Data
public class KnowledgeTextChunkPo {
    /**
     * text_chunk_id（UUIDv7）。
     */
    private String id;
    private String headingPath;
    private String textChunk;
    private String sourceFile;
    private String collectionName;
    private String fileSha256;
    private Long createdAt;
    private Long updatedAt;
}
