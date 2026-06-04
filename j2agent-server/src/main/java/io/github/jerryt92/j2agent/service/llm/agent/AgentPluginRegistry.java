package io.github.jerryt92.j2agent.service.llm.agent;

import io.github.jerryt92.j2agent.config.PluginProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * 外部 Agent 插件 JAR 注册表：启动期与运行时从 {@link PluginProperties#getPath()} 加载/卸载。
 * 扫描并注册 JAR 内全部 Spring 组件（{@code @Component} 等），不仅限于 {@link AiAgent}；启动时仅预实例化 Agent。
 * 依赖 {@link PluginAgentClassLoader} 保证平台类与 Spring 容器一致，实例化统一走 {@code getBean}。
 * 启动期在 {@link ApplicationReadyEvent} 之后再 {@code getBean}，避免早于 {@code baseTools} 等单例就绪。
 */
@Slf4j
@Component
public class AgentPluginRegistry implements BeanDefinitionRegistryPostProcessor, ApplicationContextAware,
        ApplicationListener<ApplicationReadyEvent> {

    private volatile boolean startupActivated;

    private ApplicationContext applicationContext;
    private final List<DynamicRegistration> dynamicRegistrations = new CopyOnWriteArrayList<>();
    private final Object reloadLock = new Object();

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        if (applicationContext == null) {
            log.warn("ApplicationContext not ready, skip plugin load at registry phase.");
            return;
        }
        synchronized (reloadLock) {
            loadJarsAtStartup(registry);
        }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // 插件 Bean 在 ApplicationReady 之后实例化，此处仅注册 BeanDefinition
    }

    /**
     * 应用就绪后再加载插件 Agent，与手动 reload 时机一致（容器依赖已就绪）。
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (startupActivated) {
            return;
        }
        startupActivated = true;
        DefaultListableBeanFactory beanFactory =
                (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        activatePluginAgents(beanFactory, true);
        applicationContext.getBean(AgentRouter.class).refresh();
        log.info("Plugin agents activated on application ready.");
    }

    /**
     * 查询已加载插件 Agent 状态。
     */
    public AgentPluginStatus getStatus() {
        String pluginPath = resolvePluginPath();
        List<String> jarFiles = listJarFileNames(pluginPath);
        List<String> loadedAgentIds = dynamicRegistrations.stream()
                .map(DynamicRegistration::agentId)
                .filter(StringUtils::hasText)
                .sorted()
                .collect(Collectors.toList());
        return new AgentPluginStatus(jarFiles, loadedAgentIds);
    }

    /**
     * 重新扫描插件目录并热加载 JAR；与内置 Agent agentId 冲突时整次失败且不替换已加载插件。
     */
    public AgentPluginReloadOutcome reload() {
        synchronized (reloadLock) {
            String pluginPath = resolvePluginPath();
            List<File> jarFiles = listJarFiles(pluginPath);
            DefaultListableBeanFactory beanFactory =
                    (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
            Set<String> builtinAgentIds = collectBuiltinAgentIds(beanFactory);
            try {
                validatePluginAgentIds(jarFiles, builtinAgentIds, beanFactory);
            } catch (AgentPluginConflictException ex) {
                log.warn("Agent plugin reload rejected: {}", ex.getMessage());
                return AgentPluginReloadOutcome.failure(listJarFileNames(pluginPath),
                        currentLoadedAgentIds(), ex.getMessage());
            }
            unloadDynamicPlugins();
            for (File jarFile : jarFiles) {
                try {
                    scanAndRegisterJar(jarFile, beanFactory);
                } catch (Exception ex) {
                    log.error("Skip failed plugin JAR during reload scan: {}", jarFile.getAbsolutePath(), ex);
                }
            }
            List<String> loadedAgentIds = activateAllPluginBeans(beanFactory);
            return AgentPluginReloadOutcome.success(listJarFileNames(pluginPath), loadedAgentIds);
        }
    }

    /**
     * 当前由本注册表加载的动态 Bean 名称集合。
     */
    public Set<String> getDynamicBeanNames() {
        return dynamicRegistrations.stream()
                .map(DynamicRegistration::beanName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private void loadJarsAtStartup(BeanDefinitionRegistry registry) {
        String pluginPath = resolvePluginPath();
        if (!StringUtils.hasText(pluginPath)) {
            log.info("No plugin path configured, skip dynamic loading.");
            return;
        }
        List<File> jarFiles = listJarFiles(pluginPath);
        if (jarFiles.isEmpty()) {
            log.info("No JAR files found in plugin path: {}", pluginPath);
            return;
        }
        for (File jarFile : jarFiles) {
            try {
                scanAndRegisterJar(jarFile, registry);
            } catch (Exception ex) {
                log.error("Skip failed plugin JAR during startup scan: {}", jarFile.getAbsolutePath(), ex);
            }
        }
    }

    /**
     * 为已登记的插件 BeanDefinition 执行 Spring 注入与初始化。
     */
    private void activatePluginAgents(DefaultListableBeanFactory beanFactory, boolean failFastOnStartup) {
        if (!dynamicRegistrations.isEmpty()) {
            try {
                validatePluginAgentIds(
                        listJarFiles(resolvePluginPath()),
                        collectBuiltinAgentIds(beanFactory),
                        beanFactory);
            } catch (AgentPluginConflictException ex) {
                throw new IllegalStateException("Agent plugin startup load failed: " + ex.getMessage(), ex);
            }
        }
        activateAllPluginBeans(beanFactory);
    }

    /**
     * 实例化已注册的插件 Bean：先依赖类，再 AiAgent。
     */
    private List<String> activateAllPluginBeans(DefaultListableBeanFactory beanFactory) {
        activatePluginDependencyBeans(beanFactory);
        List<String> agentIds = new ArrayList<>();
        for (DynamicRegistration registration : new ArrayList<>(dynamicRegistrations)) {
            if (!registration.aiAgent()) {
                continue;
            }
            try {
                if (beanFactory.containsSingleton(registration.beanName())) {
                    Object existing = beanFactory.getBean(registration.beanName());
                    if (existing instanceof AiAgent aiAgent) {
                        registration.setAgentId(aiAgent.getAgentId());
                        agentIds.add(aiAgent.getAgentId());
                    }
                    continue;
                }
                AiAgent aiAgent = obtainPluginAgent(beanFactory, registration.beanName());
                registration.setAgentId(aiAgent.getAgentId());
                agentIds.add(aiAgent.getAgentId());
                log.info("Loaded plugin agent: agentId={}, bean={}, jar={}",
                        aiAgent.getAgentId(), registration.beanName(), registration.jarFileName());
            } catch (Exception ex) {
                log.error("Skip failed plugin agent bean: bean={}, jar={}",
                        registration.beanName(), registration.jarFileName(), ex);
            }
        }
        return agentIds;
    }

    /**
     * 先实例化插件依赖 Bean（如 IntelligentReportSqlTools），再创建 AiAgent，避免构造器注入找不到 Bean。
     */
    private void activatePluginDependencyBeans(DefaultListableBeanFactory beanFactory) {
        activatePluginDependencyBeans(beanFactory, dynamicRegistrations);
    }

    private void activatePluginDependencyBeans(DefaultListableBeanFactory beanFactory,
                                               Iterable<DynamicRegistration> registrations) {
        for (DynamicRegistration registration : registrations) {
            if (registration.aiAgent()) {
                continue;
            }
            if (beanFactory.containsSingleton(registration.beanName())) {
                continue;
            }
            try {
                beanFactory.getBean(registration.beanName());
                log.info("Initialized plugin dependency bean: {} (jar={})",
                        registration.beanName(), registration.jarFileName());
            } catch (Exception ex) {
                log.error("Skip failed plugin dependency bean: bean={}, jar={}",
                        registration.beanName(), registration.jarFileName(), ex);
            }
        }
    }

    /**
     * 扫描 JAR，将其中 Spring 组件注册为 BeanDefinition（构造器自动装配）。
     */
    private List<DynamicRegistration> scanAndRegisterJar(File jarFile, BeanDefinitionRegistry registry) {
        log.info("Loading plugin JAR: {}", jarFile.getAbsolutePath());
        List<DynamicRegistration> added = new ArrayList<>();
        PluginAgentClassLoader classLoader = null;
        try (JarFile jar = new JarFile(jarFile)) {
            URL[] urls = {jarFile.toURI().toURL()};
            classLoader = new PluginAgentClassLoader(urls, applicationContext.getClassLoader());
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                String className = entry.getName().replace('/', '.')
                        .substring(0, entry.getName().length() - 6);
                try {
                    Class<?> clazz = classLoader.loadClass(className);
                    if (!isSpringComponent(clazz)) {
                        continue;
                    }
                    String beanName = resolveBeanName(clazz, registry);
                    if (isDuplicateRegistration(beanName)) {
                        continue;
                    }
                    if (registry.containsBeanDefinition(beanName)
                            && !replaceExistingPluginBeanDefinition(registry, beanName)) {
                        log.info("Skip plugin class {} — non-plugin bean {} already exists",
                                clazz.getName(), beanName);
                        continue;
                    }
                    GenericBeanDefinition beanDefinition = new GenericBeanDefinition();
                    beanDefinition.setBeanClass(clazz);
                    beanDefinition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
                    beanDefinition.setAutowireCandidate(true);
                    beanDefinition.setLazyInit(true);
                    registry.registerBeanDefinition(beanName, beanDefinition);
                    boolean aiAgent = isConcreteAiAgent(clazz);
                    DynamicRegistration registration = new DynamicRegistration(beanName, classLoader, jarFile.getName(), aiAgent);
                    dynamicRegistrations.add(registration);
                    added.add(registration);
                    log.info("Registered dynamic plugin bean definition: {} from {} (aiAgent={})",
                            beanName, clazz.getName(), aiAgent);
                } catch (Throwable e) {
                    log.error("", e);
                }
            }
        } catch (Exception e) {
            closeQuietly(classLoader);
            throw new IllegalStateException("Failed to load JAR: " + jarFile.getAbsolutePath(), e);
        }
        if (added.isEmpty()) {
            closeQuietly(classLoader);
        }
        return added;
    }

    /**
     * 热加载时替换同名的旧插件 Bean 定义，避免 ClassLoader 切换后类型不一致。
     *
     * @return true 表示已移除旧定义可继续注册；false 表示存在内置等非插件 Bean 占用该 beanName，应跳过
     */
    private boolean replaceExistingPluginBeanDefinition(BeanDefinitionRegistry registry, String beanName) {
        BeanDefinition existing = registry.getBeanDefinition(beanName);
        if (!(existing instanceof GenericBeanDefinition generic) || !generic.isLazyInit()) {
            return false;
        }
        removeBeanDefinition(registry, beanName);
        log.info("Replaced previous plugin bean definition: {}", beanName);
        return true;
    }

    private static void removeBeanDefinition(BeanDefinitionRegistry registry, String beanName) {
        if (registry instanceof DefaultListableBeanFactory beanFactory) {
            if (beanFactory.containsSingleton(beanName)) {
                beanFactory.destroySingleton(beanName);
            }
        }
        if (registry.containsBeanDefinition(beanName)) {
            registry.removeBeanDefinition(beanName);
        }
    }

    /**
     * 交由 Spring 容器完成构造器注入、字段注入与初始化。
     */
    private AiAgent obtainPluginAgent(DefaultListableBeanFactory beanFactory, String beanName) {
        Object bean = beanFactory.getBean(beanName);
        if (!(bean instanceof AiAgent aiAgent)) {
            throw new IllegalStateException("Plugin bean is not AiAgent: " + beanName);
        }
        return aiAgent;
    }

    private void unloadDynamicPlugins() {
        DefaultListableBeanFactory beanFactory =
                (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
        List<DynamicRegistration> snapshot = new ArrayList<>(dynamicRegistrations);
        // 先销毁 AiAgent，再销毁其依赖 Bean
        snapshot.sort((a, b) -> Boolean.compare(b.aiAgent(), a.aiAgent()));
        Set<URLClassLoader> classLoaders = new LinkedHashSet<>();
        for (DynamicRegistration registration : snapshot) {
            String beanName = registration.beanName();
            try {
                if (beanFactory.containsSingleton(beanName)) {
                    beanFactory.destroySingleton(beanName);
                }
                if (beanFactory.containsBeanDefinition(beanName)) {
                    beanFactory.removeBeanDefinition(beanName);
                }
            } catch (Exception ex) {
                log.warn("Failed to remove dynamic bean: {}", beanName, ex);
            }
            if (registration.classLoader() != null) {
                classLoaders.add(registration.classLoader());
            }
        }
        classLoaders.forEach(AgentPluginRegistry::closeQuietly);
        dynamicRegistrations.clear();
    }

    private void validatePluginAgentIds(List<File> jarFiles, Set<String> builtinAgentIds,
                                        DefaultListableBeanFactory beanFactory)
            throws AgentPluginConflictException {
        Set<String> seenPluginIds = new LinkedHashSet<>();
        for (File jarFile : jarFiles) {
            PluginAgentClassLoader scanLoader = null;
            try (JarFile jar = new JarFile(jarFile)) {
                scanLoader = new PluginAgentClassLoader(
                        new URL[]{jarFile.toURI().toURL()}, applicationContext.getClassLoader());
                for (Class<?> agentClass : scanAiAgentClasses(jar, scanLoader)) {
                    String agentId = resolveAgentId(agentClass, beanFactory);
                    if (!StringUtils.hasText(agentId)) {
                        log.warn("Skip plugin agentId validation for {} in {} — cannot resolve agentId",
                                agentClass.getName(), jarFile.getName());
                        continue;
                    }
                    if (builtinAgentIds.contains(agentId)) {
                        throw new AgentPluginConflictException(
                                "Plugin agentId conflicts with built-in agent: " + agentId
                                        + " (class=" + agentClass.getName() + ", jar=" + jarFile.getName() + ")");
                    }
                    if (!seenPluginIds.add(agentId)) {
                        throw new AgentPluginConflictException(
                                "Duplicate plugin agentId in JAR directory: " + agentId);
                    }
                }
            } catch (Exception ex) {
                if (ex instanceof AgentPluginConflictException conflict) {
                    throw conflict;
                }
                throw new IllegalStateException("Failed to validate plugin JAR: " + jarFile.getAbsolutePath(), ex);
            } finally {
                closeQuietly(scanLoader);
            }
        }
    }

    private Set<String> collectBuiltinAgentIds(DefaultListableBeanFactory beanFactory) {
        Set<String> ids = new LinkedHashSet<>();
        if (applicationContext != null) {
            try {
                applicationContext.getBeansOfType(AiAgent.class).forEach((name, agent) -> {
                    if (!getDynamicBeanNames().contains(name)) {
                        ids.add(agent.getAgentId());
                    }
                });
            } catch (BeansException ignored) {
                // 启动早期容器尚未完成实例化
            }
        }
        return ids;
    }

    private List<Class<?>> scanAiAgentClasses(JarFile jar, PluginAgentClassLoader classLoader) {
        List<Class<?>> result = new ArrayList<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                continue;
            }
            String className = entry.getName().replace('/', '.')
                    .substring(0, entry.getName().length() - 6);
            try {
                Class<?> clazz = classLoader.loadClass(className);
                if (isConcreteAiAgent(clazz)) {
                    result.add(clazz);
                }
            } catch (Throwable ignored) {
                // 预扫描忽略无法加载的类
            }
        }
        return result;
    }

    /**
     * 预扫描 agentId：使用 Spring {@link AutowireCapableBeanFactory#autowire}，不注册 Bean。
     */
    private String resolveAgentId(Class<?> clazz, DefaultListableBeanFactory beanFactory) {
        if (!AiAgent.class.isAssignableFrom(clazz) || beanFactory == null) {
            return null;
        }
        try {
            Object probe = beanFactory.autowire(clazz, AutowireCapableBeanFactory.AUTOWIRE_CONSTRUCTOR, false);
            if (probe instanceof AiAgent aiAgent) {
                return aiAgent.getAgentId();
            }
        } catch (Exception ex) {
            log.debug("Cannot resolve agentId for class {}: {}", clazz.getName(), ex.getMessage());
        }
        return null;
    }

    private boolean isDuplicateRegistration(String beanName) {
        return dynamicRegistrations.stream().anyMatch(r -> r.beanName().equals(beanName));
    }

    private String resolvePluginPath() {
        if (applicationContext != null) {
            PluginProperties properties = applicationContext.getBean(PluginProperties.class);
            if (properties != null && StringUtils.hasText(properties.getPath())) {
                return properties.getPath();
            }
        }
        return applicationContext != null
                ? applicationContext.getEnvironment().getProperty("com.nms.ai.plugin.path")
                : null;
    }

    private static List<File> listJarFiles(String pluginPath) {
        if (!StringUtils.hasText(pluginPath)) {
            return List.of();
        }
        File pathFile = new File(pluginPath);
        if (!pathFile.exists() || !pathFile.isDirectory()) {
            return List.of();
        }
        File[] jars = pathFile.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return List.of();
        }
        return List.of(jars);
    }

    private static List<String> listJarFileNames(String pluginPath) {
        return listJarFiles(pluginPath).stream()
                .map(File::getName)
                .sorted()
                .collect(Collectors.toList());
    }

    private List<String> currentLoadedAgentIds() {
        return dynamicRegistrations.stream()
                .map(DynamicRegistration::agentId)
                .filter(StringUtils::hasText)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 解析插件 Bean 名称，优先使用 {@code @Component} 等注解的 value（如 intelligentReportKbRetriever）。
     */
    private String resolveBeanName(Class<?> clazz, BeanDefinitionRegistry registry) {
        String beanName = resolveStereotypeBeanName(clazz);
        if (registry.containsBeanDefinition(beanName)) {
            String fqcn = clazz.getName();
            if (!registry.containsBeanDefinition(fqcn)) {
                return fqcn;
            }
        }
        return beanName;
    }

    private static String resolveStereotypeBeanName(Class<?> clazz) {
        Component component = clazz.getAnnotation(Component.class);
        if (component != null && StringUtils.hasText(component.value())) {
            return component.value();
        }
        Service service = clazz.getAnnotation(Service.class);
        if (service != null && StringUtils.hasText(service.value())) {
            return service.value();
        }
        Repository repository = clazz.getAnnotation(Repository.class);
        if (repository != null && StringUtils.hasText(repository.value())) {
            return repository.value();
        }
        RestController restController = clazz.getAnnotation(RestController.class);
        if (restController != null && StringUtils.hasText(restController.value())) {
            return restController.value();
        }
        return StringUtils.uncapitalize(clazz.getSimpleName());
    }

    /**
     * 是否为可注册的 Spring 风格 Bean（与 {@code @ComponentScan} 常见 stereotype 对齐）。
     */
    private static boolean isSpringComponent(Class<?> clazz) {
        return !clazz.isInterface()
                && !Modifier.isAbstract(clazz.getModifiers())
                && (clazz.isAnnotationPresent(org.springframework.stereotype.Component.class)
                || clazz.isAnnotationPresent(org.springframework.context.annotation.Configuration.class)
                || clazz.isAnnotationPresent(org.springframework.stereotype.Service.class)
                || clazz.isAnnotationPresent(org.springframework.stereotype.Repository.class)
                || clazz.isAnnotationPresent(org.springframework.web.bind.annotation.RestController.class));
    }

    private static boolean isConcreteAiAgent(Class<?> clazz) {
        return AiAgent.class.isAssignableFrom(clazz)
                && !clazz.isInterface()
                && !Modifier.isAbstract(clazz.getModifiers());
    }

    private static void closeQuietly(URLClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            classLoader.close();
        } catch (IOException ex) {
            log.debug("Failed to close plugin ClassLoader: {}", ex.getMessage());
        }
    }

    /**
     * 动态插件 Bean 登记信息。
     */
    static final class DynamicRegistration {
        private final String beanName;
        private final PluginAgentClassLoader classLoader;
        private final String jarFileName;
        private final boolean aiAgent;
        private volatile String agentId;

        DynamicRegistration(String beanName, PluginAgentClassLoader classLoader, String jarFileName, boolean aiAgent) {
            this.beanName = beanName;
            this.classLoader = classLoader;
            this.jarFileName = jarFileName;
            this.aiAgent = aiAgent;
        }

        boolean aiAgent() {
            return aiAgent;
        }

        String beanName() {
            return beanName;
        }

        PluginAgentClassLoader classLoader() {
            return classLoader;
        }

        String jarFileName() {
            return jarFileName;
        }

        String agentId() {
            return agentId;
        }

        void setAgentId(String agentId) {
            this.agentId = agentId;
        }
    }

    /**
     * 插件状态。
     */
    public record AgentPluginStatus(List<String> jarFiles, List<String> loadedAgentIds) {
    }

    /**
     * 插件重载结果。
     */
    public record AgentPluginReloadOutcome(
            boolean success,
            String message,
            List<String> jarFiles,
            List<String> loadedAgentIds) {

        static AgentPluginReloadOutcome success(List<String> jarFiles, List<String> loadedAgentIds) {
            return new AgentPluginReloadOutcome(true, null, jarFiles, loadedAgentIds);
        }

        static AgentPluginReloadOutcome failure(List<String> jarFiles,
                                                List<String> loadedAgentIds, String message) {
            return new AgentPluginReloadOutcome(false, message, jarFiles, loadedAgentIds);
        }
    }

    /**
     * 插件 agentId 与内置 Agent 冲突。
     */
    public static class AgentPluginConflictException extends Exception {
        public AgentPluginConflictException(String message) {
            super(message);
        }
    }
}
