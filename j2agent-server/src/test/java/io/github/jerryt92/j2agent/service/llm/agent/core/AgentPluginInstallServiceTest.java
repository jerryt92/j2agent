package io.github.jerryt92.j2agent.service.llm.agent.core;

import io.github.jerryt92.j2agent.config.PluginProperties;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentPluginInstallServiceTest {

    @TempDir
    Path tempDir;

    private PluginProperties pluginProperties;
    private AgentPluginRegistry agentPluginRegistry;
    private AgentPluginReloadService agentPluginReloadService;
    private AgentPluginInstallService installService;

    @BeforeEach
    void setUp() throws Exception {
        Path pluginsRoot = tempDir.resolve("plugins");
        Files.createDirectories(pluginsRoot.resolve("agents"));
        pluginProperties = new PluginProperties();
        pluginProperties.setPath(pluginsRoot.toString());

        agentPluginRegistry = mock(AgentPluginRegistry.class);
        agentPluginReloadService = mock(AgentPluginReloadService.class);
        installService = new AgentPluginInstallService(
                pluginProperties, agentPluginRegistry, agentPluginReloadService);

        when(agentPluginRegistry.runExclusive(any())).thenAnswer(invocation -> {
            Callable<?> callable = invocation.getArgument(0);
            return callable.call();
        });
        when(agentPluginRegistry.getBuiltinAgentIds()).thenReturn(java.util.Set.of());
        when(agentPluginRegistry.listInstalledPackages()).thenReturn(List.of());
        when(agentPluginRegistry.scanBundleAgentIds(any())).thenReturn(List.of("demo_agent"));
        when(agentPluginRegistry.scanBundleAgentClasses(any()))
                .thenReturn(List.of(AgentPluginRegistryResolveAgentIdTest.StubPluginAgent.class));
        when(agentPluginReloadService.reload()).thenReturn(
                AgentPluginRegistry.AgentPluginReloadOutcome.success(List.of(), List.of("demo_agent")));
        when(agentPluginRegistry.getStatus()).thenReturn(
                new AgentPluginRegistry.AgentPluginStatus(List.of(), List.of(), List.of()));
    }

    @Test
    void shouldRejectNonTarGzFilename() {
        MockMultipartFile file = new MockMultipartFile("file", "demo.zip", "application/zip", new byte[] {1});
        AgentPluginInstallService.AgentInstallOutcome outcome = installService.installPackage(file, false);
        assertFalse(outcome.success());
        assertTrue(outcome.message().contains(".tar.gz"));
    }

    @Test
    void shouldReturnConflictWhenDirectoryExistsWithoutReplace() throws Exception {
        Path agentsRoot = tempDir.resolve("plugins").resolve("agents");
        Path existing = agentsRoot.resolve("demo-agent");
        Files.createDirectories(existing.resolve("resources"));
        Files.writeString(existing.resolve("demo.jar"), "jar");

        MockMultipartFile file = packageArchive("demo-agent.tar.gz");
        AgentPluginInstallService.AgentInstallOutcome outcome = installService.installPackage(file, false);
        assertTrue(outcome.conflict());
        assertFalse(outcome.success());
    }

    @Test
    void shouldInstallWhenReplaceRequested() throws Exception {
        Path agentsRoot = tempDir.resolve("plugins").resolve("agents");
        Path existing = agentsRoot.resolve("demo-agent");
        Files.createDirectories(existing.resolve("resources"));
        Files.writeString(existing.resolve("old.jar"), "old");

        MockMultipartFile file = packageArchive("demo-agent.tar.gz");
        AgentPluginInstallService.AgentInstallOutcome outcome = installService.installPackage(file, true);
        assertTrue(outcome.success());
        assertTrue(Files.exists(agentsRoot.resolve("demo-agent").resolve("demo.jar")));
        verify(agentPluginRegistry).unloadPlugins();
        verify(agentPluginReloadService).reload();
    }

    @Test
    void shouldRejectInvalidAgentDirOnDelete() {
        AgentPluginRegistry.AgentPluginReloadOutcome outcome = installService.deletePackage("../escape");
        assertFalse(outcome.success());
    }

    @Test
    void shouldDeleteEntireAgentDirectoryIncludingResources() throws Exception {
        Path agentsRoot = tempDir.resolve("plugins").resolve("agents");
        Path existing = agentsRoot.resolve("demo-agent");
        Path resourcesDir = existing.resolve("resources");
        Files.createDirectories(resourcesDir);
        Files.writeString(resourcesDir.resolve("system-prompt.md"), "prompt");
        Files.writeString(existing.resolve("demo.jar"), "jar");

        AgentPluginRegistry.AgentPluginReloadOutcome outcome = installService.deletePackage("demo-agent");
        assertTrue(outcome.success());
        assertFalse(Files.exists(existing));
        assertFalse(Files.exists(resourcesDir));
        assertFalse(Files.exists(agentsRoot.resolve("demo-agent")));
        // 删除前必须先卸载插件释放 Windows 文件锁，再走统一 reload
        verify(agentPluginRegistry).unloadPlugins();
        verify(agentPluginReloadService).reload();
    }

    private MockMultipartFile packageArchive(String filename) throws IOException {
        byte[] archive = tarGz(
                entry("demo.jar", "jar".getBytes(StandardCharsets.UTF_8)),
                entry("resources/system-prompt.md", "prompt".getBytes(StandardCharsets.UTF_8)));
        return new MockMultipartFile("file", filename, "application/gzip", archive);
    }

    private static byte[] tarGz(TarEntrySpec... entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(buffer);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            for (TarEntrySpec spec : entries) {
                TarArchiveEntry entry = new TarArchiveEntry(spec.name());
                entry.setSize(spec.content().length);
                tar.putArchiveEntry(entry);
                tar.write(spec.content());
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
        return buffer.toByteArray();
    }

    private static TarEntrySpec entry(String name, byte[] content) {
        return new TarEntrySpec(name, content);
    }

    private record TarEntrySpec(String name, byte[] content) {
    }
}
