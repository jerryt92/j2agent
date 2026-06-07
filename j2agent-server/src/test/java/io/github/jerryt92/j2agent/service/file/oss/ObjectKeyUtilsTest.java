package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.service.file.oss.util.ObjectKeyUtils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectKeyUtilsTest {
    @Test
    void shouldDetectDirectoryMarker() {
        assertTrue(ObjectKeyUtils.isDirectoryMarker("manual/images/"));
        assertFalse(ObjectKeyUtils.isDirectoryMarker("manual/images/logo.png"));
    }

    @Test
    void shouldCollectParentDirectoryPrefixesFromDeepestToShallowest() {
        assertEquals(
                List.of("manual/images/", "manual/"),
                ObjectKeyUtils.parentDirectoryPrefixes("manual/images/logo.png")
        );
        assertEquals(List.of(), ObjectKeyUtils.parentDirectoryPrefixes("logo.png"));
    }
}