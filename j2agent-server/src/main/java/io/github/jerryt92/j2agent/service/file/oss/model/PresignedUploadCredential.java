package io.github.jerryt92.j2agent.service.file.oss.model;

import java.util.Map;

/**
 * 客户端直传对象存储所需的上传凭证。
 */
public record PresignedUploadCredential(
        String provider,
        String uploadUrl,
        String method,
        Map<String, String> headers,
        long expiresAt,
        Map<String, String> providerExtras
) {
}
