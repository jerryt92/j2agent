package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.config.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.utils.HashUtil;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;

/**
 * 知识库目录元数据解析服务。
 */
@Service
@DependsOn("flywayInitializer")
public class KnowledgeRepoMetadataService {
    private final KnowledgeRepoProperties properties;
    @Getter
    private volatile Path repoRootPath;
    /** info.json 未配置时的默认最小标题级别（仅 ### 及以下开启分片）。 */
    public static final int DEFAULT_MIN_HEADING_LEVEL = 3;
    /** info.json 未配置时默认不将文件名作为标题链前缀。 */
    public static final boolean DEFAULT_FILENAME_AS_TITLE = false;

    private volatile Map<Path, String> collectionByPrefixDir = Map.of();
    private volatile Map<Path, String> infoJsonHashByPrefixDir = Map.of();
    private volatile Map<Path, List<String>> partitionNamesByPrefixDir = Map.of();
    private volatile Map<Path, Integer> minHeadingLevelByPrefixDir = Map.of();
    private volatile Map<Path, Boolean> filenameAsTitleByPrefixDir = Map.of();

    public KnowledgeRepoMetadataService(KnowledgeRepoProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动时加载根目录和 info.json。
     */
    @PostConstruct
    public void init() {
        if (StringUtils.isBlank(properties.getRootPath())) {
            return;
        }
        repoRootPath = resolveRootPath(properties.getRootPath());
        reloadMetadata();
    }

    /**
     * 重新加载目录 metadata 配置。
     */
    public synchronized void reloadMetadata() {
        if (repoRootPath == null || !Files.exists(repoRootPath)) {
            return;
        }
        List<Path> infoJsonPaths = scanInfoJsonPaths();
        if (infoJsonPaths.isEmpty()) {
            clearMetadata();
            return;
        }
        validatePrefixRule(infoJsonPaths);
        Map<Path, String> nextCollectionByPrefixDir = new HashMap<>();
        Map<Path, String> nextInfoJsonHashByPrefixDir = new HashMap<>();
        Map<Path, List<String>> nextPartitionNamesByPrefixDir = new HashMap<>();
        Map<Path, Integer> nextMinHeadingLevelByPrefixDir = new HashMap<>();
        Map<Path, Boolean> nextFilenameAsTitleByPrefixDir = new HashMap<>();
        for (Path infoJsonPath : infoJsonPaths) {
            RepoInfo repoInfo = parseRepoInfo(infoJsonPath);
            Path prefixDir = toCanonicalDir(infoJsonPath.getParent());
            nextCollectionByPrefixDir.put(prefixDir, repoInfo.collection());
            nextInfoJsonHashByPrefixDir.put(prefixDir, calculateFileSha256(infoJsonPath));
            nextPartitionNamesByPrefixDir.put(prefixDir, repoInfo.partitionNames());
            nextMinHeadingLevelByPrefixDir.put(prefixDir, repoInfo.minHeadingLevel());
            nextFilenameAsTitleByPrefixDir.put(prefixDir, repoInfo.filenameAsTitle());
        }
        collectionByPrefixDir = Collections.unmodifiableMap(nextCollectionByPrefixDir);
        infoJsonHashByPrefixDir = Collections.unmodifiableMap(nextInfoJsonHashByPrefixDir);
        partitionNamesByPrefixDir = Collections.unmodifiableMap(nextPartitionNamesByPrefixDir);
        minHeadingLevelByPrefixDir = Collections.unmodifiableMap(nextMinHeadingLevelByPrefixDir);
        filenameAsTitleByPrefixDir = Collections.unmodifiableMap(nextFilenameAsTitleByPrefixDir);
    }

    /**
     * 当前知识库目录是否已配置至少一个 info.json。
     */
    public boolean hasMetadata() {
        return !collectionByPrefixDir.isEmpty();
    }

    private void clearMetadata() {
        collectionByPrefixDir = Map.of();
        infoJsonHashByPrefixDir = Map.of();
        partitionNamesByPrefixDir = Map.of();
        minHeadingLevelByPrefixDir = Map.of();
        filenameAsTitleByPrefixDir = Map.of();
    }

    /**
     * 根据文件路径解析 collection。
     */
    public String resolveCollection(Path filePath) {
        Path prefixDir = resolvePrefixDir(filePath);
        String resolved = collectionByPrefixDir.get(prefixDir);
        if (StringUtils.isBlank(resolved)) {
            throw new IllegalStateException("未找到匹配的 info.json collection 配置，文件路径: " + filePath.toAbsolutePath().normalize());
        }
        return resolved;
    }

    /**
     * 根据文件路径解析匹配 info.json 的哈希值。
     */
    public String resolveInfoJsonHash(Path filePath) {
        Path prefixDir = resolvePrefixDir(filePath);
        String resolved = infoJsonHashByPrefixDir.get(prefixDir);
        if (StringUtils.isBlank(resolved)) {
            throw new IllegalStateException("未找到匹配的 info.json 哈希配置，文件路径: " + filePath.toAbsolutePath().normalize());
        }
        return resolved;
    }

    /**
     * 扫描知识库目录中的全部 info.json。
     */
    private List<Path> scanInfoJsonPaths() {
        // FOLLOW_LINKS：根目录或子目录为符号链接时仍能扫到真实 Markdown/info.json（跨仓库链常见）
        try (Stream<Path> stream = Files.walk(repoRootPath, Integer.MAX_VALUE, FileVisitOption.FOLLOW_LINKS)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> "info.json".equals(path.getFileName().toString()))
                    .map(path -> path.toAbsolutePath().normalize())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("遍历知识库目录失败", e);
        }
    }

