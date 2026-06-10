package io.github.jerryt92.j2agent.service.llm.agent.core;

import io.github.jerryt92.j2agent.config.plugin.PluginLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 插件部署单元：瘦 JAR + 可选外部 {@code resources/} 目录（tar.gz 解压布局）。
 */
public record AgentPluginBundle(File jarFile, File resourcesDir, String label) {

    private static final Logger log = LoggerFactory.getLogger(AgentPluginBundle.class);

    private static final Set<String> RESERVED_SUBDIRS = Set.of(
            PluginLayout.SKILLS_DIR_NAME,
            PluginLayout.AGENTS_DIR_NAME
    );

    /**
     * 发现 Agent 插件 bundle。{@code j2agent.plugin.path} 指向 {@code .../plugins} 根目录时，
     * 扫描 {@code agents/<agentDir>/}；若已指向 {@code .../plugins/agents}，则直接扫描其子目录。
     */
    public static List<AgentPluginBundle> discover(String pluginPath) {
        if (!StringUtils.hasText(pluginPath)) {
            return List.of();
        }
        AgentScanRoot scanRoot = resolveAgentScanRoot(new File(pluginPath));
        File root = scanRoot.root();
        if (!root.exists() || !root.isDirectory()) {
            return List.of();
        }
        List<AgentPluginBundle> bundles = new ArrayList<>();
        File[] rootJars = root.listFiles((dir, name) -> name.endsWith(".jar"));
        if (rootJars != null) {
            for (File jar : rootJars) {
                bundles.add(new AgentPluginBundle(jar, null, scanRoot.labelPrefix() + jar.getName()));
            }
        }
        File[] subdirs = root.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File dir : subdirs) {
                if (RESERVED_SUBDIRS.contains(dir.getName())) {
                    log.debug("Skip reserved plugin subdirectory: {}", dir.getName());
                    continue;
                }
                addAgentBundleIfPresent(bundles, dir, scanRoot.labelPrefix());
            }
        }
        bundles.sort(Comparator.comparing(AgentPluginBundle::label));
        return bundles;
    }

    private record AgentScanRoot(File root, String labelPrefix) {
    }

    private static AgentScanRoot resolveAgentScanRoot(File configuredRoot) {
        if (!configuredRoot.isDirectory()) {
            return new AgentScanRoot(configuredRoot, "");
        }
        if (PluginLayout.AGENTS_DIR_NAME.equals(configuredRoot.getName())) {
            return new AgentScanRoot(configuredRoot, PluginLayout.AGENTS_DIR_NAME + "/");
        }
        File agentsDir = new File(configuredRoot, PluginLayout.AGENTS_DIR_NAME);
        if (agentsDir.isDirectory()) {
            return new AgentScanRoot(agentsDir, PluginLayout.AGENTS_DIR_NAME + "/");
        }
        return new AgentScanRoot(configuredRoot, "");
    }

    private static void addAgentBundleIfPresent(List<AgentPluginBundle> bundles, File dir, String labelPrefix) {
        AgentPluginBundle bundle = discoverSubdirectory(dir, labelPrefix);
        if (bundle != null) {
            bundles.add(bundle);
        }
    }

    /**
     * 解析插件根目录下的 agents 扫描根（与 {@link #discover(String)} 一致）。
     */
    public static File resolveAgentsRoot(String pluginPath) {
        if (!StringUtils.hasText(pluginPath)) {
            throw new IllegalStateException("Plugin path is not configured");
        }
        return resolveAgentScanRoot(new File(pluginPath)).root();
    }

    /**
     * 校验并解析单个 Agent 安装目录（恰好 1 个 JAR + resources/）。
     */
    public static AgentPluginBundle fromAgentDirectory(File dir) {
        AgentPluginBundle bundle = discoverSubdirectory(dir, "");
        if (bundle == null) {
            throw new IllegalArgumentException(
                    "Invalid agent package layout in " + dir.getName() + " — require exactly one JAR and resources/");
        }
        return bundle;
    }

    private static AgentPluginBundle discoverSubdirectory(File dir, String labelPrefix) {
        File resources = new File(dir, "resources");
        File[] jars = dir.listFiles((d, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return null;
        }
        if (jars.length > 1) {
            log.warn("Skip plugin directory {} — multiple JARs found", dir.getName());
            return null;
        }
        if (!resources.isDirectory()) {
            log.warn("Skip plugin directory {} — missing resources/", dir.getName());
            return null;
        }
        File jar = jars[0];
        return new AgentPluginBundle(jar, resources, labelPrefix + dir.getName() + "/" + jar.getName());
    }

    /**
     * ClassLoader URL：外部 resources 优先，便于运维覆盖；瘦 JAR 提供 class。
     */
    public URL[] toClassLoaderUrls() throws MalformedURLException {
        List<URL> urls = new ArrayList<>(2);
        if (resourcesDir != null && resourcesDir.isDirectory()) {
            urls.add(resourcesDir.toURI().toURL());
        }
        urls.add(jarFile.toURI().toURL());
        return urls.toArray(URL[]::new);
    }
}
