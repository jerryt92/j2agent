package io.github.jerryt92.j2agent.service.llm.agent;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * 插件 JAR 专用 ClassLoader：优先从 JAR 加载插件实现类，实例化与依赖注入由 Spring {@code getBean} 完成。
 * <p>
 * 对 {@code com.nms.platsvc.ai.center.*}：若主应用 ClassLoader 中已存在（如 {@code AiAgent}、{@code BaseTools}），
 * 则使用父加载器以保证与 Spring 容器为同一 Class；若仅存在于插件 JAR（新业务 Agent），则从 JAR 加载。
 */
public class PluginAgentClassLoader extends URLClassLoader {

    /**
     * 与主应用、Spring 容器共享的平台包前缀
     */
    private static final String PLATFORM_PACKAGE_PREFIX = "com.nms.platsvc.ai.center.";

    /**
     * @param urls   插件 JAR URL
     * @param parent 应用 ClassLoader（与 Spring 容器一致）
     */
    public PluginAgentClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                return maybeResolve(loaded, resolve);
            }
            if (name.startsWith(PLATFORM_PACKAGE_PREFIX)) {
                return loadPlatformClass(name, resolve);
            }
            return super.loadClass(name, resolve);
        }
    }

    /**
     * 平台包：父加载器已有则共享；否则视为插件独有实现，从 JAR 加载。
     */
    private Class<?> loadPlatformClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
            Class.forName(name, false, getParent());
            return Class.forName(name, resolve, getParent());
        } catch (ClassNotFoundException parentMissing) {
            Class<?> fromJar = findClass(name);
            return maybeResolve(fromJar, resolve);
        }
    }

    private Class<?> maybeResolve(Class<?> clazz, boolean resolve) {
        if (resolve) {
            resolveClass(clazz);
        }
        return clazz;
    }
}