package io.github.jerryt92.j2agent.service.file.oss.model;

/**
 * 直传初始化结果，包含对象键与上传凭证。
 */
public record DirectUploadInitResult(String objectKey, PresignedUploadCredential credential) {
}
