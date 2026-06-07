package io.github.jerryt92.j2agent.service.file.oss.model;

/**
 * 直传上传延迟对账任务。
 */
public record ObjectUploadReconcileTask(String bucket, String objectKey, int attempt) {
}
