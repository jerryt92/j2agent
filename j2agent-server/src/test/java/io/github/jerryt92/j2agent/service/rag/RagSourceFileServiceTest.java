package io.github.jerryt92.j2agent.service.rag;

import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.model.FileDto;
import io.github.jerryt92.j2agent.model.RagInfoDto;
import io.github.jerryt92.j2agent.model.po.KnowledgeTextChunkPo;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeTextChunkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagSourceFileServiceTest {

    private KnowledgeTextChunkService knowledgeTextChunkService;
    private RagSourceFileService ragSourceFileService;

    @BeforeEach
    void setUp() {
        knowledgeTextChunkService = mock(KnowledgeTextChunkService.class);
        when(knowledgeTextChunkService.getByIds(any())).thenReturn(Map.of());
        ragSourceFileService = new RagSourceFileService(knowledgeTextChunkService);
    }

    @Test
    void shouldResolveUniqueMdSourcesAndBuildRepoUrl() {
        String path = "j2agent-docs/foo/bar.md";
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
    void shouldResolveMultipleDistinctMdSources() {
        String pathA = "j2agent-docs/foo/a.md";
        String pathB = "j2agent-docs/bar/b.md";
        Document doc1 = Document.builder()
                .text("chunk-a")
                .metadata(Map.of("sourceFile", pathA, "textChunkId", "id-a"))
                .build();
        Document doc2 = Document.builder()
                .text("chunk-b")
                .metadata(Map.of("sourceFile", pathB, "textChunkId", "id-b"))
                .build();

        RagSourceFileService.ResolvedRagSources resolved =
                ragSourceFileService.resolveUniqueMdSources(List.of(doc1, doc2));

        assertEquals(2, resolved.srcFiles().size());
        assertEquals(2, resolved.ragInfos().size());
        assertEquals("a.md", resolved.srcFiles().get(0).getFullFileName());
        assertEquals("b.md", resolved.srcFiles().get(1).getFullFileName());
    }

    @Test
    void shouldResolveMdEvenWhenDiskMissing() {
        Document missingMd = Document.builder()
                .text("y")
                .metadata(Map.of("sourceFile", "docs/missing.md", "textChunkId", "id-m"))
                .build();
        Document nonMd = Document.builder()
                .text("x")
                .metadata(Map.of("sourceFile", "docs/readme.txt"))
                .build();
        Document ragSystem = Document.builder()
                .text("fallback")
                .metadata(Map.of("sourceFile", "rag-system"))
                .build();

        RagSourceFileService.ResolvedRagSources resolved =
                ragSourceFileService.resolveUniqueMdSources(List.of(nonMd, missingMd, ragSystem));

        assertEquals(1, resolved.srcFiles().size());
        assertEquals("missing.md", resolved.srcFiles().getFirst().getFullFileName());
        assertEquals("docs/missing.md", resolved.srcFiles().getFirst().getRelativePath());
    }

    @Test
    void shouldResolveThreeMdWhenOnlyOneOnDisk() {
        String pathA = "docs/a.md";
        String pathB = "docs/b.md";
        String pathC = "docs/c.md";
        Document docA = Document.builder()
                .text("a")
                .metadata(Map.of("sourceFile", pathA, "textChunkId", "id-a"))
                .build();
        Document docB = Document.builder()
                .text("b")
                .metadata(Map.of("sourceFile", pathB, "textChunkId", "id-b"))
                .build();
        Document docC = Document.builder()
                .text("c")
                .metadata(Map.of("sourceFile", pathC, "textChunkId", "id-c"))
                .build();

        RagSourceFileService.ResolvedRagSources resolved =
                ragSourceFileService.resolveUniqueMdSources(List.of(docA, docB, docC));

        assertEquals(3, resolved.srcFiles().size());
        assertEquals(3, resolved.ragInfos().size());
    }

    @Test
    void shouldNormalizeBackslashAndLeadingDotPathsToSingleSource() {
        Document doc1 = Document.builder()
                .text("a")
                .metadata(Map.of("sourceFile", "folder/a.md", "textChunkId", "id-1"))
                .build();
        Document doc2 = Document.builder()
                .text("a2")
                .metadata(Map.of("sourceFile", "folder\\a.md", "textChunkId", "id-2"))
                .build();
        Document doc3 = Document.builder()
                .text("b")
                .metadata(Map.of("sourceFile", "./folder/b.md", "textChunkId", "id-3"))
                .build();

        RagSourceFileService.ResolvedRagSources resolved =
                ragSourceFileService.resolveUniqueMdSources(List.of(doc1, doc2, doc3));

        assertEquals(2, resolved.srcFiles().size());
    }

    @Test
    void shouldResolveMdAndAdocSourcesTogether() {
        String mdPath = "网管安装纳管相关/安装部署.md";
        String adocPath = "wiki/办公/武汉4楼打印机.adoc";
        Document mdDoc = Document.builder()
                .text("md chunk")
                .metadata(Map.of("sourceFile", mdPath, "textChunkId", "id-md"))
                .build();
        Document adocDoc = Document.builder()
                .text("adoc chunk")
                .metadata(Map.of("sourceFile", adocPath, "textChunkId", "id-adoc"))
                .build();

        RagSourceFileService.ResolvedRagSources resolved =
                ragSourceFileService.resolveUniqueMdSources(List.of(mdDoc, adocDoc));

        assertEquals(2, resolved.srcFiles().size());
        assertEquals("安装部署.md", resolved.srcFiles().get(0).getFullFileName());
        assertEquals("武汉4楼打印机.adoc", resolved.srcFiles().get(1).getFullFileName());
    }

    @Test
    void shouldFallbackSourceFileFromTextChunkWhenMetadataInvalid() {
        String path = "docs/printer.md";
        KnowledgeTextChunkPo chunk = new KnowledgeTextChunkPo();
        chunk.setId("chunk-printer");
        chunk.setSourceFile(path);
        when(knowledgeTextChunkService.getByIds(List.of("chunk-printer")))
                .thenReturn(Map.of("chunk-printer", chunk));
        Document doc = Document.builder()
                .text("printer")
                .metadata(Map.of("sourceFile", "heading-only-path", "textChunkId", "chunk-printer"))
                .build();

        RagSourceFileService.ResolvedRagSources resolved =
                ragSourceFileService.resolveUniqueMdSources(List.of(doc));

        assertEquals(1, resolved.srcFiles().size());
        assertEquals(path, resolved.srcFiles().getFirst().getRelativePath());
    }
}
