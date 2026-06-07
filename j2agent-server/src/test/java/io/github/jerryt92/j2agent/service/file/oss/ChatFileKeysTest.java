package io.github.jerryt92.j2agent.service.file.oss;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatFileKeysTest {

    @Test
    void chatObjectPrefixShouldIncludeUserAndContext() {
        assertEquals("chat/alice/ctx-1/", ChatFileKeys.chatObjectPrefix("alice", "ctx-1"));
    }

    @Test
    void chatStoredFileNameShouldPrefixUuidBeforeBasename() {
        String stored = ChatFileKeys.chatStoredFileName("folder/screenshot.png");
        assertTrue(stored.endsWith("_screenshot.png"));
        assertTrue(stored.contains("_"));
    }

    @Test
    void chatObjectKeyShouldUseContextDirectory() {
        String key = ChatFileKeys.chatObjectKey("alice", "ctx-1", "a.png");
        assertTrue(key.startsWith("chat/alice/ctx-1/"));
        assertTrue(key.endsWith("_a.png"));
    }

    @Test
    void displayNameShouldStripUuidPrefix() {
        assertEquals("screenshot.png", ChatFileKeys.displayName("chat/u/c/uuid_screenshot.png"));
    }

    @Test
    void requireOwnedKeyShouldRejectForeignUser() {
        assertThrows(ResponseStatusException.class,
                () -> ChatFileKeys.requireOwnedKey("chat/bob/ctx/file.png", "alice"));
    }

    @Test
    void requireOwnedKeyForReferenceShouldAllowMatchingContext() {
        String key = ChatFileKeys.chatObjectKey("alice", "ctx-1", "a.png");
        assertDoesNotThrow(() -> ChatFileKeys.requireOwnedKeyForReference(key, "alice", "ctx-1"));
    }

    @Test
    void requireOwnedKeyForReferenceShouldAllowLegacyKey() {
        String legacy = "chat/alice/upload-uuid/image.png";
        assertDoesNotThrow(() -> ChatFileKeys.requireOwnedKeyForReference(legacy, "alice", "ctx-1"));
    }

    @Test
    void requireOwnedKeyForReferenceShouldRejectOtherContext() {
        String otherContext = ChatFileKeys.chatObjectKey("alice", "ctx-2", "a.png");
        assertThrows(ResponseStatusException.class,
                () -> ChatFileKeys.requireOwnedKeyForReference(otherContext, "alice", "ctx-1"));
    }
}
