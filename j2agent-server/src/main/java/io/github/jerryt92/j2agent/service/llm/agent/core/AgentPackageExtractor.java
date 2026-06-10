package io.github.jerryt92.j2agent.service.llm.agent.core;

import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

/**
 * 将 Agent 插件 tar.gz 解压到目标目录。
 */
public final class AgentPackageExtractor {

    private AgentPackageExtractor() {
    }

    /**
     * 从 tar.gz 预读 JAR 并扫描 agentId（不写完整安装目录，用于冲突检测）。
     */
    public static List<String> previewAgentIds(MultipartFile file, AgentPluginRegistry registry) throws IOException {
        // 仅预读：将 tar.gz 内根 JAR 落到系统临时目录，扫描后删除，不写入 agents/ 安装路径
        Path tempJar = Files.createTempFile("agent-bundle-tmp", ".jar");
        try {
            if (!extractRootJarFromArchive(file, tempJar)) {
                return List.of();
            }
            AgentPluginBundle bundle = new AgentPluginBundle(tempJar.toFile(), null, tempJar.getFileName().toString());
            return registry.scanBundleAgentIds(bundle);
        } finally {
            Files.deleteIfExists(tempJar);
        }
    }

    /**
     * 解压到 {@code targetDir}（即 {@code agents/<agentDir>/}），与安装目标同卷，无需后续 move。
     */
    public static void extractToDirectory(MultipartFile file, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        Path targetRoot = targetDir.toAbsolutePath().normalize();
        try (InputStream raw = file.getInputStream();
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry)) {
                    continue;
                }
                String entryName = entry.getName();
                Path destination = resolveSafeDestination(targetRoot, entryName);
                if (entry.isDirectory()) {
                    Files.createDirectories(destination);
                } else {
                    Path parent = destination.getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.copy(tar, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException | RuntimeException ex) {
            deleteRecursively(targetDir);
            throw ex;
        }
    }

    private static boolean extractRootJarFromArchive(MultipartFile file, Path targetJar) throws IOException {
        boolean found = false;
        try (InputStream raw = file.getInputStream();
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(raw);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (!tar.canReadEntryData(entry) || entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName();
                validateEntryPath(entryName);
                if (!entryName.endsWith(".jar") || entryName.contains("/") || entryName.contains("\\")) {
                    continue;
                }
                try (OutputStream out = Files.newOutputStream(targetJar)) {
                    tar.transferTo(out);
                }
                found = true;
                break;
            }
        }
        return found;
    }

    static void validateEntryPath(String entryName) throws IOException {
        if (!hasText(entryName)) {
            throw new IOException("Empty tar entry path is not allowed");
        }
        if (entryName.startsWith("/") || entryName.startsWith("\\")) {
            throw new IOException("Absolute tar entry path is not allowed: " + entryName);
        }
        if (entryName.length() >= 2
                && Character.isLetter(entryName.charAt(0))
                && entryName.charAt(1) == ':') {
            throw new IOException("Absolute tar entry path is not allowed: " + entryName);
        }
        for (String segment : entryName.split("[/\\\\]")) {
            if ("..".equals(segment)) {
                throw new IOException("Parent traversal in tar entry is not allowed: " + entryName);
            }
        }
    }

    static Path resolveSafeDestination(Path targetRoot, String entryName) throws IOException {
        validateEntryPath(entryName);
        Path destination = targetRoot.resolve(entryName).normalize();
        if (!destination.startsWith(targetRoot)) {
            throw new IOException("Unsafe tar entry path: " + entryName);
        }
        return destination;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try {
            Files.walk(root)
                    .sorted((a, b) -> b.compareTo(a))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best effort cleanup
                        }
                    });
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    /**
     * 严格递归删除：任一文件删除失败（如 Windows 下 JAR 被 ClassLoader 锁定）立即抛出，
     * 调用方据此返回明确错误而非静默残留。
     */
    static void deleteRecursivelyStrict(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        List<Path> paths;
        try (var stream = Files.walk(root)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ex) {
                throw new IOException("Failed to delete " + path
                        + " — file may be locked by a plugin ClassLoader", ex);
            }
        }
    }
}
