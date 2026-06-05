package io.github.jerryt92.j2agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG / Embedding 检索侧查询切分与多向量融合配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "j2agent.retrieve")
public class RetrieveProperties {

    /**
     * 单条送入 Embedding API 的最大字符数（低于百炼等厂商 8192 上限留余量）。
     */
    private int maxEmbeddingInputChars = 7500;

    /**
     * 超长 query 切分时的重叠字符数，减轻句边界切断影响。
     */
    private int queryChunkOverlapChars = 200;

    /**
     * 超长 query 最多切分几段；超出时首 N-1 段从头滑动，末段取全文尾部。
     */
    private int maxQueryChunks = 4;
}
