package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.service.file.StaticFileService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库文档图片 URL 改写器，将相对路径转为可直链访问的 API 地址。
 */
@Component
public class KnowledgeMarkdownImageRewriter {
    private static final Pattern LEGACY_HINT = Pattern.compile(
            "\\{\\s*role:\\s*'system',\\s*content:\\s*'以Markdown\\s*格式展示图片，图片地址为：([^']+)'\\s*}");
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[([^\\]]*)]\\(([^)]+)\\)");
    private static final Pattern ADOC_IMAGE = Pattern.compile("(?m)^image::([^\\[]+)\\[([^\\]]*)]$");

    /**
     * 改写 text_chunk 正文中的图片引用。
     */
    public String rewriteTextChunkImages(String sourceFileRelative, String textChunk) {
        if (StringUtils.isBlank(textChunk)) {
            return textChunk;
        }
        String rewritten = rewriteLegacyHints(textChunk);
        rewritten = rewriteAsciiDocImages(sourceFileRelative, rewritten);
        return rewriteMarkdownImages(sourceFileRelative, rewritten);
    }

    /**
     * 批量改写逻辑文本块中的图片 URL。
     */
    public List<KnowledgeTextChunkParser.TextChunk> rewriteChunks(String sourceFileRelative,
                                                                  List<KnowledgeTextChunkParser.TextChunk> chunks) {
        return chunks.stream()
                .map(chunk -> new KnowledgeTextChunkParser.TextChunk(
                        chunk.textChunkId(),
                        chunk.headingPath(),
                        rewriteTextChunkImages(sourceFileRelative, chunk.textChunk()),
                        chunk.sourcePath(),
                        chunk.emptyBody()))
                .toList();
    }

    private String rewriteLegacyHints(String textChunk) {
        Matcher matcher = LEGACY_HINT.matcher(textChunk);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("![](" + url + ")"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String rewriteMarkdownImages(String sourceFileRelative, String textChunk) {
        Matcher matcher = MD_IMAGE.matcher(textChunk);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String alt = matcher.group(1);
            String rawUrl = matcher.group(2).trim();
            String rewrittenUrl = rewriteImageUrl(sourceFileRelative, rawUrl);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("![" + alt + "](" + rewrittenUrl + ")"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String rewriteAsciiDocImages(String sourceFileRelative, String textChunk) {
        Matcher matcher = ADOC_IMAGE.matcher(textChunk);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String rawUrl = matcher.group(1).trim();
            String attrs = matcher.group(2);
            String rewrittenUrl = rewriteImageUrl(sourceFileRelative, rawUrl);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("image::" + rewrittenUrl + "[" + attrs + "]"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String rewriteImageUrl(String sourceFileRelative, String rawUrl) {
        if (StringUtils.isBlank(rawUrl)) {
            return rawUrl;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("/")) {
            return trimmed;
        }
        try {
            String decoded = URLDecoder.decode(trimmed, StandardCharsets.UTF_8);
            Path sourceDir = Path.of(sourceFileRelative).getParent();
            String normalizedRelative = decoded.replace("\\", "/");
            if (normalizedRelative.startsWith("./")) {
                normalizedRelative = normalizedRelative.substring(2);
            }
            Path resolved = sourceDir == null
                    ? Path.of(normalizedRelative)
                    : sourceDir.resolve(normalizedRelative).normalize();
            return StaticFileService.toRepoFileUrl(resolved.toString().replace("\\", "/"));
        } catch (Exception e) {
            return trimmed;
        }
    }
}
