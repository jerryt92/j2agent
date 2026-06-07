package io.github.jerryt92.j2agent.service.file.oss.model;

/**
 * 删除补偿延迟任务。
 */
public record ObjectDeleteReconcileTask(String bucket, String objectKey, int attempt) {
}
