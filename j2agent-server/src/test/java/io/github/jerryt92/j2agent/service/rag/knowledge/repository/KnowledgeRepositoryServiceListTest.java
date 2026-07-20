package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.mapper.KnowledgeRepositoryMapper;
import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeRepositoryServiceListTest {
    @TempDir
    Path tempDir;

    @Test
    void listUsesTopLevelDirectoriesAndInfoJson() throws Exception {
        Path root = tempDir.resolve("knowledge-repo");
        Path local = root.resolve("local_kb");
        Path remote = root.resolve("remote_kb");
        Files.createDirectories(local);
        Files.createDirectories(remote);
        Files.writeString(local.resolve("info.json"), """
                {"collection_name":"local_collection","min_heading_level":2,"filename_as_title":true}
                """, StandardCharsets.UTF_8);
        Files.writeString(remote.resolve("info.json"), """
                {"collection_name":"remote_collection","min_heading_level":3,"filename_as_title":false}
                """, StandardCharsets.UTF_8);

        KnowledgeRepositoryPo remoteConfig = new KnowledgeRepositoryPo();
        remoteConfig.setId("remote-id");
        remoteConfig.setRepoCode("remote_kb");
        remoteConfig.setProtocol("GIT");
        remoteConfig.setEnabled(true);
        remoteConfig.setStatus("SYNCED");
        remoteConfig.setRemoteUrl("https://example.com/repo.git");
        remoteConfig.setDefaultBranch("main");
        remoteConfig.setUpdateIntervalMinutes(60);
        KnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper(List.of(remoteConfig));

        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        properties.setRootPath(root.toString());
        KnowledgeRepoMetadataService metadataService = new KnowledgeRepoMetadataService(properties);
        metadataService.init();
        KnowledgeRepositoryCredentialCipher cipher = new KnowledgeRepositoryCredentialCipher(properties);
        KnowledgeRepositoryService service = new KnowledgeRepositoryService(
                mapper, properties, metadataService, null, cipher, List.of());

        List<KnowledgeRepositoryDtos.Item> items = service.list().getData();

        assertEquals(2, items.size());
        KnowledgeRepositoryDtos.Item localItem = items.get(0);
        KnowledgeRepositoryDtos.Item remoteItem = items.get(1);
        assertEquals("local_kb", localItem.getRepoCode());
        assertEquals(List.of("local_collection"), localItem.getCollections());
        assertEquals(2, localItem.getMinHeadingLevel());
        assertTrue(localItem.getFilenameAsTitle());
        assertTrue(localItem.getReadonly());
        assertEquals("remote_kb", remoteItem.getRepoCode());
        assertEquals(List.of("remote_collection"), remoteItem.getCollections());
        assertFalse(remoteItem.getReadonly());
        assertEquals("GIT", remoteItem.getProtocol());
    }

    @Test
    void listIncludesRemoteConfigWhenDirectoryMissing() throws Exception {
        Path root = tempDir.resolve("knowledge-repo");
        Path local = root.resolve("local_kb");
        Files.createDirectories(local);
        Files.writeString(local.resolve("info.json"), """
                {"collection_name":"local_collection"}
                """, StandardCharsets.UTF_8);

        KnowledgeRepositoryPo missingRemoteConfig = new KnowledgeRepositoryPo();
        missingRemoteConfig.setId("missing-id");
        missingRemoteConfig.setRepoCode("missing_remote");
        missingRemoteConfig.setProtocol("GIT");
        missingRemoteConfig.setEnabled(true);
        missingRemoteConfig.setStatus("SYNCED");
        missingRemoteConfig.setRemoteUrl("https://example.com/missing.git");
        missingRemoteConfig.setUpdateIntervalMinutes(60);
        KnowledgeRepositoryMapper mapper = new FakeKnowledgeRepositoryMapper(List.of(missingRemoteConfig));

        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        properties.setRootPath(root.toString());
        KnowledgeRepoMetadataService metadataService = new KnowledgeRepoMetadataService(properties);
        metadataService.init();
        KnowledgeRepositoryCredentialCipher cipher = new KnowledgeRepositoryCredentialCipher(properties);
        KnowledgeRepositoryService service = new KnowledgeRepositoryService(
                mapper, properties, metadataService, null, cipher, List.of());

        List<KnowledgeRepositoryDtos.Item> items = service.list().getData();

        assertEquals(2, items.size());
        KnowledgeRepositoryDtos.Item missingItem = items.get(1);
        assertEquals("missing-id", missingItem.getId());
        assertEquals("missing_remote", missingItem.getRepoCode());
        assertEquals("REMOTE", missingItem.getType());
        assertEquals("GIT", missingItem.getProtocol());
        assertEquals("DIRECTORY_MISSING", missingItem.getStatus());
        assertFalse(missingItem.getReadonly());
        assertEquals("https://example.com/missing.git", missingItem.getRemoteUrl());
        assertEquals(List.of(), missingItem.getCollections());
        assertTrue(missingItem.getLocalPath().endsWith("knowledge-repo/missing_remote"));
    }

    private static class FakeKnowledgeRepositoryMapper implements KnowledgeRepositoryMapper {
        private final List<KnowledgeRepositoryPo> rows;

        private FakeKnowledgeRepositoryMapper(List<KnowledgeRepositoryPo> rows) {
            this.rows = rows;
        }

        @Override
        public List<KnowledgeRepositoryPo> selectAll() {
            return rows;
        }

        @Override
        public List<KnowledgeRepositoryPo> selectRemoteAll() {
            return rows;
        }

        @Override
        public KnowledgeRepositoryPo selectById(String id) {
            return rows.stream().filter(row -> id.equals(row.getId())).findFirst().orElse(null);
        }

        @Override
        public KnowledgeRepositoryPo selectByRepoCode(String repoCode) {
            return rows.stream().filter(row -> repoCode.equals(row.getRepoCode())).findFirst().orElse(null);
        }

        @Override
        public List<KnowledgeRepositoryPo> selectDueRemote(long dueBefore) {
            return rows;
        }

        @Override
        public int insert(KnowledgeRepositoryPo po) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateConfig(KnowledgeRepositoryPo po) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateStatus(String id, String status, String lastError, long updatedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateSyncResult(KnowledgeRepositoryPo po) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteById(String id) {
            throw new UnsupportedOperationException();
        }
    }
}
