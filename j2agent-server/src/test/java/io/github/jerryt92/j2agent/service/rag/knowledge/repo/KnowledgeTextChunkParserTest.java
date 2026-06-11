package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeTextChunkParserTest {

    private final KnowledgeTextChunkParser parser = new KnowledgeTextChunkParser();

    @Test
    void parse_emptyBody_usesHeadingAsTextChunk() {
        String content = """
                # Section
                ## Sub
                ### Leaf
                """;
        List<KnowledgeTextChunkParser.TextChunk> chunks = parser.parse("doc.md", content);
        assertEquals(1, chunks.size());
        KnowledgeTextChunkParser.TextChunk chunk = chunks.getFirst();
        assertEquals("Section / Sub / Leaf", chunk.headingPath());
        assertEquals("Section / Sub / Leaf", chunk.textChunk());
        assertTrue(chunk.emptyBody());
        assertFalse(chunk.textChunkId().isBlank());
    }

    @Test
    void parse_withBody_storesBodyAsTextChunk() {
        String content = """
                ### Title
                body line one
                body line two
                """;
        List<KnowledgeTextChunkParser.TextChunk> chunks = parser.parse("doc.md", content);
        assertEquals(1, chunks.size());
        KnowledgeTextChunkParser.TextChunk chunk = chunks.getFirst();
        assertEquals("Title", chunk.headingPath());
        assertEquals("body line one\nbody line two", chunk.textChunk());
        assertFalse(chunk.emptyBody());
    }

    @Test
    void parse_filenameAsTitleWithoutHeadings_usesFilenameAsChunk() {
        String content = "plain content without headings";
        List<KnowledgeTextChunkParser.TextChunk> chunks = parser.parse(
                "manual.md", content, true, "manual");
        assertEquals(1, chunks.size());
        assertEquals("manual", chunks.getFirst().headingPath());
        assertEquals("plain content without headings", chunks.getFirst().textChunk());
    }
}
