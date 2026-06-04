package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import io.github.jerryt92.j2agent.config.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.config.VectorDatabaseInit;
import io.github.jerryt92.j2agent.service.embedding.EmbeddingService;
import io.github.jerryt92.j2agent.service.rag.knowledge.MilvusKnowledgeWriteService;
import io.github.jerryt92.j2agent.utils.HashUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * 知识库目录同步服务。
 */
@Slf4j
@Service
@DependsOn("flywayInitializer")
public class KnowledgeRepoSyncService {
    /**
     * 文件最新状态对象。
     */
    /**
     * 文件最新状态：含 collection 与可选 Milvus 分区列表。
     */
    private record FileState(String fileSha256, String infoJsonHash, String collectionName, List<String> partitionNames,
                             String diffHash) {
    }

    private final KnowledgeRepoProperties properties;
    private final KnowledgeRepoMetadataService metadataService;
    private final KnowledgeRepoHashTreeService hashTreeService;
    private final MarkdownQaParser markdownQaParser;
    private final KnowledgeMarkdownImageRewriter knowledgeMarkdownImageRewriter;
    private final MilvusKnowledgeWriteService milvusKnowledgeWriteService;
    private final VectorDatabaseInit vectorDatabaseInit;
    private final EmbeddingService embeddingService;
    private final KnowledgeRepoHashCache hashCache = new KnowledgeRepoHashCache();
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private final Set<Path> watchedDirectories = ConcurrentHashMap.newKeySet();
    private WatchService watchService;

    public KnowledgeRepoSyncService(KnowledgeRepoProperties properties,
                                    KnowledgeRepoMetadataService metadataService,
                                    KnowledgeRepoHashTreeService hashTreeService,
                                    MarkdownQaParser markdownQaParser,
                                    KnowledgeMarkdownImageRewriter knowledgeMarkdownImageRewriter,
                                    MilvusKnowledgeWriteService milvusKnowledgeWriteService,
                                    VectorDatabaseInit vectorDatabaseInit,
                                    EmbeddingService embeddingService) {
        this.properties = properties;
        this.metadataService = metadataService;
        this.hashTreeService = hashTreeService;
        this.markdownQaParser = markdownQaParser;
        this.knowledgeMarkdownImageRewriter = knowledgeMarkdownImageRewriter;
        this.milvusKnowledgeWriteService = milvusKnowledgeWriteService;
        this.vectorDatabaseInit = vectorDatabaseInit;
        this.embeddingService = embeddingService;
    }

