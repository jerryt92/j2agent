package io.github.jerryt92.j2agent.service.rag.knowledge.repo;

import lombok.Getter;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 知识库文件哈希树内存缓存。
 */
public class KnowledgeRepoHashCache {
    @Getter
    private final Map<String, String> pathToHash = new HashMap<>();

    /**
     * 计算前后快照差异。
     */
    public DiffResult diff(Map<String, String> latestSnapshot) {
        Set<String> added = new HashSet<>();
        Set<String> modified = new HashSet<>();
        Set<String> deleted = new HashSet<>();
        for (Map.Entry<String, String> entry : latestSnapshot.entrySet()) {
            String oldHash = pathToHash.get(entry.getKey());
            if (oldHash == null) {
                added.add(entry.getKey());
                continue;
            }
            if (!oldHash.equals(entry.getValue())) {
                modified.add(entry.getKey());
            }
        }
        for (String oldPath : pathToHash.keySet()) {
            if (!latestSnapshot.containsKey(oldPath)) {
                deleted.add(oldPath);
            }
        }
        return new DiffResult(added, modified, deleted);
    }

    /**
     * 使用最新快照覆盖内存缓存。
     */
    public void replaceAll(Map<String, String> latestSnapshot) {
        pathToHash.clear();
        pathToHash.putAll(latestSnapshot);
    }

    /**
     * 差异结果对象。
     */
    public record DiffResult(Set<String> added, Set<String> modified, Set<String> deleted) {
    }
}

