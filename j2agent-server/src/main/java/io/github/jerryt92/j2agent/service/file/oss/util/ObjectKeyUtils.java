package io.github.jerryt92.j2agent.service.file.oss.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

public final class ObjectKeyUtils {
    private ObjectKeyUtils() {
    }

    public static String hash(String objectKey) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(objectKey.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static String normalizeEtag(String etag) {
        if (etag == null) {
            return null;
        }
        String normalized = etag.trim();
        if (normalized.length() >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    public static long normalizeLastModified(long lastModified) {
        return lastModified <= 0 ? lastModified : lastModified / 1000 * 1000;
    }

    public static Long normalizeLastModified(Long lastModified) {
        return lastModified == null ? null : normalizeLastModified(lastModified.longValue());
    }

    public static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        String normalized = prefix.trim().replace('\\', '/').replaceFirst("^/+", "");
        if (normalized.contains("../") || normalized.equals("..")) {
            throw new IllegalArgumentException("prefix must not contain parent path segments");
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    public static String objectKey(String prefix, String fileName) {
        String normalizedFileName = fileName == null ? "" : fileName.replace('\\', '/');
        normalizedFileName = normalizedFileName.substring(normalizedFileName.lastIndexOf('/') + 1).trim();
        if (normalizedFileName.isBlank() || ".".equals(normalizedFileName) || "..".equals(normalizedFileName)) {
            throw new IllegalArgumentException("file name is invalid");
        }
        return normalizePrefix(prefix) + normalizedFileName;
    }

    public static boolean isDirectoryMarker(String objectKey) {
        return objectKey != null && objectKey.endsWith("/");
    }

    public static List<String> parentDirectoryPrefixes(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            return List.of();
        }
        List<String> prefixes = new ArrayList<>();
        int end = objectKey.length();
        while (end > 0) {
            int slash = objectKey.lastIndexOf('/', end - 1);
            if (slash < 0) {
                break;
            }
            prefixes.add(objectKey.substring(0, slash + 1));
            end = slash;
        }
        return Collections.unmodifiableList(prefixes);
    }
}
