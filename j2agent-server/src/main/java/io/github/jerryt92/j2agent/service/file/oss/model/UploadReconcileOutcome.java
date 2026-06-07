package io.github.jerryt92.j2agent.service.file.oss.model;

/**
 * 内部对账结果，供延迟队列 Worker 决策。
 */
public enum UploadReconcileOutcome {
    COMPLETED,
    NOT_READY,
    SIZE_MISMATCH,
    SKIPPED
}
