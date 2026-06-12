package io.github.jerryt92.j2agent.service.rag;

import io.github.jerryt92.j2agent.model.FileDto;
import io.github.jerryt92.j2agent.model.RagInfoDto;
import io.github.jerryt92.j2agent.service.file.StaticFileService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 从 RAG 检索 Document 提取知识库 .md 源文件，经 {@link StaticFileService} 校验后构建来源链接。
 */
@Slf4j
@Service
public class RagSourceFileService {

    private static final String METADATA_SOURCE_FILE = "sourceFile";
    private static final String METADATA_TEXT_CHUNK_ID = "textChunkId";
    private static final String RAG_SYSTEM_SOURCE = "rag-system";

    private final StaticFileService staticFileService;

    public RagSourceFileService(StaticFileService staticFileService) {
        this.staticFileService = staticFileService;
    }

    /**
     * 解析检索命中的唯一 .md 源文件列表及对应 {@link RagInfoDto}。
     */
    public ResolvedRagSources resolveUniqueMdSources(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return ResolvedRagSources.empty();
        }
        LinkedHashMap<String, RagInfoDto> byPath = new LinkedHashMap<>();
        for (Document document : documents) {
            if (document == null || document.getMetadata() == null) {
                continue;
            }
            String relativePath = normalizeRelativePath(document.getMetadata().get(METADATA_SOURCE_FILE));
            if (relativePath == null) {
                continue;
            }
            if (byPath.containsKey(relativePath)) {
                continue;
            }
            FileDto fileDto = buildFileDtoIfExists(relativePath);
            if (fileDto == null) {
                continue;
            }
            RagInfoDto ragInfo = new RagInfoDto()
                    .textChunkId(stringMetadata(document.getMetadata().get(METADATA_TEXT_CHUNK_ID)))
                    .srcFile(fileDto);
            byPath.put(relativePath, ragInfo);
        }
        if (byPath.isEmpty()) {
            return ResolvedRagSources.empty();
        }
        List<RagInfoDto> ragInfos = new ArrayList<>(byPath.values());
        List<FileDto> srcFiles = ragInfos.stream()
                .map(RagInfoDto::getSrcFile)
                .toList();
        return new ResolvedRagSources(srcFiles, ragInfos);
    }

    private FileDto buildFileDtoIfExists(String relativePath) {
        Resource resource = staticFileService.getKnowledgeRepoFile(relativePath);
        if (resource == null || !resource.exists()) {
            log.debug("RAG 来源文件未找到，跳过: {}", relativePath);
            return null;
        }
        return new FileDto()
                .id(stablePathId(relativePath))
                .fullFileName(Path.of(relativePath).getFileName().toString())
                .relativePath(relativePath)
                .url(StaticFileService.toRepoFileUrl(relativePath));
    }

    private static String normalizeRelativePath(Object raw) {
        if (raw == null) {
            return null;
        }
        String path = raw.toString().trim().replace('\\', '/');
        if (StringUtils.isBlank(path) || RAG_SYSTEM_SOURCE.equals(path)) {
            return null;
        }
        if (path.startsWith("./")) {
            path = path.substring(2);
        }
        if (!path.toLowerCase().endsWith(".md")) {
            return null;
        }
        return path;
    }

    private static String stringMetadata(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 路径稳定整数 id，供 {@link io.github.jerryt92.j2agent.model.Translator} 去重。
     */
    static int stablePathId(String relativePath) {
        return relativePath.replace('\\', '/').hashCode();
    }

    public record ResolvedRagSources(List<FileDto> srcFiles, List<RagInfoDto> ragInfos) {
        public static ResolvedRagSources empty() {
            return new ResolvedRagSources(List.of(), List.of());
        }

        public boolean isEmpty() {
            return srcFiles == null || srcFiles.isEmpty();
        }
    }
}
