package io.github.jerryt92.j2agent.service.rag;

import io.github.jerryt92.j2agent.model.FileDto;
import io.github.jerryt92.j2agent.service.file.StaticFileService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RagSourcePathUtilsTest {

    @Test
    void shouldNormalizeCommonPathVariants() {
        assertEquals("a/b.md", RagSourcePathUtils.normalizeKbSourceRelativePath("a\\b.md"));
        assertEquals("a/b.md", RagSourcePathUtils.normalizeKbSourceRelativePath("./a/b.md"));
        assertEquals("a/b.md", RagSourcePathUtils.normalizeKbSourceRelativePath("/a/b.md"));
        assertEquals("a/b.md", RagSourcePathUtils.normalizeKbSourceRelativePath("a//b.md"));
        assertEquals("a/b.md", RagSourcePathUtils.normalizeKbSourceRelativePath("a/b.md#section"));
    }

    @Test
    void shouldAcceptAdocAndAsciidocExtensions() {
        assertEquals("wiki/办公/武汉4楼打印机.adoc",
                RagSourcePathUtils.normalizeKbSourceRelativePath("wiki/办公/武汉4楼打印机.adoc"));
        assertEquals("docs/guide.asciidoc",
                RagSourcePathUtils.normalizeKbSourceRelativePath("./docs/guide.asciidoc"));
    }

    @Test
    void shouldRejectUnsupportedPaths() {
        assertNull(RagSourcePathUtils.normalizeKbSourceRelativePath("readme.txt"));
        assertNull(RagSourcePathUtils.normalizeKbSourceRelativePath("rag-system"));
    }

    @Test
    void shouldNormalizeRepoUrlAndEncodedSlashVariantsToSamePath() {
        assertEquals("wiki/doc.md",
                RagSourcePathUtils.normalizeKbSourceRelativePath("wiki%2Fdoc.md"));
        assertEquals("wiki/doc.md",
                RagSourcePathUtils.normalizeKbSourceRelativePath(
                        "/v1/rest/j2agent/file/repo/wiki%2Fdoc.md"));
    }

    @Test
    void shouldDedupeByRelativePathOrRepoUrl() {
        FileDto byPath = new FileDto()
                .relativePath("docs/a.md")
                .url(StaticFileService.toRepoFileUrl("docs/a.md"));
        FileDto byUrlOnly = new FileDto()
                .url(StaticFileService.toRepoFileUrl("docs/a.md"));

        assertEquals(RagSourcePathUtils.sourceDedupeKey(byPath),
                RagSourcePathUtils.sourceDedupeKey(byUrlOnly));
    }
}
