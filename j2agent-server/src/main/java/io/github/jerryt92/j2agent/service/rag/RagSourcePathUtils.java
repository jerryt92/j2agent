package io.github.jerryt92.j2agent.service.rag;

import io.github.jerryt92.j2agent.model.FileDto;
import io.github.jerryt92.j2agent.service.file.StaticFileService;
import org.apache.commons.lang3.StringUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * RAG 来源文件路径规范化工具。
 */
public final class RagSourcePathUtils {

    private static final String RAG_SYSTEM_SOURCE = "rag-system";
    private static final String REPO_FILE_PATH_MARKER = "/file/repo/";

    /** 与 {@link io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoSyncService} 入库后缀一致。 */
    private static final List<String> KB_SOURCE_EXTENSIONS = List.of(".asciidoc", ".adoc", ".md");

    private RagSourcePathUtils() {
    }

    /**
     * 将检索元数据中的路径规范为知识库相对文档路径（.md / .adoc / .asciidoc）；无法识别时返回 null。
     */
    public static String normalizeKbSourceRelativePath(Object raw) {
        if (raw == null) {
            return null;
        }
        String path = raw.toString().trim();
        if (StringUtils.isBlank(path) || RAG_SYSTEM_SOURCE.equals(path)) {
            return null;
        }
        path = path.replace('\\', '/');
        if (path.contains(REPO_FILE_PATH_MARKER)) {
            String fromUrl = StaticFileService.extractRepoRelativePath(path);
            if (StringUtils.isNotBlank(fromUrl)) {
                path = fromUrl;
            }
        }
        if (path.contains("%")) {
            try {
                path = URLDecoder.decode(path, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // 保留原值，避免畸形编码导致来源丢失
            }
        }
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        path = path.replaceAll("/+", "/");
        int hashIdx = path.indexOf('#');
        if (hashIdx >= 0) {
            path = path.substring(0, hashIdx);
        }
        int queryIdx = path.indexOf('?');
        if (queryIdx >= 0) {
            path = path.substring(0, queryIdx);
        }
        path = path.trim();
        if (path.isEmpty() || !hasKbSourceExtension(path)) {
            return null;
        }
        return path;
    }

    /** 路径是否可解析为知识库文档来源。 */
    public static boolean isKbSourceRelativePath(Object raw) {
        return normalizeKbSourceRelativePath(raw) != null;
    }

    /**
     * RAG 来源去重键：优先规范化相对路径，否则从 repo 直链反解；无法识别时回退 url。
     */
    public static String sourceDedupeKey(FileDto fileDto) {
        if (fileDto == null) {
            return null;
        }
        String fromRelative = normalizeKbSourceRelativePath(fileDto.getRelativePath());
        if (fromRelative != null) {
            return fromRelative;
        }
        if (StringUtils.isNotBlank(fileDto.getUrl())) {
            String normalizedUrl = StaticFileService.normalizeRepoFileUrl(fileDto.getUrl());
            String fromUrl = normalizeKbSourceRelativePath(StaticFileService.extractRepoRelativePath(normalizedUrl));
            if (fromUrl != null) {
                return fromUrl;
            }
            return normalizedUrl.trim();
        }
        return null;
    }

    private static boolean hasKbSourceExtension(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        for (String extension : KB_SOURCE_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
