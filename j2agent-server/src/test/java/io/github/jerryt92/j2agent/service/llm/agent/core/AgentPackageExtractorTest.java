package io.github.jerryt92.j2agent.service.llm.agent.core;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPackageExtractorTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExtractValidTarGzPackageToTargetDirectory() throws Exception {
        byte[] archive = tarGz(entry("plugin.jar", "jar-content".getBytes(StandardCharsets.UTF_8)),
                entry("resources/system-prompt.md", "prompt".getBytes(StandardCharsets.UTF_8)));

        MockMultipartFile file = new MockMultipartFile("file", "demo-agent.tar.gz", "application/gzip", archive);
        Path target = tempDir.resolve("agents").resolve("demo-agent");
        AgentPackageExtractor.extractToDirectory(file, target);

        assertTrue(Files.exists(target.resolve("plugin.jar")));
        assertTrue(Files.isDirectory(target.resolve("resources")));
        assertEquals("jar-content", Files.readString(target.resolve("plugin.jar")));
    }

    @Test
    void shouldRejectZipSlipEntriesInArchive() throws Exception {
        byte[] archive = tarGz(entry("../escape.txt", "bad".getBytes(StandardCharsets.UTF_8)));
        MockMultipartFile file = new MockMultipartFile("file", "evil.tar.gz", "application/gzip", archive);
        Path target = tempDir.resolve("evil-agent");
        assertThrows(IOException.class, () -> AgentPackageExtractor.extractToDirectory(file, target));
        assertTrue(!Files.exists(target) || Files.list(target).findAny().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/absolute.txt",
            "\\absolute.txt",
            "C:/outside.txt",
            "D:\\outside.txt",
            "../escape.txt",
            "nested/../../outside.txt"
    })
    void shouldRejectUnsafeEntryPaths(String entryName) {
        assertThrows(IOException.class, () -> AgentPackageExtractor.validateEntryPath(entryName));
    }

    @Test
    void shouldResolveSafeDestinationInsideTargetDirectory() throws Exception {
        Path target = tempDir.resolve("demo-agent");
        Path destination = AgentPackageExtractor.resolveSafeDestination(target, "resources/prompt.md");
        Path targetRoot = target.toAbsolutePath().normalize();
        assertTrue(destination.startsWith(targetRoot));
        assertEquals(targetRoot.resolve("resources/prompt.md").normalize(), destination);
    }

    private static byte[] tarGz(TarEntrySpec... entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(buffer);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
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