    /**
     * 校验前缀路径规则：任一 info.json 的祖先/后代路径链路中至多存在一个 info.json。
     */
    private void validatePrefixRule(List<Path> infoJsonPaths) {
        if (infoJsonPaths == null || infoJsonPaths.isEmpty()) {
            return;
        }
        List<Path> sorted = new ArrayList<>(infoJsonPaths);
        sorted.sort(Comparator.comparingInt(Path::getNameCount));
        List<Path> prefixDirs = new ArrayList<>();
        for (Path infoJsonPath : sorted) {
            Path currentDir = toCanonicalDir(infoJsonPath.getParent());
            for (Path existedDir : prefixDirs) {
                if (currentDir.startsWith(existedDir)) {
                    throw new IllegalStateException("检测到前缀路径存在多个 info.json，冲突路径: " + existedDir.resolve("info.json") + " 与 " + infoJsonPath);
                }
            }
            prefixDirs.add(currentDir);
        }
    }

    /**
     * 解析单个 info.json：collection_name、可选 partition_names、min_heading_level 与 filename_as_title。
     */
    private RepoInfo parseRepoInfo(Path infoJsonPath) {
        try {
            JSONObject info = JSONObject.parseObject(Files.readString(infoJsonPath));
            String parsedCollection = info.getString("collection_name");
            if (StringUtils.isBlank(parsedCollection)) {
                throw new IllegalStateException("info.json 未配置 collection_name 字段，路径: " + infoJsonPath);
            }
            List<String> partitionNames = parsePartitionNames(info, infoJsonPath);
            int minHeadingLevel = parseMinHeadingLevel(info, infoJsonPath);
            boolean filenameAsTitle = parseFilenameAsTitle(info, infoJsonPath);
            return new RepoInfo(parsedCollection.trim(), partitionNames, minHeadingLevel, filenameAsTitle);
        } catch (IOException e) {
            throw new IllegalStateException("读取 info.json 失败: " + infoJsonPath, e);
        }
    }

    /**
     * 解析 min_heading_level；缺省为 {@link #DEFAULT_MIN_HEADING_LEVEL}，有效范围为 1–3。
     */
    private int parseMinHeadingLevel(JSONObject info, Path infoJsonPath) {
        if (!info.containsKey("min_heading_level")) {
            return DEFAULT_MIN_HEADING_LEVEL;
        }
        Integer level = info.getInteger("min_heading_level");
        if (level == null || level < 1 || level > 3) {
            throw new IllegalStateException("info.json min_heading_level 必须为 1–3 的整数，路径: " + infoJsonPath);
        }
        return level;
    }

    /**
     * 解析 filename_as_title；缺省为 {@link #DEFAULT_FILENAME_AS_TITLE}。
     */
    private boolean parseFilenameAsTitle(JSONObject info, Path infoJsonPath) {
        if (!info.containsKey("filename_as_title")) {
            return DEFAULT_FILENAME_AS_TITLE;
        }
        Object raw = info.get("filename_as_title");
        if (!(raw instanceof Boolean value)) {
            throw new IllegalStateException("info.json filename_as_title 必须为 boolean，路径: " + infoJsonPath);
        }
        return value;
    }

