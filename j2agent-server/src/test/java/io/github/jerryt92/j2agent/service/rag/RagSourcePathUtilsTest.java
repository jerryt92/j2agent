package io.github.jerryt92.j2agent.service.rag;

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
}
