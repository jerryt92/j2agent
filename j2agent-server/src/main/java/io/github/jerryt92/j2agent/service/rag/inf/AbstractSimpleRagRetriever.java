package io.github.jerryt92.j2agent.service.rag.inf;

import io.github.jerryt92.j2agent.model.KnowledgeRetrieveItemDto;
import io.github.jerryt92.j2agent.service.rag.SimpleRagStoreSyncService;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * Agent 随附 SimpleRag 短文档检索抽象。
 *
 * <p>适用于 Agent 自带的小规模、稳定资料，例如页面路由表、工具说明、领域规则片段等。
 * 子类只负责声明 RAG 库名称、classpath 资源目录，以及必要时覆盖切片/检索策略；
 * 资源读取、Embedding、写入 Milvus、检索和生命周期清理由 SimpleRag 服务处理。</p>
 *
 * <p>ownerAgentId 不需要也不允许由子类手动指定。平台启动或 Agent 插件 reload 后，
 * {@link SimpleRagStoreSyncService} 会扫描所有 {@link io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent}，
 * 通过 Agent 返回的 retriever 反向推断归属 Agent，并使用 {@code agent.getAgentId()} 作为
 * SimpleRag 库的 owner。</p>
 */
public abstract class AbstractSimpleRagRetriever implements DocumentRetriever {

    @Autowired
    private SimpleRagStoreSyncService simpleRagStoreSyncService;

    /**
     * RAG 库业务名称。
     *
     * <p>平台落 Milvus 时会自动添加 {@code simple_rag_} collection 前缀；子类不要手写前缀。
     * 同一个 Agent 内应保持稳定；名称变化会被平台视为新库，旧 collection 会在同步时清理。
     * 建议使用小写字母、数字和下划线。</p>
     */
    public abstract String ragStoreName();

    /**
     * SimpleRag Markdown 资源目录。
     *
     * <p>路径按 classpath 解析，服务会自动读取该目录及子目录下所有 {@code .md} 文件。
     * 例如：{@code skills/my-agent-routes}。</p>
     */
    public abstract String simpleRagResourcePath();

    /**
     * Markdown/AsciiDoc 切片时识别的最小标题级别。
     *
     * <p>取值范围为 1-3。默认从一级标题开始切片；子类可按资料结构覆盖。</p>
     */
    public int minHeadingLevel() {
        return 1;
    }

    /**
     * 文档没有有效标题时，是否使用文件名作为合成标题生成切片。
     */
    public boolean filenameAsTitle() {
        return true;
    }

    /**
     * 单次检索返回条数。
     *
     * <p>默认值在 SimpleRag 抽象内定义，子类可按 Agent 自身资料规模覆盖。</p>
     */
    public int topK() {
        return 5;
    }

    /**
     * 稠密向量距离指标。
     */
    public KnowledgeRetrieveItemDto.MetricTypeEnum metricType() {
        return KnowledgeRetrieveItemDto.MetricTypeEnum.COSINE;
    }

    /**
     * 混合检索中的稠密向量权重。
     */
    public float denseWeight() {
        return 0.5f;
    }

    /**
     * 混合检索中的稀疏/BM25 权重。
     */
    public float sparseWeight() {
        return 0.5f;
    }

    /**
     * Spring AI RAG 检索入口。
     *
     * <p>子类不要直接访问 Milvus 或其他向量库；检索委托给 SimpleRag 自己的服务，
     * 并使用平台统一的 {@code VectorDatabaseService}。</p>
     */
    @Override
    public List<Document> retrieve(Query query) {
        return simpleRagStoreSyncService.retrieveFromStore(this, query);
    }

    /**
     * 解析检索参数，并归一化混合检索权重。
     */
    public SimpleRagStoreSyncService.SimpleRagRetrieverParams resolveParams() {
        int topK = Math.max(1, topK());
        KnowledgeRetrieveItemDto.MetricTypeEnum metricType = metricType() == null
                ? KnowledgeRetrieveItemDto.MetricTypeEnum.COSINE
                : metricType();
        float denseWeight = clampWeight(denseWeight());
        float sparseWeight = clampWeight(sparseWeight());
        float total = Math.max(0f, denseWeight) + Math.max(0f, sparseWeight);
        if (total <= 0f) {
            return new SimpleRagStoreSyncService.SimpleRagRetrieverParams(topK, metricType, 1f, 0f);
        }
        return new SimpleRagStoreSyncService.SimpleRagRetrieverParams(
                topK,
                metricType,
                Math.max(0f, denseWeight) / total,
                Math.max(0f, sparseWeight) / total);
    }

    private static float clampWeight(float value) {
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }
}
