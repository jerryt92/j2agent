package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.service.file.oss.util.ObjectKeyUtils;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 聊天图片对象键规则：chat/{userId}/{contextId}/{UUIDv7}_{文件名}
 */
public final class ChatFileKeys {
    private static final String CHAT_ROOT = "chat/";

    private ChatFileKeys() {
    }

    public static String chatObjectPrefix(String userId, String contextId) {
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(contextId)) {
            throw new IllegalArgumentException("userId and contextId must not be blank");
        }
        return ObjectKeyUtils.normalizePrefix(CHAT_ROOT + userId + "/" + contextId);
    }

    public static String chatStoredFileName(String originalFilename) {
        String basename = basename(originalFilename);
        return UUIDv7Utils.randomUUIDv7() + "_" + basename;
    }

    public static String chatObjectKey(String userId, String contextId, String originalFilename) {
        return ObjectKeyUtils.objectKey(chatObjectPrefix(userId, contextId), chatStoredFileName(originalFilename));
    }

    public static void requireOwnedKey(String objectKey, String userId) {
        if (!StringUtils.hasText(objectKey) || !objectKey.startsWith(CHAT_ROOT + userId + "/")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "image does not belong to current user");
        }
    }

    public static void requireOwnedKeyForReference(String objectKey, String userId, String contextId) {
        requireOwnedKey(objectKey, userId);
        if (objectKey.startsWith(chatObjectPrefix(userId, contextId))) {
            return;
        }
        if (!isNewFormatStoredBasename(basename(objectKey))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "image does not belong to current conversation");
    }

    static boolean isNewFormatStoredBasename(String basename) {
        return basename.length() > 23
                && basename.charAt(22) == '_'
                && basename.substring(0, 22).matches("[A-Za-z0-9]{22}");
    }

    static boolean isLegacyChatKey(String objectKey, String userId) {
        if (!StringUtils.hasText(objectKey) || !objectKey.startsWith(CHAT_ROOT + userId + "/")) {
            return false;
        }
        return !isNewFormatStoredBasename(basename(objectKey));
    }

    public static String displayName(String objectKey) {
        String basename = basename(objectKey);
        int underscore = basename.indexOf('_');
        if (underscore >= 0 && underscore < basename.length() - 1) {
            return basename.substring(underscore + 1);
        }
        return basename;
    }

    private static String basename(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash < 0 ? normalized : normalized.substring(slash + 1);
    }
}
