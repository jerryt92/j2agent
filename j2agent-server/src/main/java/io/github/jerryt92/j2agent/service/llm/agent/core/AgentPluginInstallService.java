package io.github.jerryt92.j2agent.service.llm.agent.core;

import io.github.jerryt92.j2agent.config.plugin.PluginLayout;
import io.github.jerryt92.j2agent.config.plugin.PluginProperties;
import io.github.jerryt92.j2agent.service.rag.SimpleRagStoreSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Agent 插件包安装与删除：解压 tar.gz 到 agents 目录并触发热重载。
 */
@Slf4j
@Service
public class AgentPluginInstallService {

    private final PluginProperties pluginProperties;
    private final AgentPluginRegistry agentPluginRegistry;
    private final AgentPluginReloadService agentPluginReloadService;
    private final SimpleRagStoreSyncService simpleRagStoreSyncService;

    public AgentPluginInstallService(PluginProperties pluginProperties,
                                     AgentPluginRegistry agentPluginRegistry,
                                     AgentPluginReloadService agentPluginReloadService,
                                     SimpleRagStoreSyncService simpleRagStoreSyncService) {
        this.pluginProperties = pluginProperties;
        this.agentPluginRegistry = agentPluginRegistry;
        this.agentPluginReloadService = agentPluginReloadService;
        this.simpleRagStoreSyncService = simpleRagStoreSyncService;
    }

    public AgentInstallOutcome installPackage(MultipartFile file, boolean replace) {
        if (file == null || file.isEmpty()) {
            return AgentInstallOutcome.failure("Agent package file is required");
        }
        String originalFilename = file.getOriginalFilename();
        if (!StringUtils.hasText(originalFilename)) {
            return AgentInstallOutcome.failure("Agent package filename is required");
        }
        String agentDir;
        try {
            agentDir = deriveAgentDir(originalFilename);
        } catch (IllegalArgumentException ex) {
            return AgentInstallOutcome.failure(ex.getMessage());
        }

        try {
            return agentPluginRegistry.runExclusive(() -> installFromArchive(file, agentDir, replace));
        } catch (AgentInstallConflictException ex) {
            return AgentInstallOutcome.conflict(
                    ex.getMessage(),
                    ex.conflictingAgentIds(),
                    ex.existingAgentDir(),
                    ex.incomingAgentIds());
        } catch (Exception ex) {
            log.error("Failed to install agent package {}", originalFilename, ex);
            return AgentInstallOutcome.failure(ex.getMessage() != null
                    ? ex.getMessage()
                    : "Failed to install agent package");
        }
    }

    /**
     * 删除 {@code <plugin.path>/agents/<agentDir>/} 整个安装目录（含 JAR 与 resources/），
     * 随后调用 {@link AgentPluginReloadService#reload()} 走与手动重载相同的逻辑。
     */
    public AgentPluginRegistry.AgentPluginReloadOutcome deletePackage(String agentDir) {
        try {
            validateAgentDir(agentDir);
        } catch (IllegalArgumentException ex) {
            return AgentPluginRegistry.AgentPluginReloadOutcome.failure(
                    List.of(), agentPluginRegistry.getStatus().loadedAgentIds(), ex.getMessage());
        }
        try {
            return agentPluginRegistry.runExclusive(() -> {
                File target = new File(resolveAgentsRoot(), agentDir);
                if (!target.isDirectory()) {
                    return AgentPluginRegistry.AgentPluginReloadOutcome.failure(
                            listJarLabels(), currentLoadedIds(),
                            "Agent package directory not found: " + agentDir);
                }
                // Windows 下 JAR 被插件 ClassLoader 锁定，先卸载释放文件锁再删除
                agentPluginRegistry.unloadPlugins();
                try {
                    AgentPackageExtractor.deleteRecursivelyStrict(target.toPath());
                } catch (IOException deleteEx) {
                    log.error("Failed to delete agent package directory {}", target.getAbsolutePath(), deleteEx);
                    // 恢复剩余（含未删成功的）插件加载状态
                    agentPluginReloadService.reload();
                    return AgentPluginRegistry.AgentPluginReloadOutcome.failure(
                            listJarLabels(), currentLoadedIds(), deleteEx.getMessage());
                }
                return agentPluginReloadService.reload();
            });
        } catch (Exception ex) {
            log.error("Failed to delete agent package {}", agentDir, ex);
            return AgentPluginRegistry.AgentPluginReloadOutcome.failure(
                    listJarLabels(), currentLoadedIds(), ex.getMessage());
        }
    }

