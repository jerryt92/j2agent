package io.github.jerryt92.j2agent.model;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

public class EmbeddingModel {

    @Data
    @Accessors(chain = true)
    public static class EmbeddingsRequest {
        List<String> input;
    }

    @Data
    @Accessors(chain = true)
    public static class EmbeddingsResponse {
        List<EmbeddingsItem> data;
    }

    @Data
    @Accessors(chain = true)
    public static class EmbeddingsItem {
        // 嵌入模型名称
        String embeddingModel;
        // 嵌入模型提供商名称
        String embeddingProvider;
        // 用于标记数据的嵌入模型
        String checkEmbeddingHash;
        // 嵌入文本
        String text;
        // 嵌入向量
        float[] embeddings;
    }

    @Data
    @Accessors(chain = true)
    public static class EmbeddingsQueryItem {
        // 嵌入数据的哈希值，同时也作为唯一标识符
        String hash;
        // 检索结果的得分（越靠近1越相似）
        float score;
        // 混合检索得分
        Float hybridScore;
        // 稠密向量检索得分
        Float denseScore;
        // 稀疏向量检索得分
        Float sparseScore;
        // 嵌入模型名称
        String embeddingModel;
        // 嵌入模型提供商名称
        String embeddingProvider;
        // 嵌入文本
        String text;
        // 文本块ID
        String textChunkId;
        // 问题
        String question;
        // 答案
        String answer;
        // 源文件路径
        String sourceFile;
    }
}
