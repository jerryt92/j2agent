package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.RedisKeyNamespaces;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class KnowledgeRepoMaintenanceLockServiceTest {

    @Mock
    private org.redisson.api.RedissonClient redissonClient;

    private KnowledgeRepoMaintenanceLockService lockService;

    @BeforeEach
    void setUp() {
        lockService = new KnowledgeRepoMaintenanceLockService(redissonClient, new RedisKeyNamespaces("test-app"));
    }

    @Test
    void repoRootHash_normalizesPath() {
        Path a = Path.of("/tmp/knowledge-repo");
        Path b = Path.of("/tmp/knowledge-repo/.");
        assertEquals(lockService.repoRootHash(a), lockService.repoRootHash(b));
    }

    @Test
    void repoRootHash_differsForDifferentRoots() {
        Path a = Path.of("/tmp/repo-a");
        Path b = Path.of("/tmp/repo-b");
        assertNotEquals(lockService.repoRootHash(a), lockService.repoRootHash(b));
    }

    @Test
    void repoRootHash_rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> lockService.repoRootHash(null));
    }
}
