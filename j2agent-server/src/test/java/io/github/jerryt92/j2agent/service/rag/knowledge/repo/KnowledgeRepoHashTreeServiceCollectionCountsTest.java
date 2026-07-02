package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.mapper.KnowledgeSourceFileHashMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * KnowledgeRepoHashTreeService collection 计数读取测试。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeRepoHashTreeServiceCollectionCountsTest {

    @Mock
    private KnowledgeSourceFileHashMapper mapper;

    @InjectMocks
    private KnowledgeRepoHashTreeService hashTreeService;

    @Test
    void loadActiveCollectionCounts_readsQuotedPostgresAliases() {
        when(mapper.selectActiveCollectionCounts()).thenReturn(List.of(
                Map.of("collectionName", "rc_wiki", "fileCount", 3L)
        ));

        assertEquals(Map.of("rc_wiki", 3L), hashTreeService.loadActiveCollectionCounts());
    }
}
