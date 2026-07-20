package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import java.util.stream.Stream;

/**
 * 知识库仓库常量定义。
 */
public final class KnowledgeRepositoryConstants {
    public static final String TYPE_LOCAL_FILE = "LOCAL_FILE";
    public static final String TYPE_REMOTE = "REMOTE";
    public static final String PROTOCOL_GIT = "GIT";
    public static final String STATUS_IDLE = "IDLE";
    public static final String STATUS_SYNCING = "SYNCING";
    public static final String STATUS_SYNCED = "SYNCED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DIRECTORY_MISSING = "DIRECTORY_MISSING";
    public static final int DEFAULT_UPDATE_INTERVAL_MINUTES = 60;

    private KnowledgeRepositoryConstants() {
    }
}
