package io.github.jerryt92.j2agent.service.file.oss.provider;

import org.springframework.util.StringUtils;

import java.time.Duration;

final class ObjectStorageSupport {
    private static final long MAX_PRESIGNED_URL_SECONDS = Duration.ofDays(7).toSeconds();

    private ObjectStorageSupport() {
    }

    static String requireName(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    static int expirySeconds(Duration expiry) {
        if (expiry == null || expiry.isZero() || expiry.isNegative()) {
            throw new IllegalArgumentException("expiry must be positive");
        }
        long seconds = expiry.toSeconds();
        if (seconds > MAX_PRESIGNED_URL_SECONDS) {
            throw new IllegalArgumentException("expiry must not exceed 7 days");
        }
        return Math.toIntExact(seconds);
    }

    static int pageSize(int pageSize) {
        if (pageSize < 1 || pageSize > 1000) {
            throw new IllegalArgumentException("pageSize must be between 1 and 1000");
        }
        return pageSize;
    }
}