    private AgentInstallOutcome installFromArchive(MultipartFile file, String agentDir, boolean replace)
            throws IOException, AgentInstallConflictException {
        List<String> incomingAgentIds = AgentPackageExtractor.previewAgentIds(file, agentPluginRegistry);
        if (incomingAgentIds.isEmpty()) {
            return AgentInstallOutcome.failure("No AiAgent implementation found in uploaded package");
        }

        Set<String> builtinAgentIds = agentPluginRegistry.getBuiltinAgentIds();
        List<String> builtinConflicts = incomingAgentIds.stream()
                .filter(builtinAgentIds::contains)
                .sorted()
                .toList();
        if (!builtinConflicts.isEmpty()) {
            return AgentInstallOutcome.failure(
                    "Plugin agentId conflicts with built-in agent: " + String.join(", ", builtinConflicts));
        }

        File agentsRoot = resolveAgentsRoot();
        Path targetPath = new File(agentsRoot, agentDir).toPath();
        boolean directoryExists = Files.isDirectory(targetPath);

        List<AgentPluginRegistry.InstalledPackageInfo> installed = agentPluginRegistry.listInstalledPackages();
        Set<String> conflictingAgentIds = new LinkedHashSet<>();
        String existingAgentDir = null;
        for (AgentPluginRegistry.InstalledPackageInfo pkg : installed) {
            if (agentDir.equals(pkg.agentDir())) {
                continue;
            }
            for (String incomingId : incomingAgentIds) {
                if (pkg.agentIds().contains(incomingId)) {
                    conflictingAgentIds.add(incomingId);
                    existingAgentDir = pkg.agentDir();
                }
            }
        }

        if (!replace && (directoryExists || !conflictingAgentIds.isEmpty())) {
            throw new AgentInstallConflictException(
                    buildConflictMessage(directoryExists, conflictingAgentIds, agentDir, existingAgentDir),
                    new ArrayList<>(conflictingAgentIds),
                    existingAgentDir != null ? existingAgentDir : agentDir,
                    incomingAgentIds);
        }

        boolean pluginsUnloaded = false;
        try {
            if (replace) {
                agentPluginRegistry.unloadPlugins();
                pluginsUnloaded = true;
                if (directoryExists) {
                    AgentPackageExtractor.deleteRecursivelyStrict(targetPath);
                }
                for (AgentPluginRegistry.InstalledPackageInfo pkg : installed) {
                    if (agentDir.equals(pkg.agentDir())) {
                        continue;
                    }
                    boolean overlaps = pkg.agentIds().stream().anyMatch(incomingAgentIds::contains);
                    if (overlaps) {
                        AgentPackageExtractor.deleteRecursivelyStrict(new File(agentsRoot, pkg.agentDir()).toPath());
                    }
                }
            }

            AgentPackageExtractor.extractToDirectory(file, targetPath);
            AgentPluginBundle bundle = AgentPluginBundle.fromAgentDirectory(targetPath.toFile());
            if (agentPluginRegistry.scanBundleAgentClasses(bundle).isEmpty()) {
                AgentPackageExtractor.deleteRecursivelyStrict(targetPath);
                return AgentInstallOutcome.failure("No AiAgent implementation found in uploaded package");
            }
        } catch (IOException ex) {
            if (pluginsUnloaded) {
                agentPluginReloadService.reload();
            }
            throw ex;
        }

        // 替换时失效所属 SimpleRag，使后续 reload 同步走重建而非 COMPLETED 跳过
        if (replace) {
            simpleRagStoreSyncService.invalidateByOwnerAgentIds(incomingAgentIds);
        }

        AgentPluginRegistry.AgentPluginReloadOutcome reloadOutcome = agentPluginReloadService.reload();
        if (!reloadOutcome.success()) {
            return AgentInstallOutcome.failure(reloadOutcome.message());
        }
        return AgentInstallOutcome.success(reloadOutcome.jarFiles(), reloadOutcome.loadedAgentIds());
    }

    private static String buildConflictMessage(boolean directoryExists,
                                               Set<String> conflictingAgentIds,
                                               String agentDir,
                                               String existingAgentDir) {
        if (!conflictingAgentIds.isEmpty()) {
            return "Agent id already installed: "
                    + String.join(", ", conflictingAgentIds)
                    + " (existing directory: " + existingAgentDir + ")";
        }
        return "Agent package directory already exists: " + agentDir;
    }

