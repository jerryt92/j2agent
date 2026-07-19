package io.github.jerryt92.j2agent.model.po;

import lombok.Data;

/**
 * SimpleRag collection 级同步状态。
 */
@Data
public class SimpleRagCollectionStatePo {
    /** 主键（UUIDv7）。 */
    private String id;
    /** Milvus collection 名称（含 simple_rag_ 前缀）。 */
    private String collectionName;
    /** 归属 Agent ID。 */
    private String ownerAgentId;
    /** 同步状态：IN_PROGRESS / COMPLETED / FAILED。 */
    private String syncStatus;
    /** 最近一次成功写入的文档条数。 */
    private Integer documentCount;
    /** 失败原因（截断后）。 */
    private String errorMessage;
    /** 创建时间（毫秒时间戳）。 */
    private Long createdAt;
    /** 更新时间（毫秒时间戳）。 */
    private Long updatedAt;
}
