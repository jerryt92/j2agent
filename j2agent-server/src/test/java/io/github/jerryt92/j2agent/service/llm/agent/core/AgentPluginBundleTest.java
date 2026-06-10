package io.github.jerryt92.j2agent.service.llm.agent.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPluginBundleTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldAcceptValidAgentDirectoryLayout() throws Exception {
        Path agentDir = tempDir.resolve("demo-agent");
        Files.createDirectories(agentDir.resolve("resources"));
        Files.writeString(agentDir.resolve("demo.jar"), "jar");

        AgentPluginBundle bundle = AgentPluginBundle.fromAgentDirectory(agentDir.toFile());
        assertEquals("demo.jar", bundle.jarFile().getName());
        assertTrue(bundle.resourcesDir().isDirectory());
    }

    @Test
    void shouldRejectDirectoryWithoutResources() throws Exception {
        Path agentDir = tempDir.resolve("invalid-agent");
        Files.createDirectories(agentDir);
        Files.writeString(agentDir.resolve("demo.jar"), "jar");

        assertThrows(IllegalArgumentException.class,
                () -> AgentPluginBundle.fromAgentDirectory(agentDir.toFile()));
    }

    @Test
    void shouldResolveAgentsRootUnderPluginsPath() throws Exception {
        Path pluginsRoot = tempDir.resolve("plugins");
        Path agentsRoot = pluginsRoot.resolve("agents");
        Files.createDirectories(agentsRoot);

        Path resolved = AgentPluginBundle.resolveAgentsRoot(pluginsRoot.toString()).toPath();
        assertEquals(agentsRoot, resolved);
    }
}
