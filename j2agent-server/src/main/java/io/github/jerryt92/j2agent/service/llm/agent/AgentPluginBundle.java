package io.github.jerryt92.j2agent.service.llm.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 插件部署单元：瘦 JAR + 可选外部 {@code resources/} 目录（tar.gz 解压布局）。
 */
public record AgentPluginBundle(File jarFile, File resourcesDir, String label) {

    private static final Logger log = LoggerFactory.getLogger(AgentPluginBundle.class);

    /**
     * 发现插件目录下的全部 bundle：根目录裸 JAR（兼容旧部署）+ 一级子目录（JAR + resources/）。
     */
    public static List<AgentPluginBundle> discover(String pluginPath) {
        if (!StringUtils.hasText(pluginPath)) {
            return List.of();
        }
        File root = new File(pluginPath);
        if (!root.exists() || !root.isDirectory()) {
            return List.of();
        }
        List<AgentPluginBundle> bundles = new ArrayList<>();
        File[] rootJars = root.listFiles((dir, name) -> name.endsWith(".jar"));
        if (rootJars != null) {
            for (File jar : rootJars) {
                bundles.add(new AgentPluginBundle(jar, null, jar.getName()));
            }
        }
        File[] subdirs = root.listFiles(File::isDirectory);
        if (subdirs != null) {
            for (File dir : subdirs) {
                AgentPluginBundle bundle = discoverSubdirectory(dir);
                if (bundle != null) {
                    bundles.add(bundle);
                }
            }
        }
        bundles.sort(Comparator.comparing(AgentPluginBundle::label));
        return bundles;
    }

    private static AgentPluginBundle discoverSubdirectory(File dir) {
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
        return new AgentPluginBundle(jar, resources, dir.getName() + "/" + jar.getName());
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
