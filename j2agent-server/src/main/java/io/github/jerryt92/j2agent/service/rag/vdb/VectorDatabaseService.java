package io.github.jerryt92.j2agent.service.rag.vdb;

import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.service.rag.knowledge.bo.KnowledgeVectorBo;

import java.util.List;

/**
 * 向量库服务：所有读写与检索必须显式指定 Milvus collection，不提供默认 collection。
 */
public interface VectorDatabaseService {
    /**
     * 根据维度和度量方式初始化向量数据库连接与度量参数（不创建任何 collection）。
     */
    void reBuildVectorDatabase(int dimension, String metricTypeStr);

    /**
     * 按指定 collection 混合检索（语义向量 + 稀疏向量）。
     */
    default List<EmbeddingModel.EmbeddingsQueryItem> hybridRetrieval(String collectionName,
                                                                     String queryText,
                                                                     float[] queryVector,
                                                                     int topK,
                                                                     String metricType,
                                                                     float denseWeight,
                                                                     float sparseWeight) {
        return hybridRetrieval(collectionName, queryText, queryVector, topK, metricType, denseWeight, sparseWeight, null);
    }

    /**
     * 按指定 collection 混合检索，可选限定 Milvus 分区。
     *
     * @param partitionNames 非空时仅在所列分区内检索；null 或空表示全 collection。
     */
    List<EmbeddingModel.EmbeddingsQueryItem> hybridRetrieval(String collectionName,
                                                             String queryText,
                                                             float[] queryVector,
                                                             int topK,
                                                             String metricType,
                                                             float denseWeight,
                                                             float sparseWeight,
                                                             List<String> partitionNames);

    /**
     * 按指定 collection 分页查询知识分片。
     */
    default List<EmbeddingModel.EmbeddingsQueryItem> queryKnowledge(String collectionName,
                                                                    int offset,
                                                                    int limit,
                                                                    String search) {
        return queryKnowledge(collectionName, offset, limit, search, null);
    }

    /**
     * 分页查询知识分片，可选限定 Milvus 分区。
     */
    default List<EmbeddingModel.EmbeddingsQueryItem> queryKnowledge(String collectionName,
                                                                    int offset,
                                                                    int limit,
                                                                    String search,
                                                                    List<String> partitionNames) {
        return List.of();
    }

    /**
     * 按指定 collection 写入向量。
     */
    default void putData(String collectionName, List<KnowledgeVectorBo> knowledgeVectors) {
        putData(collectionName, knowledgeVectors, null);
    }

    /**
     * 按指定 collection 写入向量；partitionNames 非空时创建分区并将数据写入首分区。
     */
    void putData(String collectionName, List<KnowledgeVectorBo> knowledgeVectors, List<String> partitionNames);

    /**
     * 按指定 collection 与源文件路径删除向量数据。
     */
    void deleteBySourceFile(String collectionName, String sourceFile);

    /**
     * 判断指定 collection 是否存在。
     */
    boolean hasCollection(String collectionName);

    /**
     * 删除指定 collection。
     */
    void dropCollection(String collectionName);

    /**
     * 在 collection 不存在时创建。
     */
    void createCollectionIfAbsent(String collectionName);

    /**
     * 列出当前 Milvus 实例中的全部 collection 名称。
     */
    List<String> listCollections();

    /**
     * 删除当前 Milvus 实例中的全部 collection。
     */
    void dropAllCollections();
}
