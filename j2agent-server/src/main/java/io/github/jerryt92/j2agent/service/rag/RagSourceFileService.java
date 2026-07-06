package io.github.jerryt92.j2agent.service.rag;

import io.github.jerryt92.j2agent.model.FileDto;
import io.github.jerryt92.j2agent.model.RagInfoDto;
import io.github.jerryt92.j2agent.model.po.KnowledgeTextChunkPo;
import io.github.jerryt92.j2agent.service.file.StaticFileService;
import io.github.jerryt92.j2agent.service.rag.knowledge.KnowledgeTextChunkService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 RAG 检索 Document 提取知识库文档源文件（.md / .adoc / .asciidoc）并构建来源链接。
 */
@Service
public class RagSourceFileService {

    private static final String METADATA_SOURCE_FILE = "sourceFile";
    private static final String METADATA_TEXT_CHUNK_ID = "textChunkId";
    private static final String RAG_SYSTEM_SOURCE = "rag-system";

    private final KnowledgeTextChunkService knowledgeTextChunkService;

    public RagSourceFileService(KnowledgeTextChunkService knowledgeTextChunkService) {
        this.knowledgeTextChunkService = knowledgeTextChunkService;
    }

    /**
     * 解析检索命中的唯一知识库文档源文件列表及对应 {@link RagInfoDto}。
     */
    public ResolvedRagSources resolveUniqueMdSources(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return ResolvedRagSources.empty();
        }
        MutableResolveStats stats = ResolveStats.mutable(documents.size());
        Map<String, KnowledgeTextChunkPo> chunkById = loadChunkFallbacks(documents);
        LinkedHashMap<String, RagInfoDto> byPath = new LinkedHashMap<>();
        for (Document document : documents) {
            if (document == null || document.getMetadata() == null) {
                stats.skippedInvalid++;
                continue;
            }
            Map<String, Object> metadata = document.getMetadata();
            String relativePath = resolveRelativePath(metadata, chunkById, stats);
            if (relativePath == null) {
                Object rawSource = metadata.get(METADATA_SOURCE_FILE);
                if (rawSource != null && RAG_SYSTEM_SOURCE.equals(rawSource.toString().trim())) {
                    stats.skippedSystem++;
                } else if (rawSource != null && !rawSource.toString().isBlank()) {
                    stats.skippedNonKb++;
                } else {
                    stats.skippedInvalid++;
                }
                continue;
            }
            if (byPath.containsKey(relativePath)) {
                stats.skippedDuplicate++;
                continue;
            }
            FileDto fileDto = buildFileDto(relativePath);
            RagInfoDto ragInfo = new RagInfoDto()
                    .textChunkId(stringMetadata(metadata.get(METADATA_TEXT_CHUNK_ID)))
                    .srcFile(fileDto);
            byPath.put(relativePath, ragInfo);
            stats.resolved++;
        }
        if (byPath.isEmpty()) {
            return ResolvedRagSources.empty(stats.freeze());
        }
        List<RagInfoDto> ragInfos = new ArrayList<>(byPath.values());
        List<FileDto> srcFiles = ragInfos.stream()
                .map(RagInfoDto::getSrcFile)
                .toList();
        return new ResolvedRagSources(srcFiles, ragInfos, stats.freeze());
    }

    /**
     * 优先用 metadata.sourceFile；不可解析时回退 text_chunk 表中的 source_file。
     */
    private String resolveRelativePath(Map<String, Object> metadata,
                                       Map<String, KnowledgeTextChunkPo> chunkById,
                                       MutableResolveStats stats) {
        String fromMetadata = RagSourcePathUtils.normalizeKbSourceRelativePath(metadata.get(METADATA_SOURCE_FILE));
        if (fromMetadata != null) {
            return fromMetadata;
        }
        String textChunkId = stringMetadata(metadata.get(METADATA_TEXT_CHUNK_ID));
        if (!StringUtils.isNotBlank(textChunkId)) {
            return null;
        }
        KnowledgeTextChunkPo chunk = chunkById.get(textChunkId);
        if (chunk == null) {
            return null;
        }
        String fromChunk = RagSourcePathUtils.normalizeKbSourceRelativePath(chunk.getSourceFile());
        if (fromChunk != null) {
            stats.chunkFallback++;
        }
        return fromChunk;
    }

    private Map<String, KnowledgeTextChunkPo> loadChunkFallbacks(List<Document> documents) {
        List<String> chunkIds = new ArrayList<>();
        for (Document document : documents) {
            if (document == null || document.getMetadata() == null) {
                continue;
            }
            Object rawSource = document.getMetadata().get(METADATA_SOURCE_FILE);
            if (RagSourcePathUtils.isKbSourceRelativePath(rawSource)) {
                continue;
            }
            String textChunkId = stringMetadata(document.getMetadata().get(METADATA_TEXT_CHUNK_ID));
            if (StringUtils.isNotBlank(textChunkId)) {
                chunkIds.add(textChunkId);
            }
        }
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        return knowledgeTextChunkService.getByIds(chunkIds);
    }

    /** 为合法知识库相对路径构建来源链接；磁盘不存在时仍返回 DTO。 */
    private FileDto buildFileDto(String relativePath) {
        return new FileDto()
                .id(stablePathId(relativePath))
                .fullFileName(Path.of(relativePath).getFileName().toString())
                .relativePath(relativePath)
                .url(StaticFileService.toRepoFileUrl(relativePath));
    }

    private static String stringMetadata(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 路径稳定整数 id，供 {@link io.github.jerryt92.j2agent.model.Translator} 展示去重辅助。
     */
    static int stablePathId(String relativePath) {
        return relativePath.replace('\\', '/').hashCode();
    }

    public record ResolvedRagSources(List<FileDto> srcFiles, List<RagInfoDto> ragInfos, ResolveStats stats) {
        public ResolvedRagSources {
            stats = stats != null ? stats : ResolveStats.empty();
        }

        public static ResolvedRagSources empty() {
            return new ResolvedRagSources(List.of(), List.of(), ResolveStats.empty());
        }

        static ResolvedRagSources empty(ResolveStats stats) {
            return new ResolvedRagSources(List.of(), List.of(), stats);
        }

        public boolean isEmpty() {
            return srcFiles == null || srcFiles.isEmpty();
        }
    }

    /** 来源解析统计，供 agent-run 日志汇总。 */
    public record ResolveStats(int inputDocs,
                               int resolved,
                               int skippedDuplicate,
                               int skippedInvalid,
                               int skippedNonKb,
                               int skippedSystem,
                               int chunkFallback) {

        static MutableResolveStats mutable(int inputDocs) {
            return new MutableResolveStats(inputDocs);
        }

        static ResolveStats empty() {
            return new ResolveStats(0, 0, 0, 0, 0, 0, 0);
        }

        /** 格式化为 agent-run 日志 rag 字段片段。 */
        public String toLogFragment() {
            return "inputDocs=" + inputDocs
                    + ",resolved=" + resolved
                    + ",skipDup=" + skippedDuplicate
                    + ",skipInvalid=" + skippedInvalid
                    + ",skipNonKb=" + skippedNonKb
                    + ",skipSystem=" + skippedSystem
                    + ",chunkFallback=" + chunkFallback;
        }
    }

    private static final class MutableResolveStats {
        final int inputDocs;
        int resolved;
        int skippedDuplicate;
        int skippedInvalid;
        int skippedNonKb;
        int skippedSystem;
        int chunkFallback;

        MutableResolveStats(int inputDocs) {
            this.inputDocs = inputDocs;
        }

        ResolveStats freeze() {
            return new ResolveStats(inputDocs, resolved, skippedDuplicate, skippedInvalid,
                    skippedNonKb, skippedSystem, chunkFallback);
        }
    }
}