    /**
     * 解析 partition_names 数组；缺省或非数组视为无分区配置。
     */
    private List<String> parsePartitionNames(JSONObject info, Path infoJsonPath) {
        if (!info.containsKey("partition_names")) {
            return List.of();
        }
        JSONArray array = info.getJSONArray("partition_names");
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        Set<String> orderedUnique = new LinkedHashSet<>();
        for (int i = 0; i < array.size(); i++) {
            String raw = array.getString(i);
            if (raw == null || raw.isBlank()) {
                throw new IllegalStateException("info.json partition_names 存在空元素，路径: " + infoJsonPath);
            }
            orderedUnique.add(raw.trim());
        }
        return List.copyOf(orderedUnique);
    }

    /**
     * 计算文件 sha256。
     */
    private String calculateFileSha256(Path filePath) {
        try {
            return HashUtil.getMessageDigest(Files.readAllBytes(filePath), HashUtil.MdAlgorithm.SHA256);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("计算文件哈希失败: " + filePath, e);
        }
    }

    /**
     * 解析文件命中的前缀目录。
     */
    private Path resolvePrefixDir(Path filePath) {
        if (filePath == null) {
            throw new IllegalStateException("文件路径为空，无法解析前缀 info.json");
        }
        Path absoluteFilePath = toCanonicalPathIfExists(filePath);
        Path matchedPrefixDir = null;
        int maxDepth = -1;
        for (Path prefixDir : collectionByPrefixDir.keySet()) {
            if (!absoluteFilePath.startsWith(prefixDir)) {
                continue;
            }
            int depth = prefixDir.getNameCount();
            if (depth > maxDepth) {
                maxDepth = depth;
                matchedPrefixDir = prefixDir;
            }
        }
        if (matchedPrefixDir == null) {
            throw new IllegalStateException("未找到匹配的 info.json 前缀路径，文件路径: " + absoluteFilePath);
        }
        return matchedPrefixDir;
    }

    /**
     * 根据文件路径解析 Milvus 分区名列表；空列表表示使用默认分区。
     */
    public List<String> resolvePartitionNames(Path filePath) {
        Path prefixDir = resolvePrefixDir(filePath);
        List<String> resolved = partitionNamesByPrefixDir.get(prefixDir);
        return resolved == null ? List.of() : resolved;
    }

    /**
     * 根据文件路径解析 Markdown 分片最小标题级别（1=#，2=##，3=###）。
     */
    public int resolveMinHeadingLevel(Path filePath) {
        Path prefixDir = resolvePrefixDir(filePath);
        Integer resolved = minHeadingLevelByPrefixDir.get(prefixDir);
        if (resolved == null) {
            throw new IllegalStateException("未找到匹配的 info.json min_heading_level 配置，文件路径: " + filePath.toAbsolutePath().normalize());
        }
        return resolved;
    }

    /**
     * 根据文件路径解析是否将 Markdown 文件名作为标题链前缀。
     */
    public boolean resolveFilenameAsTitle(Path filePath) {
        Path prefixDir = resolvePrefixDir(filePath);
        Boolean resolved = filenameAsTitleByPrefixDir.get(prefixDir);
        if (resolved == null) {
            throw new IllegalStateException("未找到匹配的 info.json filename_as_title 配置，文件路径: " + filePath.toAbsolutePath().normalize());
        }
        return resolved;
    }

    /**
     * 单个 info.json 解析结果。
     */
    private record RepoInfo(String collection, List<String> partitionNames, int minHeadingLevel, boolean filenameAsTitle) {
    }

    /**
     * 解析配置中的根目录路径。
     */
    private Path resolveRootPath(String configuredPath) {
        if (configuredPath.startsWith("classpath:/")) {
            String relativePath = configuredPath.substring("classpath:/".length());
            try {
                return new ClassPathResource(relativePath).getFile().toPath().toRealPath();
            } catch (IOException e) {
                throw new IllegalStateException("无法解析 classpath 知识库目录: " + configuredPath, e);
            }
        }
        Path path = Path.of(configuredPath.trim()).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            return path;
        }
        try {
            return path.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("无法解析知识库根目录（例如符号链接无效）: " + configuredPath, e);
        }
    }

    /**
     * 将已存在的路径规范为真实路径，避免符号链接与物理路径混用导致 startsWith 匹配失败。
     */
    private Path toCanonicalPathIfExists(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return normalized;
        }
        try {
            return normalized.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("无法解析路径: " + path, e);
        }
    }

    /**
     * 将目录规范为真实路径（用于 info.json 前缀键）。
     */
    private Path toCanonicalDir(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return normalized;
        }
        try {
            return normalized.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("无法解析目录: " + dir, e);
        }
    }
}
