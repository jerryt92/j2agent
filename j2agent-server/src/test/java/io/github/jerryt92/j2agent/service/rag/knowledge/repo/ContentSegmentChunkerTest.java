package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentSegmentChunkerTest {

    private ContentSegmentChunker newChunker(int maxLen, int overlap) {
        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        properties.setContentSegmentChars(maxLen);
        properties.setContentSegmentOverlapChars(overlap);
        return new ContentSegmentChunker(properties);
    }

    @Test
    void chunk_shortText_returnsSingleSegment() {
        ContentSegmentChunker chunker = newChunker(10, 2);
        List<String> segments = chunker.chunk("short text");
        assertEquals(1, segments.size());
        assertEquals("short text", segments.getFirst());
    }

    @Test
    void chunk_overlapZero_partitionsWithoutOverlap() {
        ContentSegmentChunker chunker = newChunker(10, 0);
        String text = "0123456789".repeat(3);
        List<String> segments = chunker.chunk(text);
        assertEquals(3, segments.size());
        int pos = 0;
        for (String segment : segments) {
            assertEquals(segment, text.substring(pos, pos + segment.length()));
            pos += segment.length();
        }
        assertEquals(text.length(), pos);
    }

    @Test
    void chunk_overlapPositive_stepsWithConfiguredOverlap() {
        ContentSegmentChunker chunker = newChunker(10, 2);
        String text = "0123456789".repeat(3);
        List<String> segments = chunker.chunk(text);
        assertTrue(segments.size() >= 3);
        assertEquals("0123456789", segments.getFirst());
        assertEquals("8901234567", segments.get(1));
        assertEquals(segments.get(0).substring(8), segments.get(1).substring(0, 2));
    }

    @Test
    void chunk_prefersNewlineBreak() {
        ContentSegmentChunker chunker = newChunker(20, 0);
        String text = "line one content\nline two content here";
        List<String> segments = chunker.chunk(text);
        assertTrue(segments.size() >= 2);
        assertTrue(segments.getFirst().endsWith("\n"));
    }
}
