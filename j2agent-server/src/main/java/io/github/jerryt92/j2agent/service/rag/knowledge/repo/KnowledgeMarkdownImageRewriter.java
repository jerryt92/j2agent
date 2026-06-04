package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.constants.CommonConstants;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.net.URLEncoder;
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
     * 改写单个 QA 分片 answer 中的图片引用。
     */
    public String rewriteAnswerImages(String sourceFileRelative, String answer) {
        if (StringUtils.isBlank(answer)) {
            return answer;
        }
        String rewritten = rewriteLegacyHints(answer);
        rewritten = rewriteAsciiDocImages(sourceFileRelative, rewritten);
        return rewriteMarkdownImages(sourceFileRelative, rewritten);
    }

    /**
     * 批量改写 QA 分片中的图片 URL。
     */
    public List<MarkdownQaParser.QaSegment> rewriteSegments(String sourceFileRelative,
                                                            List<MarkdownQaParser.QaSegment> segments) {
        return segments.stream()
                .map(segment -> new MarkdownQaParser.QaSegment(
                        segment.segmentId(),
                        segment.question(),
                        rewriteAnswerImages(sourceFileRelative, segment.answer()),
                        segment.sourcePath(),
                        segment.headingPath()))
                .toList();
    }

    /**
     * 将 legacy 提示行转为标准 Markdown 图片语法。
     */
    private String rewriteLegacyHints(String answer) {
        Matcher matcher = LEGACY_HINT.matcher(answer);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("![](" + url + ")"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 改写 Markdown 图片语法中的相对路径。
     */
    private String rewriteMarkdownImages(String sourceFileRelative, String answer) {
        Matcher matcher = MD_IMAGE.matcher(answer);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String alt = matcher.group(1);
            String url = matcher.group(2).trim();
            String rewrittenUrl = rewriteImageUrl(sourceFileRelative, url);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("![" + alt + "](" + rewrittenUrl + ")"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 将 AsciiDoc 块图片语法转为 Markdown 图片语法，便于现有前端按 Markdown 渲染答案。
     */
    private String rewriteAsciiDocImages(String sourceFileRelative, String answer) {
        Matcher matcher = ADOC_IMAGE.matcher(answer);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String url = matcher.group(1).trim();
            String alt = matcher.group(2).trim();
            String rewrittenUrl = rewriteImageUrl(sourceFileRelative, url);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement("![" + alt + "](" + rewrittenUrl + ")"));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    /**
     * 将相对图片路径解析为知识库直链 URL，外链与已有 repo 路径保持不变。
     */
    private String rewriteImageUrl(String sourceFileRelative, String url) {
        if (StringUtils.isBlank(url)) {
            return url;
        }
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) {
            return url;
        }
        if (url.startsWith(CommonConstants.REPO_FILE_URL)) {
            return url;
        }
        if (url.startsWith(CommonConstants.STATIC_FILE_URL)) {
            String relativePath = url.substring(CommonConstants.STATIC_FILE_URL.length());
            return buildRepoFileUrl(sourceFileRelative, relativePath);
        }
        String decodedUrl = url;
        try {
            decodedUrl = URLDecoder.decode(url, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // 保留原始 URL
        }
        return buildRepoFileUrl(sourceFileRelative, decodedUrl);
    }

    /**
     * 基于源文件位置，将相对路径转为知识库直链 URL。
     */
    private String buildRepoFileUrl(String sourceFileRelative, String relativePath) {
        Path sourceDir = Path.of(sourceFileRelative).getParent();
        if (sourceDir == null) {
            sourceDir = Path.of("");
        }
        Path resolved = sourceDir.resolve(relativePath.replace('\\', '/')).normalize();
        String posixPath = resolved.toString().replace('\\', '/');
        return CommonConstants.REPO_FILE_URL + encodePath(posixPath);
    }

    /**
     * 分段 URL 编码，保留路径分隔符。
     */
    private String encodePath(String path) {
        String[] segments = path.split("/");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return builder.toString();
    }
}
