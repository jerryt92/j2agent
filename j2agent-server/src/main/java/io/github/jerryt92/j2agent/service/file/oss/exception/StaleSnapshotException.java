package io.github.jerryt92.j2agent.service.file.oss.exception;

/**
 * 同步扫描快照与当前 OSS/数据库状态不一致时抛出。
 */
public class StaleSnapshotException extends RuntimeException {
    public StaleSnapshotException(String message) {
        super(message);
    }
}
