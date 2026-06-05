package io.github.jerryt92.j2agent.service.llm.agent;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * 插件 JAR 专用 ClassLoader：父加载器已有的类与 Spring 容器共享，其余从 JAR 加载。
 * 实例化与依赖注入由 Spring {@code getBean} 完成。
 */
public class PluginAgentClassLoader extends URLClassLoader {

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
            try {
                return Class.forName(name, resolve, getParent());
            } catch (ClassNotFoundException parentMissing) {
                Class<?> fromJar = findClass(name);
                return maybeResolve(fromJar, resolve);
            }
        }
    }

    private Class<?> maybeResolve(Class<?> clazz, boolean resolve) {
        if (resolve) {
            resolveClass(clazz);
        }
        return clazz;
    }
}
