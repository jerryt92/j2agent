package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.utils.HashUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown/AsciiDoc QA 分片解析器。
 */
@Component
public class MarkdownQaParser {

    private static final int DEFAULT_MIN_HEADING_LEVEL = 3;

    /**
     * 使用默认最小标题级别（3，即仅 ###/=== 及以下开启分片）解析文档。
     */
    public List<QaSegment> parse(String sourcePath, String content) {
        return parse(sourcePath, content, DEFAULT_MIN_HEADING_LEVEL);
    }

    /**
     * 将文档按默认标题级别解析为 QA 分片，可选将文件名作为标题链前缀。
     */
    public List<QaSegment> parse(String sourcePath, String content, boolean filenameAsTitle, String filenameTitle) {
        return parse(sourcePath, content, DEFAULT_MIN_HEADING_LEVEL, filenameAsTitle, filenameTitle);
    }

    /**
     * 将文档按 #/##/### 或 =/==/=== 标题解析为 QA 分片。
     *
     * @param minHeadingLevel 优先分片最小标题级别：Markdown 为 1=#，2=##，3=###；AsciiDoc 为 1=，2==，3===；若文档标题深度达不到该级别，则退到文档中存在的最深标题级别
     */
    public List<QaSegment> parse(String sourcePath, String content, int minHeadingLevel) {
        return parse(sourcePath, content, minHeadingLevel, false, null);
    }

    /**
     * 将文档按 #/##/### 或 =/==/=== 标题解析为 QA 分片。
     *
     * @param minHeadingLevel 优先分片最小标题级别：Markdown 为 1=#，2=##，3=###；AsciiDoc 为 1=，2==，3===；若文档标题深度达不到该级别，则退到文档中存在的最深标题级别
     * @param filenameAsTitle 是否将文件名作为标题链最前缀
     * @param filenameTitle   文件名标题，通常为去掉文档后缀的文件名
     */
    public List<QaSegment> parse(String sourcePath, String content, int minHeadingLevel,
                                 boolean filenameAsTitle, String filenameTitle) {
        if (minHeadingLevel < 1 || minHeadingLevel > 3) {
            throw new IllegalArgumentException("minHeadingLevel 必须为 1–3");
        }
        List<QaSegment> segments = new ArrayList<>();
        if (StringUtils.isBlank(content)) {
            return segments;
        }
        String normalizedFilenameTitle = filenameAsTitle ? StringUtils.trimToNull(filenameTitle) : null;
        DocumentFormat format = resolveDocumentFormat(sourcePath);
        String[] lines = content.split("\\r?\\n");
        int effectiveHeadingLevel = resolveEffectiveHeadingLevel(lines, minHeadingLevel, format);
        if (effectiveHeadingLevel == 0) {
            if (normalizedFilenameTitle != null) {
                addSegment(segments, sourcePath, normalizedFilenameTitle, content);
            }
            return segments;
        }
        String h1 = null;
        String h2 = null;
        String h3 = null;
        String currentHeadingPath = null;
        StringBuilder currentAnswer = new StringBuilder();
        for (String line : lines) {
            Heading heading = parseHeading(line, format);
            if (heading == null) {
                if (currentHeadingPath != null) {
                    currentAnswer.append(line).append('\n');
                }
                continue;
            }
            if (currentHeadingPath != null) {
                addSegment(segments, sourcePath, currentHeadingPath, currentAnswer.toString());
            }
            currentAnswer.setLength(0);
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
            addSegment(segments, sourcePath, currentHeadingPath, currentAnswer.toString());
        }
        return segments;
    }

    /**
     * 解析实际用于分片的标题级别：优先使用配置级别，文档达不到时退到实际最深标题。
     */
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

    /**
     * 解析标题行。
     */
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

    /**
     * 解析 Markdown 标题行。
     */
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

    /**
     * 解析 AsciiDoc 标题行。
     */
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

    /**
     * 组装标题链。
     */
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

    /**
     * 生成并追加分片。
     */
    private void addSegment(List<QaSegment> segments, String sourcePath, String headingPath, String answer) {
        String cleanAnswer = answer == null ? "" : answer.trim();
        if (StringUtils.isBlank(headingPath) || StringUtils.isBlank(cleanAnswer)) {
            return;
        }
        String segmentIdSeed = sourcePath + "|" + headingPath + "|" + cleanAnswer;
        segments.add(new QaSegment(
                buildSha1(segmentIdSeed),
                headingPath,
                cleanAnswer,
                sourcePath,
                headingPath
        ));
    }

    /**
     * 计算稳定分片主键。
     */
    private String buildSha1(String text) {
        try {
            return HashUtil.getMessageDigest(text.getBytes(StandardCharsets.UTF_8), HashUtil.MdAlgorithm.SHA1);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("计算分片主键失败", e);
        }
    }

    /**
     * 根据文件扩展名识别文档格式；未知格式沿用 Markdown 行为，兼容历史调用。
     */
    private DocumentFormat resolveDocumentFormat(String sourcePath) {
        String lowerSourcePath = sourcePath == null ? "" : sourcePath.toLowerCase();
        if (lowerSourcePath.endsWith(".adoc") || lowerSourcePath.endsWith(".asciidoc")) {
            return DocumentFormat.ASCIIDOC;
        }
        return DocumentFormat.MARKDOWN;
    }

    /**
     * 支持的文档格式。
     */
    private enum DocumentFormat {
        MARKDOWN,
        ASCIIDOC
    }

    /**
     * 文档标题对象。
     */
    private record Heading(int level, String title) {
    }

    /**
     * QA 分片对象。
     */
    public record QaSegment(String segmentId, String question, String answer, String sourcePath, String headingPath) {
    }
}
