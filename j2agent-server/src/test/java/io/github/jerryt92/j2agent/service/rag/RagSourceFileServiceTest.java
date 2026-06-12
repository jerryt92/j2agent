package io.github.jerryt92.j2agent.service.rag;

import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.model.FileDto;
import io.github.jerryt92.j2agent.model.RagInfoDto;
import io.github.jerryt92.j2agent.service.file.StaticFileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagSourceFileServiceTest {

    private StaticFileService staticFileService;
    private RagSourceFileService ragSourceFileService;

    @BeforeEach
    void setUp() {
        staticFileService = mock(StaticFileService.class);
        ragSourceFileService = new RagSourceFileService(staticFileService);
    }

    @Test
    void shouldResolveUniqueMdSourcesAndBuildRepoUrl() {
        String path = "j2agent-docs/foo/bar.md";
        when(staticFileService.getKnowledgeRepoFile(eq(path)))
                .thenReturn(new ByteArrayResource("content".getBytes()));
        Document doc1 = Document.builder()
                .text("chunk-a")
                .metadata(Map.of("sourceFile", path, "textChunkId", "id-1"))
                .build();
        Document doc2 = Document.builder()
                .text("chunk-b")
                .metadata(Map.of("sourceFile", path, "textChunkId", "id-2"))
                .build();

        RagSourceFileService.ResolvedRagSources resolved =
                ragSourceFileService.resolveUniqueMdSources(List.of(doc1, doc2));

        assertEquals(1, resolved.srcFiles().size());
        FileDto fileDto = resolved.srcFiles().getFirst();
        assertEquals("bar.md", fileDto.getFullFileName());
        assertEquals(path, fileDto.getRelativePath());
        assertEquals(RagSourceFileService.stablePathId(path), fileDto.getId());
        assertTrue(fileDto.getUrl().startsWith(CommonConstants.REPO_FILE_URL));
        assertFalse(fileDto.getUrl().contains("%2F"));
        assertEquals(1, resolved.ragInfos().size());
        RagInfoDto ragInfo = resolved.ragInfos().getFirst();
        assertEquals("id-1", ragInfo.getTextChunkId());
        assertEquals(fileDto, ragInfo.getSrcFile());
    }

    @Test
    void shouldIgnoreNonMdAndMissingFiles() {
        when(staticFileService.getKnowledgeRepoFile(eq("docs/readme.txt"))).thenReturn(null);
        Document nonMd = Document.builder()
                .text("x")
                .metadata(Map.of("sourceFile", "docs/readme.txt"))
                .build();
        Document missingMd = Document.builder()
                .text("y")
                .metadata(Map.of("sourceFile", "docs/missing.md"))
                .build();
        Document ragSystem = Document.builder()
                .text("fallback")
                .metadata(Map.of("sourceFile", "rag-system"))
                .build();

        RagSourceFileService.ResolvedRagSources resolved =
                ragSourceFileService.resolveUniqueMdSources(List.of(nonMd, missingMd, ragSystem));

        assertTrue(resolved.isEmpty());
    }
}
