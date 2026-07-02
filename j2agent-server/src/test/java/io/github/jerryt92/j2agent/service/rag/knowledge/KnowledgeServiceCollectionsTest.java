package io.github.jerryt92.j2agent.service.rag.knowledge;

import io.github.jerryt92.j2agent.model.KnowledgeCollectionListDto;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoHashTreeService;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * KnowledgeService collection 列表合并逻辑测试。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceCollectionsTest {

    @Mock
    private KnowledgeTextChunkService knowledgeTextChunkService;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private KnowledgeRepoMetadataService metadataService;
    @Mock
    private KnowledgeRepoHashTreeService hashTreeService;

    @InjectMocks
    private KnowledgeService knowledgeService;

    @Test
    void getKnowledgeCollections_mergesConfiguredAndActiveCollections() {
        when(metadataService.listConfiguredCollectionNames()).thenReturn(Set.of("rc_wiki", "inc_qa"));
        when(hashTreeService.loadActiveCollectionCounts()).thenReturn(Map.of("rc_wiki", 2L, "legacy_kb", 1L));

        KnowledgeCollectionListDto result = knowledgeService.getKnowledgeCollections();

        assertEquals(List.of("inc_qa", "legacy_kb", "rc_wiki"), result.getData());
    }
}