    private File resolveAgentsRoot() throws IOException {
        String pluginPath = pluginProperties.resolvePath();
        if (!StringUtils.hasText(pluginPath)) {
            throw new IllegalStateException("j2agent.plugin.path is not configured");
        }
        File agentsRoot = AgentPluginBundle.resolveAgentsRoot(pluginPath);
        Files.createDirectories(agentsRoot.toPath());
        return agentsRoot;
    }

    private static String deriveAgentDir(String originalFilename) {
        String normalized = originalFilename.replace('\\', '/');
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".tar.gz")) {
            return fileName.substring(0, fileName.length() - ".tar.gz".length());
        }
        if (lower.endsWith(".tgz")) {
            return fileName.substring(0, fileName.length() - ".tgz".length());
        }
        throw new IllegalArgumentException("Agent package must be a .tar.gz or .tgz file");
    }

    private static void validateAgentDir(String agentDir) {
        if (!StringUtils.hasText(agentDir) || agentDir.contains("..") || agentDir.contains("/") || agentDir.contains("\\")) {
            throw new IllegalArgumentException("Invalid agent directory name");
        }
        if (PluginLayout.AGENTS_DIR_NAME.equals(agentDir) || PluginLayout.SKILLS_DIR_NAME.equals(agentDir)) {
            throw new IllegalArgumentException("Reserved agent directory name: " + agentDir);
        }
    }

    private List<String> listJarLabels() {
        return agentPluginRegistry.getStatus().jarFiles();
    }

    private List<String> currentLoadedIds() {
        return agentPluginRegistry.getStatus().loadedAgentIds();
    }

    public static final class AgentInstallOutcome {
        private final boolean success;
        private final boolean conflict;
        private final String message;
        private final List<String> conflictingAgentIds;
        private final String existingAgentDir;
        private final List<String> incomingAgentIds;
        private final List<String> jarFiles;
        private final List<String> loadedAgentIds;

        private AgentInstallOutcome(boolean success,
                                  boolean conflict,
                                  String message,
                                  List<String> conflictingAgentIds,
                                  String existingAgentDir,
                                  List<String> incomingAgentIds,
                                  List<String> jarFiles,
                                  List<String> loadedAgentIds) {
            this.success = success;
            this.conflict = conflict;
            this.message = message;
            this.conflictingAgentIds = conflictingAgentIds;
            this.existingAgentDir = existingAgentDir;
            this.incomingAgentIds = incomingAgentIds;
            this.jarFiles = jarFiles;
            this.loadedAgentIds = loadedAgentIds;
        }

        static AgentInstallOutcome success(List<String> jarFiles, List<String> loadedAgentIds) {
            return new AgentInstallOutcome(true, false, null, List.of(), null, List.of(), jarFiles, loadedAgentIds);
        }

        static AgentInstallOutcome failure(String message) {
            return new AgentInstallOutcome(false, false, message, List.of(), null, List.of(), List.of(), List.of());
        }

        static AgentInstallOutcome conflict(String message,
                                            List<String> conflictingAgentIds,
                                            String existingAgentDir,
                                            List<String> incomingAgentIds) {
            return new AgentInstallOutcome(false, true, message, conflictingAgentIds, existingAgentDir,
                    incomingAgentIds, List.of(), List.of());
        }

        public boolean success() {
            return success;
        }

        public boolean conflict() {
            return conflict;
        }

        public String message() {
            return message;
        }

        public List<String> conflictingAgentIds() {
            return conflictingAgentIds;
        }

        public String existingAgentDir() {
            return existingAgentDir;
        }

        public List<String> incomingAgentIds() {
            return incomingAgentIds;
        }

        public List<String> jarFiles() {
            return jarFiles;
        }

        public List<String> loadedAgentIds() {
            return loadedAgentIds;
        }
    }

    static final class AgentInstallConflictException extends Exception {
        private final List<String> conflictingAgentIds;
        private final String existingAgentDir;
        private final List<String> incomingAgentIds;

        AgentInstallConflictException(String message,
                                      List<String> conflictingAgentIds,
                                      String existingAgentDir,
                                      List<String> incomingAgentIds) {
            super(message);
            this.conflictingAgentIds = conflictingAgentIds;
            this.existingAgentDir = existingAgentDir;
            this.incomingAgentIds = incomingAgentIds;
        }

        List<String> conflictingAgentIds() {
            return conflictingAgentIds;
        }

        String existingAgentDir() {
            return existingAgentDir;
        }

        List<String> incomingAgentIds() {
            return incomingAgentIds;
        }
    }
}