    /**
     * 应用启动后初始化本地快照并启动目录监听。
     */
    @Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (metadataService.getRepoRootPath() == null) {
            log.warn("知识库根目录未配置，跳过目录同步");
            return;
        }
        // 与 VectorDatabaseInit 异步任务对齐，避免 Milvus 尚未 reBuild 时写入导致无数据或失败
        vectorDatabaseInit.awaitInitTask(Duration.ofMinutes(12));
        hashCache.replaceAll(hashTreeService.loadSnapshot());
        syncNowSafely();
        if (properties.isWatchEnabled()) {
            startWatch();
        }
    }

    /**
     * 执行一次同步并记录异常。
     */
    private void syncNowSafely() {
        try {
            syncNow();
        } catch (Exception e) {
            log.error("知识库目录同步失败", e);
        }
    }

    /**
     * 执行一次增量同步。
     */
    public void syncNow() {
        syncExecutor.submit(() -> doSyncAfterVectorDatabaseReady(false));
    }

    /**
     * 阻塞等待一次增量同步完成，供管理端手动触发。
     *
     * @param timeout 最长等待时间
     */
    public KnowledgeRepoSyncOutcome syncNowAndAwait(Duration timeout, boolean fullRebuild) {
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null) {
            return KnowledgeRepoSyncOutcome.fail("知识库根目录未配置");
        }
        if (!Files.exists(rootPath)) {
            return KnowledgeRepoSyncOutcome.fail("知识库根目录不存在: " + rootPath.toAbsolutePath().normalize());
        }
        vectorDatabaseInit.awaitInitTask(Duration.ofMinutes(12));
        try {
            Future<?> future = syncExecutor.submit(() -> doSyncAfterVectorDatabaseReady(fullRebuild));
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return KnowledgeRepoSyncOutcome.ok();
        } catch (TimeoutException e) {
            log.warn("知识库目录同步超时: {} ms", timeout.toMillis());
            return KnowledgeRepoSyncOutcome.fail("知识库同步超时，请稍后重试");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("知识库目录同步失败", cause);
            return KnowledgeRepoSyncOutcome.fail(cause.getMessage() != null ? cause.getMessage() : "知识库同步失败");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return KnowledgeRepoSyncOutcome.fail("知识库同步被中断");
        }
    }

    /**
     * 等待向量库初始化结束后再执行同步，避免多个启动/监听入口并发写 Milvus。
     */
    private void doSyncAfterVectorDatabaseReady(boolean fullRebuild) {
        vectorDatabaseInit.awaitInitTask(Duration.ofMinutes(12));
        if (!embeddingService.isReady()) {
            log.warn("Embedding 当前不可用，本轮知识库同步降级为跳过，等待后续重试");
            return;
        }
        doSync(fullRebuild);
    }

    /**
     * 核心同步逻辑：扫描、diff、upsert、删除、回写状态。
     */
    private void doSync(boolean fullRebuild) {
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null || !Files.exists(rootPath)) {
            return;
        }
        try {
            if (fullRebuild) {
                runFullRebuildPreparation();
            }
            doSyncBody(rootPath);
        } catch (Exception e) {
            log.error("知识库同步失败: 根目录={}", rootPath.toAbsolutePath().normalize(), e);
        }
    }

    /**
     * 完全重建前置步骤：清空 Milvus 全部 collection 与哈希状态。
     */
    private void runFullRebuildPreparation() {
        log.warn("知识库完全重建开始：先删除 Milvus 全部 collection 并清空哈希状态表");
        milvusKnowledgeWriteService.dropAllCollections();
        hashTreeService.deleteAll();
        hashCache.replaceAll(Map.of());
    }

    /**
     * 同步主流程（便于 doSync 统一捕获异常并打日志）。
     */
    private void doSyncBody(Path rootPath) {
        metadataService.reloadMetadata();
        Map<String, String> previousFileCollectionMap = hashTreeService.loadActiveFileCollections();
        Map<String, Long> previousCollectionCounts = hashTreeService.loadActiveCollectionCounts();
        Set<String> previousActiveCollections = new HashSet<>(previousCollectionCounts.keySet());
        Map<String, FileState> latestFileStateMap = buildLatestFileState(rootPath);
        Map<String, String> latestSnapshot = buildLatestSnapshot(latestFileStateMap);
        Map<String, String> latestFileCollectionMap = buildFileCollectionMap(latestFileStateMap);
        Map<String, Long> latestCollectionCounts = toCollectionCounts(latestFileCollectionMap);
        logCollectionSet("同步前", previousActiveCollections);
        logCollectionStats("本轮扫描", latestCollectionCounts);
        KnowledgeRepoHashCache.DiffResult diffResult = hashCache.diff(latestSnapshot);
        long now = System.currentTimeMillis();
        for (String deletedPath : diffResult.deleted().stream().sorted().toList()) {
            Path path = resolveAbsolutePath(rootPath, deletedPath);
            String collection = previousFileCollectionMap.get(deletedPath);
            if (collection != null && !collection.isBlank()) {
                milvusKnowledgeWriteService.deleteBySourceFile(collection, deletedPath);
            }
            hashTreeService.markDeleted(Path.of(deletedPath), now);
        }
        for (String changedPath : orderChangedPaths(rootPath, diffResult.added())) {
            if (!upsertFile(rootPath, resolveAbsolutePath(rootPath, changedPath), changedPath, latestFileStateMap.get(changedPath), now)) {
                log.warn("知识库文件跳过并等待后续重试: {}", changedPath);
            }
        }
        for (String changedPath : orderChangedPaths(rootPath, diffResult.modified())) {
            Path path = resolveAbsolutePath(rootPath, changedPath);
            FileState latestState = latestFileStateMap.get(changedPath);
            deleteVectorsForModifiedFile(changedPath, previousFileCollectionMap.get(changedPath),
                    latestState == null ? null : latestState.collectionName());
            if (!upsertFile(rootPath, path, changedPath, latestFileStateMap.get(changedPath), now)) {
                log.warn("知识库文件跳过并等待后续重试: {}", changedPath);
            }
        }
        hashCache.replaceAll(latestSnapshot);
        recycleEmptyCollections(previousActiveCollections, latestCollectionCounts);
    }

    /**
     * 将单个文件解析并 upsert 到 Milvus。
     */
    private boolean upsertFile(Path rootPath, Path filePath, String relativePath, FileState fileState, long scanTime) {
        try {
            String documentContent = Files.readString(filePath, StandardCharsets.UTF_8);
            int minHeadingLevel = metadataService.resolveMinHeadingLevel(filePath);
            boolean filenameAsTitle = metadataService.resolveFilenameAsTitle(filePath);
            String filenameTitle = resolveFilenameTitle(relativePath);
            List<MarkdownQaParser.QaSegment> segments = markdownQaParser.parse(
                    relativePath, documentContent, minHeadingLevel, filenameAsTitle, filenameTitle);
            segments = knowledgeMarkdownImageRewriter.rewriteSegments(relativePath, segments);
            if (segments.isEmpty()) {
                log.warn("知识库文档未产生分片（Markdown 需存在 #/##/### 标题，AsciiDoc 需存在 ==/===/==== 标题，且标题下有非空正文；或启用 filename_as_title 且全文非空）: path={}, collection={}, minHeadingLevel={}, filenameAsTitle={}",
                        relativePath, fileState.collectionName(), minHeadingLevel, filenameAsTitle);
            }
            int upsertedCount = milvusKnowledgeWriteService.upsertQaSegments(
                    segments, relativePath, fileState.fileSha256(), fileState.collectionName(), fileState.partitionNames());
            long fileSizeBytes = Files.size(filePath);
            hashTreeService.upsertActive(
                    Path.of(relativePath),
                    fileState.fileSha256(),
                    fileState.infoJsonHash(),
                    fileState.collectionName(),
                    fileState.partitionNames(),
                    upsertedCount,
                    fileSizeBytes,
                    scanTime
            );
            return true;
        } catch (MilvusKnowledgeWriteService.EmbeddingUnavailableException e) {
            log.warn("知识库向量化降级: sourceFile={}, reason={}", relativePath, e.getMessage());
            return false;
        } catch (IOException e) {
            throw new IllegalStateException("读取知识库文档失败: " + filePath, e);
        }
    }

    /**
     * 将相对路径中的文档文件名转为标题，去掉支持的文档后缀。
     */
    private String resolveFilenameTitle(String relativePath) {
        String filename = Path.of(relativePath).getFileName().toString();
        for (String extension : List.of(".asciidoc", ".adoc", ".md")) {
            if (filename.length() > extension.length()
                    && filename.regionMatches(true, filename.length() - extension.length(), extension, 0, extension.length())) {
                return filename.substring(0, filename.length() - extension.length());
            }
        }
        return filename;
    }

    /**
     * 按文件大小从小到大处理，避免超大文件阻塞其它文件的状态落库。
     */
    private List<String> orderChangedPaths(Path rootPath, Set<String> changedPaths) {
        return changedPaths.stream()
                .sorted((left, right) -> {
                    int sizeCompare = Long.compare(fileSizeForOrder(rootPath, left), fileSizeForOrder(rootPath, right));
                    return sizeCompare == 0 ? left.compareTo(right) : sizeCompare;
                })
                .toList();
    }

    /**
     * 读取文件大小用于排序，异常时放到最后处理。
     */
    private long fileSizeForOrder(Path rootPath, String relativePath) {
        try {
            return Files.size(resolveAbsolutePath(rootPath, relativePath));
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 从文件状态构建差异快照。
     */
    private Map<String, String> buildLatestSnapshot(Map<String, FileState> latestFileStateMap) {
        Map<String, String> snapshot = new HashMap<>();
        for (Map.Entry<String, FileState> entry : latestFileStateMap.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue().diffHash());
        }
        return snapshot;
    }

    /**
     * 基于文件状态构建文件到 collection 的映射。
     */
    private Map<String, String> buildFileCollectionMap(Map<String, FileState> latestFileStateMap) {
        Map<String, String> mapping = new HashMap<>();
        for (Map.Entry<String, FileState> entry : latestFileStateMap.entrySet()) {
            mapping.put(entry.getKey(), entry.getValue().collectionName());
        }
        return mapping;
    }

    /**
     * 将文件映射转换为 collection 计数。
     */
    private Map<String, Long> toCollectionCounts(Map<String, String> fileCollectionMap) {
        Map<String, Long> counts = new HashMap<>();
        for (String collectionName : fileCollectionMap.values()) {
            if (collectionName == null || collectionName.isBlank()) {
                continue;
            }
            counts.merge(collectionName, 1L, Long::sum);
        }
        return counts;
    }

    /**
     * 回收上一轮存在但当前已空的 collection。
     */
    private void recycleEmptyCollections(Set<String> previousActiveCollections, Map<String, Long> latestCollectionCounts) {
        for (String collectionName : previousActiveCollections) {
            long latestCount = latestCollectionCounts.getOrDefault(collectionName, 0L);
            if (latestCount > 0) {
                continue;
            }
            if (!milvusKnowledgeWriteService.hasCollection(collectionName)) {
                continue;
            }
            milvusKnowledgeWriteService.dropCollection(collectionName);
            log.info("回收空 collection: {}, 当前文件数: {}", collectionName, latestCount);
        }
        logCollectionStats("同步后", latestCollectionCounts);
    }

    /**
     * 打印 collection 级文件统计。
     */
    private void logCollectionStats(String phase, Map<String, Long> collectionCounts) {
        log.info("{} collection 文件统计: {}", phase, collectionCounts);
    }

    /**
     * 打印 collection 集合。
     */
    private void logCollectionSet(String phase, Set<String> collections) {
        log.info("{} collection 集合: {}", phase, collections);
    }

    /**
     * 扫描根目录构建最新知识库文档哈希快照。
     */
    private Map<String, FileState> buildLatestFileState(Path rootPath) {
        Map<String, FileState> snapshot = new HashMap<>();
        if (!metadataService.hasMetadata()) {
            log.info("知识库目录未找到 info.json，本轮按空知识库处理: {}", rootPath.toAbsolutePath().normalize());
            return snapshot;
        }
        // 与 KnowledgeRepoMetadataService 扫描一致，跟随符号链接以识别外链的知识库目录
        try (Stream<Path> pathStream = Files.walk(rootPath, Integer.MAX_VALUE, FileVisitOption.FOLLOW_LINKS)) {
            pathStream.filter(Files::isRegularFile)
                    .filter(this::isSupportedKnowledgeDocument)
                    .forEach(path -> {
                        String relativePath = toRepoRelativePath(rootPath, path);
                        String fileSha256 = calculateSha256(path);
                        String infoJsonHash = metadataService.resolveInfoJsonHash(path);
                        String collectionName = metadataService.resolveCollection(path);
                        List<String> partitionNames = metadataService.resolvePartitionNames(path);
                        String diffHash = KnowledgeRepoDiffHash.build(fileSha256, infoJsonHash, collectionName);
                        snapshot.put(relativePath, new FileState(fileSha256, infoJsonHash, collectionName, partitionNames, diffHash));
                    });
        } catch (IOException e) {
            throw new IllegalStateException("扫描知识库目录失败", e);
        }
        return snapshot;
    }

    /**
     * 判断是否为支持进入向量库的知识库文档。
     */
    private boolean isSupportedKnowledgeDocument(Path path) {
        String lowerFilename = path.getFileName().toString().toLowerCase();
        return lowerFilename.endsWith(".md")
                || lowerFilename.endsWith(".adoc")
                || lowerFilename.endsWith(".asciidoc");
    }

    /**
     * 计算文件 sha256。
     */
    private String calculateSha256(Path path) {
        try {
            return HashUtil.getMessageDigest(Files.readAllBytes(path), HashUtil.MdAlgorithm.SHA256);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("计算文件哈希失败: " + path, e);
        }
    }

    /**
     * 修改文件时：在 DB 记录的旧 collection 与当前解析的新 collection 上分别按 source_file 删除，避免共用 collection 迁名后旧库残留。
     */
    private void deleteVectorsForModifiedFile(String relativePath, String previousCollection, String nextCollection) {
        Set<String> collections = new LinkedHashSet<>();
        if (previousCollection != null && !previousCollection.isBlank()) {
            collections.add(previousCollection.trim());
        }
        if (nextCollection != null && !nextCollection.isBlank()) {
            collections.add(nextCollection.trim());
        }
        for (String col : collections) {
            milvusKnowledgeWriteService.deleteBySourceFile(col, relativePath);
        }
    }

    /**
     * 将绝对文件路径转换为 root-path 下的相对路径。
     */
    private String toRepoRelativePath(Path rootPath, Path filePath) {
        return rootPath.toAbsolutePath().normalize().relativize(filePath.toAbsolutePath().normalize()).toString().replace("\\", "/");
    }

    /**
     * 将仓内相对路径恢复为绝对路径。
     */
    private Path resolveAbsolutePath(Path rootPath, String relativePath) {
        return rootPath.toAbsolutePath().normalize().resolve(relativePath).normalize();
    }

    /**
     * 启动目录变更监听。
     */
    private void startWatch() {
        Path rootPath = metadataService.getRepoRootPath();
        if (rootPath == null || !Files.exists(rootPath)) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            watchedDirectories.clear();
            registerAllDirectories(rootPath);
            Thread.startVirtualThread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    WatchKey key;
                    try {
                        key = watchService.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    Path watchedDir = (Path) key.watchable();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            continue;
                        }
                        Path changedPath = watchedDir.resolve((Path) event.context());
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changedPath)) {
                            registerAllDirectoriesSafely(changedPath);
                        }
                        syncNowSafely();
                    }
                    if (!key.reset()) {
                        watchedDirectories.remove(watchedDir.toAbsolutePath().normalize());
                    }
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("启动目录监听失败", e);
        }
    }

    /**
     * 递归注册知识库目录监听；info.json 可位于子目录，必须监听整棵目录树。
     */
    private void registerAllDirectories(Path rootPath) throws IOException {
        try (Stream<Path> stream = Files.walk(rootPath, Integer.MAX_VALUE, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(Files::isDirectory).forEach(this::registerDirectorySafely);
        }
    }

    /**
     * 安全注册新增目录，避免监听线程因单个目录失败退出。
     */
    private void registerAllDirectoriesSafely(Path rootPath) {
        try {
            registerAllDirectories(rootPath);
        } catch (IOException e) {
            log.warn("注册知识库新增目录监听失败: {}", rootPath, e);
        }
    }

    /**
     * 注册单个目录监听，重复注册会被跳过。
     */
    private void registerDirectorySafely(Path dir) {
        Path normalized = dir.toAbsolutePath().normalize();
        if (!watchedDirectories.add(normalized)) {
            return;
        }
        try {
            normalized.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException e) {
            watchedDirectories.remove(normalized);
            throw new IllegalStateException("注册知识库目录监听失败: " + normalized, e);
        }
    }

    /**
     * 释放线程和监听资源。
     */
    @PreDestroy
    public void destroy() {
        syncExecutor.shutdownNow();
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                log.warn("关闭 watchService 失败");
            }
        }
    }
}
