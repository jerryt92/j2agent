package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentSegmentChunkTestMdTest {

    private static final Pattern LONG_BODY_SECTION = Pattern.compile(
            "### 长正文块\\s+\\n(?:正文约[^\\n]+\\n\\n)(.+?)\\n### 空正文标题块",
            Pattern.DOTALL);

    @Test
    void testMarkdown_longBody_chunksWithConfiguredOverlap() throws Exception {
        String markdown = new ClassPathResource("knowledge/content_segment_chunk_test.md")
                .getContentAsString(StandardCharsets.UTF_8);
        Matcher matcher = LONG_BODY_SECTION.matcher(markdown);
        assertTrue(matcher.find(), "long body section not found in test markdown");
        String longBody = matcher.group(1).trim();
        assertTrue(longBody.length() > 2000, "long body should exceed segment window");

        KnowledgeRepoProperties properties = new KnowledgeRepoProperties();
        properties.setContentSegmentChars(2000);
        properties.setContentSegmentOverlapChars(200);
        ContentSegmentChunker chunker = new ContentSegmentChunker(properties);
        List<String> segments = chunker.chunk(longBody);
        assertEquals(4, segments.size(), "long body expects 4 overlapping segments at window=2000 overlap=200");
    }
}
