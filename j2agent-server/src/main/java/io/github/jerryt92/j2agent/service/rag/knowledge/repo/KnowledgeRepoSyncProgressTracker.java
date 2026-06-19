package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 知识库同步进度跟踪（内存态，供管理端轮询）。
 */
@Component
public class KnowledgeRepoSyncProgressTracker {

    public record FileProgress(
            String filePath,
            KnowledgeRepoSyncFileChangeType changeType,
            KnowledgeRepoSyncFileStatus status,
            String collection,
            Integer knowledgeCount,
            String errorMessage
    ) {
    }

    public record Snapshot(
            KnowledgeRepoSyncPhase phase,
            int totalCount,
            int processedCount,
            String currentFilePath,
            List<FileProgress> files
    ) {
    }

    private volatile KnowledgeRepoSyncPhase phase = KnowledgeRepoSyncPhase.IDLE;
    private volatile int totalCount;
    private volatile int processedCount;
    private volatile String currentFilePath;
    private final ConcurrentHashMap<String, FileProgress> files = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> fileOrder = new CopyOnWriteArrayList<>();

    public void reset() {
        phase = KnowledgeRepoSyncPhase.IDLE;
        totalCount = 0;
        processedCount = 0;
        currentFilePath = null;
        files.clear();
        fileOrder.clear();
    }

    public void beginPreparing() {
        reset();
        phase = KnowledgeRepoSyncPhase.PREPARING;
    }

    public void setPhase(KnowledgeRepoSyncPhase nextPhase) {
        this.phase = nextPhase;
    }

    public void registerDiff(Set<String> deleted,
                           Set<String> added,
                           Set<String> modified,
                           Map<String, String> collectionByPath,
                           Map<String, String> previousCollectionByPath) {
        files.clear();
        fileOrder.clear();
        processedCount = 0;
        currentFilePath = null;
        registerPaths(deleted, KnowledgeRepoSyncFileChangeType.DELETED, previousCollectionByPath);
        registerPaths(added, KnowledgeRepoSyncFileChangeType.ADDED, collectionByPath);
        registerPaths(modified, KnowledgeRepoSyncFileChangeType.MODIFIED, collectionByPath);
        totalCount = fileOrder.size();
        phase = KnowledgeRepoSyncPhase.DELETING;
    }

    private void registerPaths(Set<String> paths,
                               KnowledgeRepoSyncFileChangeType changeType,
                               Map<String, String> collectionByPath) {
        paths.stream().sorted().forEach(path -> {
            String collection = collectionByPath == null ? null : collectionByPath.get(path);
            FileProgress progress = new FileProgress(
                    path,
                    changeType,
                    KnowledgeRepoSyncFileStatus.PENDING,
                    collection,
                    null,
                    null
            );
            files.put(path, progress);
            fileOrder.add(path);
        });
    }

    public void markFileInProgress(String filePath) {
        currentFilePath = filePath;
        updateFile(filePath, current -> new FileProgress(
                current.filePath(),
                current.changeType(),
                KnowledgeRepoSyncFileStatus.IN_PROGRESS,
                current.collection(),
                current.knowledgeCount(),
                current.errorMessage()
        ));
    }

    public void markFileDeleted(String filePath) {
        finishFile(filePath, new FileProgress(
                filePath,
                KnowledgeRepoSyncFileChangeType.DELETED,
                KnowledgeRepoSyncFileStatus.DELETED,
                files.containsKey(filePath) ? files.get(filePath).collection() : null,
                null,
                null
        ));
    }

    public void markFileSynced(String filePath, String collection, int knowledgeCount) {
        finishFile(filePath, new FileProgress(
                filePath,
                resolveChangeType(filePath),
                KnowledgeRepoSyncFileStatus.SYNCED,
                collection,
                knowledgeCount,
                null
        ));
    }

    public void markFileSkipped(String filePath, String collection, String errorMessage) {
        finishFile(filePath, new FileProgress(
                filePath,
                resolveChangeType(filePath),
                KnowledgeRepoSyncFileStatus.SKIPPED,
                collection,
                null,
                errorMessage
        ));
    }

    public void markFileFailed(String filePath, String collection, String errorMessage) {
        finishFile(filePath, new FileProgress(
                filePath,
                resolveChangeType(filePath),
                KnowledgeRepoSyncFileStatus.FAILED,
                collection,
                null,
                errorMessage
        ));
    }

    public void complete() {
        currentFilePath = null;
        phase = KnowledgeRepoSyncPhase.COMPLETED;
    }

    public Snapshot snapshot() {
        List<FileProgress> ordered = new ArrayList<>(fileOrder.size());
        for (String path : fileOrder) {
            FileProgress progress = files.get(path);
            if (progress != null) {
                ordered.add(progress);
            }
        }
        return new Snapshot(phase, totalCount, processedCount, currentFilePath, Collections.unmodifiableList(ordered));
    }

    private KnowledgeRepoSyncFileChangeType resolveChangeType(String filePath) {
        FileProgress current = files.get(filePath);
        return current == null ? KnowledgeRepoSyncFileChangeType.MODIFIED : current.changeType();
    }

    private void finishFile(String filePath, FileProgress next) {
        currentFilePath = null;
        files.put(filePath, next);
        if (!fileOrder.contains(filePath)) {
            fileOrder.add(filePath);
            totalCount = fileOrder.size();
        }
        processedCount = Math.min(processedCount + 1, totalCount);
    }

    private void updateFile(String filePath, java.util.function.Function<FileProgress, FileProgress> updater) {
        files.compute(filePath, (path, current) -> {
            if (current == null) {
                return updater.apply(new FileProgress(
                        path,
                        KnowledgeRepoSyncFileChangeType.MODIFIED,
                        KnowledgeRepoSyncFileStatus.IN_PROGRESS,
                        null,
                        null,
                        null
                ));
            }
            return updater.apply(current);
        });
    }
}
