package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown/AsciiDoc 逻辑文本块解析器。
 */
@Component
public class KnowledgeTextChunkParser {

    private static final int DEFAULT_MIN_HEADING_LEVEL = 3;

    public List<TextChunk> parse(String sourcePath, String content) {
        return parse(sourcePath, content, DEFAULT_MIN_HEADING_LEVEL);
    }

    public List<TextChunk> parse(String sourcePath, String content, boolean filenameAsTitle, String filenameTitle) {
        return parse(sourcePath, content, DEFAULT_MIN_HEADING_LEVEL, filenameAsTitle, filenameTitle);
    }

    public List<TextChunk> parse(String sourcePath, String content, int minHeadingLevel) {
        return parse(sourcePath, content, minHeadingLevel, false, null);
    }

    public List<TextChunk> parse(String sourcePath, String content, int minHeadingLevel,
                                 boolean filenameAsTitle, String filenameTitle) {
        if (minHeadingLevel < 1 || minHeadingLevel > 3) {
            throw new IllegalArgumentException("minHeadingLevel 必须为 1–3");
        }
        List<TextChunk> chunks = new ArrayList<>();
        if (StringUtils.isBlank(content)) {
            return chunks;
        }
        String normalizedFilenameTitle = filenameAsTitle ? StringUtils.trimToNull(filenameTitle) : null;
        DocumentFormat format = resolveDocumentFormat(sourcePath);
        String[] lines = content.split("\\r?\\n");
        int effectiveHeadingLevel = resolveEffectiveHeadingLevel(lines, minHeadingLevel, format);
        if (effectiveHeadingLevel == 0) {
            if (normalizedFilenameTitle != null) {
                addChunk(chunks, sourcePath, normalizedFilenameTitle, content);
            }
            return chunks;
        }
        String h1 = null;
        String h2 = null;
        String h3 = null;
        String currentHeadingPath = null;
        StringBuilder currentBody = new StringBuilder();
        for (String line : lines) {
            Heading heading = parseHeading(line, format);
            if (heading == null) {
                if (currentHeadingPath != null) {
                    currentBody.append(line).append('\n');
                }
                continue;
            }
            if (currentHeadingPath != null) {
                addChunk(chunks, sourcePath, currentHeadingPath, currentBody.toString());
            }
            currentBody.setLength(0);
            if (heading.level == 1) {
                h1 = heading.title;
                h2 = null;
                h3 = null;
            } else if (heading.level == 2) {
                h2 = heading.title;
                h3 = null;
            } else {
                h3 = heading.title;
            }
            if (heading.level >= effectiveHeadingLevel) {
                currentHeadingPath = buildHeadingPath(normalizedFilenameTitle, h1, h2, h3);
            } else {
                currentHeadingPath = null;
            }
        }
        if (currentHeadingPath != null) {
            addChunk(chunks, sourcePath, currentHeadingPath, currentBody.toString());
        }
        return chunks;
    }

    private int resolveEffectiveHeadingLevel(String[] lines, int minHeadingLevel, DocumentFormat format) {
        int deepestHeadingLevel = 0;
        for (String line : lines) {
            Heading heading = parseHeading(line, format);
            if (heading != null) {
                deepestHeadingLevel = Math.max(deepestHeadingLevel, heading.level);
            }
        }
        if (deepestHeadingLevel == 0) {
            return 0;
        }
        return Math.min(minHeadingLevel, deepestHeadingLevel);
    }

    private Heading parseHeading(String line, DocumentFormat format) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (format == DocumentFormat.ASCIIDOC) {
            return parseAsciiDocHeading(trimmed);
        }
        return parseMarkdownHeading(trimmed);
    }

    private Heading parseMarkdownHeading(String trimmed) {
        if (trimmed.startsWith("### ")) {
            return new Heading(3, trimmed.substring(4).trim());
        }
        if (trimmed.startsWith("## ")) {
            return new Heading(2, trimmed.substring(3).trim());
        }
        if (trimmed.startsWith("# ")) {
            return new Heading(1, trimmed.substring(2).trim());
        }
        return null;
    }

    private Heading parseAsciiDocHeading(String trimmed) {
        if (trimmed.startsWith("=== ") && !trimmed.startsWith("==== ")) {
            return new Heading(3, trimmed.substring(4).trim());
        }
        if (trimmed.startsWith("== ") && !trimmed.startsWith("=== ")) {
            return new Heading(2, trimmed.substring(3).trim());
        }
        if (trimmed.startsWith("= ") && !trimmed.startsWith("== ")) {
            return new Heading(1, trimmed.substring(2).trim());
        }
        return null;
    }

    private String buildHeadingPath(String filenameTitle, String h1, String h2, String h3) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.isNotBlank(filenameTitle)) {
            parts.add(filenameTitle);
        }
        if (StringUtils.isNotBlank(h1)) {
            parts.add(h1);
        }
        if (StringUtils.isNotBlank(h2)) {
            parts.add(h2);
        }
        if (StringUtils.isNotBlank(h3)) {
            parts.add(h3);
        }
        return String.join(" / ", parts);
    }

    private void addChunk(List<TextChunk> chunks, String sourcePath, String headingPath, String body) {
        if (StringUtils.isBlank(headingPath)) {
            return;
        }
        String cleanBody = body == null ? "" : body.trim();
        boolean emptyBody = StringUtils.isBlank(cleanBody);
        String textChunk = emptyBody ? headingPath : cleanBody;
        chunks.add(new TextChunk(
                UUIDv7Utils.randomUUIDv7(),
                headingPath,
                textChunk,
                sourcePath,
                emptyBody
        ));
    }

    private DocumentFormat resolveDocumentFormat(String sourcePath) {
        String lowerSourcePath = sourcePath == null ? "" : sourcePath.toLowerCase();
        if (lowerSourcePath.endsWith(".adoc") || lowerSourcePath.endsWith(".asciidoc")) {
            return DocumentFormat.ASCIIDOC;
        }
        return DocumentFormat.MARKDOWN;
    }

    private enum DocumentFormat {
        MARKDOWN,
        ASCIIDOC
    }

    private record Heading(int level, String title) {
    }

    /**
     * 逻辑文本块：一个标题下的完整正文存 MySQL，Milvus 侧展开为多向量。
     */
    public record TextChunk(String textChunkId, String headingPath, String textChunk, String sourcePath, boolean emptyBody) {
    }
}
