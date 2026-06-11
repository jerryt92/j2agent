package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentSegmentChunkerTest {

    private ContentSegmentChunker chunker;

    @BeforeEach
    void setUp() {
        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        properties.setContentSegmentChars(10);
        properties.setContentSegmentOverlapChars(2);
        chunker = new ContentSegmentChunker(properties);
    }

    @Test
    void chunk_shortText_returnsSingleSegment() {
        List<String> segments = chunker.chunk("short text");
        assertEquals(1, segments.size());
        assertEquals("short text", segments.getFirst());
    }

    @Test
    void chunk_longText_returnsMultipleOverlappingSegments() {
        String text = "0123456789012345678901234567890";
        List<String> segments = chunker.chunk(text);
        assertTrue(segments.size() >= 3);
        assertEquals("0123456789", segments.getFirst());
    }

    @Test
    void chunk_prefersNewlineBreak() {
        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        properties.setContentSegmentChars(20);
        properties.setContentSegmentOverlapChars(0);
        ContentSegmentChunker newlineChunker = new ContentSegmentChunker(properties);
        String text = "line one content\nline two content here";
        List<String> segments = newlineChunker.chunk(text);
        assertTrue(segments.size() >= 2);
        assertTrue(segments.getFirst().endsWith("\n"));
    }
}
