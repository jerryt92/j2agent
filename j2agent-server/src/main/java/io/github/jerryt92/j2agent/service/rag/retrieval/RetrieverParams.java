package io.github.jerryt92.j2agent.service.rag.retrieval;

import io.github.jerryt92.j2agent.model.KnowledgeRetrieveItemDto;
import io.github.jerryt92.j2agent.service.PropertiesService;
import org.apache.commons.lang3.StringUtils;

/**
 * 检索参数快照：统一承载一次检索所需的配置项。
 */
public record RetrieverParams(int topK,
                              KnowledgeRetrieveItemDto.MetricTypeEnum metricType,
                              String metricScoreCompareExpr,
                              float denseWeight,
                              float sparseWeight) {

    /**
     * 从配置服务读取检索参数并归一化权重。
     */
    public static RetrieverParams from(PropertiesService propertiesService) {
        int topK = Integer.parseInt(propertiesService.getProperty(PropertiesService.RETRIEVE_TOP_K));
        String metricTypeStr = propertiesService.getProperty(PropertiesService.RETRIEVE_METRIC_TYPE);
        KnowledgeRetrieveItemDto.MetricTypeEnum metricType = KnowledgeRetrieveItemDto.MetricTypeEnum.valueOf(metricTypeStr);
        String metricScoreCompareExpr = propertiesService.getProperty(PropertiesService.RETRIEVE_METRIC_SCORE_COMPARE_EXPR);
        float denseWeight = clampWeight(parseWeight(propertiesService.getProperty(PropertiesService.RETRIEVE_DENSE_WEIGHT), 0.5f));
        float sparseWeight = clampWeight(parseWeight(propertiesService.getProperty(PropertiesService.RETRIEVE_SPARSE_WEIGHT), 0.5f));
        float total = denseWeight + sparseWeight;
        if (total <= 0f) {
            return new RetrieverParams(topK, metricType, metricScoreCompareExpr, 1f, 0f);
        }
        return new RetrieverParams(topK, metricType, metricScoreCompareExpr, denseWeight / total, sparseWeight / total);
    }

    /**
     * 解析权重字符串。
     */
    private static float parseWeight(String value, float fallback) {
        if (StringUtils.isBlank(value)) {
            return fallback;
        }
        try {
            return Float.parseFloat(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 将权重限定在 0-1 区间。
     */
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
