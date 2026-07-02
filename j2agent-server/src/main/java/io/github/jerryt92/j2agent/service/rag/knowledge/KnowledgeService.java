package io.github.jerryt92.j2agent.service.rag.knowledge;

import io.github.jerryt92.j2agent.model.KnowledgeCollectionListDto;
import io.github.jerryt92.j2agent.model.KnowledgeDto;
import io.github.jerryt92.j2agent.model.KnowledgeGetListDto;
import io.github.jerryt92.j2agent.model.po.KnowledgeTextChunkPo;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoHashTreeService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class KnowledgeService {
    private final KnowledgeTextChunkService knowledgeTextChunkService;
    private final EmbeddingService embeddingService;
    private final KnowledgeRepoMetadataService metadataService;
    private final KnowledgeRepoHashTreeService hashTreeService;

    public KnowledgeService(KnowledgeTextChunkService knowledgeTextChunkService,
                            EmbeddingService embeddingService,
                            KnowledgeRepoMetadataService metadataService,
                            KnowledgeRepoHashTreeService hashTreeService) {
        this.knowledgeTextChunkService = knowledgeTextChunkService;
        this.embeddingService = embeddingService;
        this.metadataService = metadataService;
        this.hashTreeService = hashTreeService;
    }

    /**
     * 查询当前可用的知识库 collection 列表。
     */
    public KnowledgeCollectionListDto getKnowledgeCollections() {
        Set<String> collections = new LinkedHashSet<>();
        collections.addAll(metadataService.listConfiguredCollectionNames());
        collections.addAll(hashTreeService.loadActiveCollectionCounts().keySet());
        collections.removeIf(StringUtils::isBlank);
        List<String> sorted = new ArrayList<>(collections);
        sorted.sort(String::compareTo);
        KnowledgeCollectionListDto result = new KnowledgeCollectionListDto();
        result.setData(sorted);
        return result;
    }

    /**
     * 按 collection 从 MySQL 查询逻辑文本块。
     */
    public KnowledgeGetListDto getKnowledge(Integer offset, Integer limit, String search, String collection, List<String> partitionNames) {
        KnowledgeGetListDto result = new KnowledgeGetListDto();
        if (StringUtils.isBlank(collection)) {
            result.setData(List.of());
            return result;
        }
        List<KnowledgeDto> data = knowledgeTextChunkService.listByCollection(
                        collection,
                        search,
                        offset == null ? 0 : offset,
                        limit == null ? 10 : limit
                ).stream()
                .map(this::toKnowledgeDto)
                .toList();
        result.setData(data);
        return result;
    }

    private KnowledgeDto toKnowledgeDto(KnowledgeTextChunkPo po) {
        KnowledgeDto knowledgeDto = new KnowledgeDto();
        knowledgeDto.setTextChunkId(po.getId());
        knowledgeDto.setOutline(StringUtils.isBlank(po.getHeadingPath()) ? List.of() : List.of(po.getHeadingPath()));
        knowledgeDto.setTextChunk(po.getTextChunk());
        knowledgeDto.setDimension(embeddingService.getDimension());
        knowledgeDto.setSourceFile(po.getSourceFile());
        return knowledgeDto;
    }
}
