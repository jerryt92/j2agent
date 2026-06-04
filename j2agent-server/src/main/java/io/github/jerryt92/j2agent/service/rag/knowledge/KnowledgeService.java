package io.github.jerryt92.j2agent.service.rag.knowledge;

import io.github.jerryt92.j2agent.mapper.KnowledgeSourceFileHashMapper;
import io.github.jerryt92.j2agent.model.EmbeddingModel;
import io.github.jerryt92.j2agent.model.KnowledgeCollectionListDto;
import io.github.jerryt92.j2agent.model.KnowledgeDto;
import io.github.jerryt92.j2agent.model.KnowledgeGetListDto;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.vdb.VectorDatabaseService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class KnowledgeService {
    private final VectorDatabaseService vectorDatabaseService;
    private final KnowledgeSourceFileHashMapper knowledgeSourceFileHashMapper;
    private final EmbeddingService embeddingService;

    public KnowledgeService(VectorDatabaseService vectorDatabaseService,
                            KnowledgeSourceFileHashMapper knowledgeSourceFileHashMapper,
                            EmbeddingService embeddingService) {
        this.vectorDatabaseService = vectorDatabaseService;
        this.knowledgeSourceFileHashMapper = knowledgeSourceFileHashMapper;
        this.embeddingService = embeddingService;
    }

    /**
     * 查询当前可用的知识库 collection 列表。
     */
    public KnowledgeCollectionListDto getKnowledgeCollections() {
        List<String> collections = knowledgeSourceFileHashMapper.selectActiveCollectionCounts().stream()
                .map(item -> item.get("collectionName"))
                .map(item -> item == null ? null : String.valueOf(item))
                .filter(StringUtils::isNotBlank)
                .distinct()
                .sorted()
                .toList();
        KnowledgeCollectionListDto result = new KnowledgeCollectionListDto();
        result.setData(collections);
        return result;
    }

    /**
     * 按 collection 从 Milvus 查询知识分片。
     *
     * @param partitionNames 可选，限定 Milvus 分区；null 或空表示全 collection。
     */
    public KnowledgeGetListDto getKnowledge(Integer offset, Integer limit, String search, String collection, List<String> partitionNames) {
        KnowledgeGetListDto result = new KnowledgeGetListDto();
        if (StringUtils.isBlank(collection)) {
            result.setData(List.of());
            return result;
        }
        List<String> effective = partitionNames == null || partitionNames.isEmpty() ? null : partitionNames;
        List<KnowledgeDto> data = vectorDatabaseService.queryKnowledge(
                        collection,
                        offset == null ? 0 : offset,
                        limit == null ? 10 : limit,
                        search,
                        effective
                ).stream()
                .map(this::toKnowledgeDto)
                .toList();
        result.setData(data);
        return result;
    }

    private KnowledgeDto toKnowledgeDto(EmbeddingModel.EmbeddingsQueryItem item) {
        KnowledgeDto knowledgeDto = new KnowledgeDto();
        knowledgeDto.setTextChunkId(item.getTextChunkId());
        knowledgeDto.setOutline(StringUtils.isBlank(item.getQuestion()) ? List.of() : List.of(item.getQuestion()));
        knowledgeDto.setTextChunk(item.getAnswer());
        knowledgeDto.setEmbeddingModel(item.getEmbeddingModel());
        knowledgeDto.setEmbeddingProvider(item.getEmbeddingProvider());
        knowledgeDto.setDimension(embeddingService.getDimension());
        knowledgeDto.setDescription(item.getText());
        knowledgeDto.setSourceFile(item.getSourceFile());
        return knowledgeDto;
    }
}
