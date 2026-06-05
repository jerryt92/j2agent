package io.github.jerryt92.j2agent.service.llm.skill;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.AbstractSkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.SkillScanner;
import io.github.jerryt92.j2agent.config.PluginLayout;
import io.github.jerryt92.j2agent.config.PluginProperties;
import io.github.jerryt92.j2agent.service.llm.agent.feature.ExternalSkills;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry.DEFAULT_SYSTEM_PROMPT_TEMPLATE;

/**
 * Skill registry：扫描 Agent 内部 classpath {@code resources/skills/}，并按 Agent 实现的 feature 接口追加能力
 * （如 {@link ExternalSkills}）。新增 skill 相关 feature 时仅改本类 {@link Builder#applyAgentFeatures()}。
 */
public class AgentClassLoaderSkillRegistry extends AbstractSkillRegistry {
    private static final Logger log = LoggerFactory.getLogger(AgentClassLoaderSkillRegistry.class);
    private static final String DEFAULT_INTERNAL_SKILLS_CLASSPATH = "skills";
    private static final String EXTERNAL_SKILLS_ROOT_DIR_NAME = PluginLayout.SKILLS_DIR_NAME;

    private final ClassLoader classLoader;
    /**
     * Agent 内部 {@code resources/skills/} 的 classpath 路径段。
     */
    private final String internalSkillsClasspath;
    private final Path externalSkillsRoot;
    private final Set<String> externalSkillNames;
    private final boolean loadAllExternalSkills;
    private final Path basePath;
    private final SkillScanner scanner = new SkillScanner();
    private final SystemPromptTemplate systemPromptTemplate;
    private final Map<String, String> jarSkillContentCache = new HashMap<>();

    private AgentClassLoaderSkillRegistry(Builder builder) {
        this.classLoader = builder.classLoader != null
                ? builder.classLoader
                : Thread.currentThread().getContextClassLoader();
        this.internalSkillsClasspath = builder.internalSkillsClasspath == null || builder.internalSkillsClasspath.isBlank()
                ? DEFAULT_INTERNAL_SKILLS_CLASSPATH
                : builder.internalSkillsClasspath;
        this.externalSkillsRoot = builder.externalSkillsRoot;
        this.externalSkillNames = builder.externalSkillNames == null
                ? Set.of()
                : Set.copyOf(builder.externalSkillNames);
        this.loadAllExternalSkills = builder.loadAllExternalSkills;
        this.basePath = builder.basePath == null || builder.basePath.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(builder.basePath);
        this.systemPromptTemplate = builder.systemPromptTemplate != null
                ? builder.systemPromptTemplate
                : SystemPromptTemplate.builder().template(DEFAULT_SYSTEM_PROMPT_TEMPLATE).build();

        try {
            Files.createDirectories(basePath);
        } catch (IOException e) {
            log.warn("Failed to create skill basePath {}: {}", basePath, e.getMessage());
        }

        if (builder.autoLoad) {
            loadSkillsToRegistry();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    protected synchronized void loadSkillsToRegistry() {
        Map<String, SkillMetadata> loadedSkills = new HashMap<>();
        jarSkillContentCache.clear();

        loadInternalSkills(loadedSkills);
        loadExternalSkills(loadedSkills);

        this.skills = loadedSkills;
        log.info("Loaded {} skills from internal classpath '{}' and external root '{}' (loadAllExternal={})",
                loadedSkills.size(), internalSkillsClasspath, externalSkillsRoot, loadAllExternalSkills);
    }

    private void loadInternalSkills(Map<String, SkillMetadata> loadedSkills) {
        try {
            Enumeration<URL> resources = classLoader.getResources(internalSkillsClasspath);
            if (!resources.hasMoreElements()) {
                log.debug("No '{}' resource found in agent classloader {}", internalSkillsClasspath, classLoader);
                return;
            }

            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                loadSkillsFromResource(resource, loadedSkills);
            }
        } catch (IOException e) {
            log.debug("Failed to load internal skills from agent classloader: {}", e.getMessage());
        }
    }

    private void loadExternalSkills(Map<String, SkillMetadata> loadedSkills) {
        if (externalSkillsRoot == null) {
            log.debug("Platform external skills root is not configured");
            return;
        }
        if (!Files.isDirectory(externalSkillsRoot)) {
            log.warn("Platform external skills directory not found: {}", externalSkillsRoot);
            return;
        }
        if (loadAllExternalSkills) {
            scanSkillDirectory(externalSkillsRoot, false, loadedSkills, "filesystem");
            return;
        }
        if (externalSkillNames.isEmpty()) {
            log.debug("Skip platform external skills: loadAllExternal=false and no skill names configured");
            return;
        }
        for (String dirName : externalSkillNames) {
            if (dirName == null || dirName.isBlank()) {
                continue;
            }
            Path skillDir = externalSkillsRoot.resolve(dirName.trim());
            if (!Files.isDirectory(skillDir)) {
                log.warn("External skill directory not found: {}", skillDir);
                continue;
            }
            loadSkill(skillDir, false, loadedSkills, "filesystem");
        }
    }

    private void loadSkillsFromResource(URL resource, Map<String, SkillMetadata> loadedSkills) {
        try {
            URI uri = resource.toURI();
            if ("file".equals(uri.getScheme())) {
                scanSkillDirectory(Path.of(uri), false, loadedSkills, "classpath");
                return;
            }
            if ("jar".equals(uri.getScheme())) {
                loadSkillsFromJarResource(uri, loadedSkills);
                return;
            }
            log.debug("Unsupported skill classpath resource protocol: {}", uri.getScheme());
        } catch (URISyntaxException e) {
            log.debug("Invalid skill resource URI {}: {}", resource, e.getMessage());
        }
    }

    private void loadSkillsFromJarResource(URI uri, Map<String, SkillMetadata> loadedSkills) {
        FileSystem jarFileSystem = null;
        boolean closeAfterScan = false;
        try {
            try {
                jarFileSystem = FileSystems.newFileSystem(uri, Collections.emptyMap());
                closeAfterScan = true;
            } catch (FileSystemAlreadyExistsException e) {
                jarFileSystem = FileSystems.getFileSystem(uri);
            }

            String pathInJar = resolvePathInJar(uri);
            scanSkillDirectory(jarFileSystem.getPath(pathInJar), true, loadedSkills, "classpath");
        } catch (IOException e) {
            log.debug("Failed to open skill JAR filesystem: {}", e.getMessage());
        } finally {
            if (closeAfterScan && jarFileSystem != null) {
                try {
                    jarFileSystem.close();
                } catch (IOException e) {
                    log.debug("Failed to close skill JAR filesystem: {}", e.getMessage());
                }
            }
        }
    }

    private String resolvePathInJar(URI uri) {
        String jarPath = uri.getSchemeSpecificPart();
        int separatorIndex = jarPath.indexOf('!');
        if (separatorIndex != -1 && separatorIndex + 1 < jarPath.length()) {
            String path = jarPath.substring(separatorIndex + 1);
            return path.startsWith("/") ? path : "/" + path;
        }
        return "/" + internalSkillsClasspath;
    }

    private void scanSkillDirectory(Path skillsPath, boolean jarPath, Map<String, SkillMetadata> loadedSkills, String source) {
        if (!Files.isDirectory(skillsPath)) {
            log.debug("Skill classpath path is not a directory: {}", skillsPath);
            return;
        }

        try (Stream<Path> stream = Files.list(skillsPath)) {
            stream.filter(Files::isDirectory).forEach(skillDir -> loadSkill(skillDir, jarPath, loadedSkills, source));
        } catch (IOException e) {
            log.warn("Failed to scan skill classpath directory {}: {}", skillsPath, e.getMessage());
        }
    }

    private void loadSkill(Path skillDir, boolean jarPath, Map<String, SkillMetadata> loadedSkills, String source) {
        try {
            SkillMetadata metadata = scanner.loadSkill(skillDir, source);
            if (metadata == null) {
                return;
            }
            Path targetSkillPath = copySkillResources(skillDir, metadata.getName(), jarPath);
            if (targetSkillPath != null) {
                metadata.setSkillPath(targetSkillPath.toString());
            }
            if (jarPath && metadata.getFullContent() != null) {
                jarSkillContentCache.put(metadata.getName(), metadata.getFullContent());
            }
            loadedSkills.put(metadata.getName(), metadata);
        } catch (Exception e) {
            log.warn("Failed to load skill from {}: {}", skillDir, e.getMessage());
        }
    }

    private Path copySkillResources(Path skillDir, String skillName, boolean jarPath) {
        try {
            String skillDirName = skillName.contains("/")
                    ? skillName.substring(0, skillName.indexOf('/'))
                    : skillName;
            Path targetSkillPath = basePath.resolve(internalSkillsClasspath).resolve(skillDirName);
            Files.createDirectories(targetSkillPath);

            try (Stream<Path> entries = Files.list(skillDir)) {
                entries.forEach(entry -> copyEntry(entry, targetSkillPath.resolve(entry.getFileName().toString()), jarPath));
            }
            return targetSkillPath;
        } catch (Exception e) {
            log.warn("Failed to copy skill resources from {}: {}", skillDir, e.getMessage(), e);
            return null;
        }
    }

    private void copyEntry(Path source, Path target, boolean jarPath) {
        try {
            if (Files.isDirectory(source)) {
                Files.createDirectories(target);
                try (Stream<Path> entries = Files.walk(source)) {
                    entries.forEach(sourcePath -> {
                        Path relativePath = source.relativize(sourcePath);
                        copySinglePath(sourcePath, target.resolve(relativePath.toString()), jarPath);
                    });
                }
            } else {
                copySinglePath(source, target, jarPath);
            }
        } catch (IOException e) {
            log.warn("Failed to copy skill resource {}: {}", source, e.getMessage(), e);
        } catch (RuntimeException e) {
            log.warn("Failed to copy skill resource {}: {}", source, e.getMessage(), e);
        }
    }

    private void copySinglePath(Path source, Path target, boolean jarPath) {
        try {
            if (Files.isDirectory(source)) {
                Files.createDirectories(target);
                return;
            }
            Files.createDirectories(target.getParent());
            if (jarPath) {
                try (InputStream inputStream = Files.newInputStream(source)) {
                    Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (FileAlreadyExistsException ignored) {
            // Existing directories are fine during repeated reloads.
        } catch (IOException e) {
            log.warn("Failed to copy skill resource file {}: {}", source, e.getMessage(), e);
        }
    }

    @Override
    public synchronized void reload() {
        loadSkillsToRegistry();
    }

    /**
     * 读取技能目录下的附属 Markdown（相对 {@link SkillMetadata#getSkillPath()}）。
     * 仅允许 .md，禁止 {@code ..} 与绝对路径。
     */
    public String readSkillResourceContent(String skillName, String relativePath) throws IOException {
        if (skillName == null || skillName.isBlank()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Relative path cannot be null or empty");
        }
        String normalizedRelative = relativePath.trim().replace('\\', '/');
        if (normalizedRelative.startsWith("/") || normalizedRelative.contains("..")) {
            throw new IllegalArgumentException("Invalid relative path: " + relativePath);
        }
        if (!normalizedRelative.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new IllegalArgumentException("Only .md skill resources are supported");
        }
        Optional<SkillMetadata> skillOpt = get(skillName.trim());
        if (skillOpt.isEmpty()) {
            throw new IllegalStateException("Skill not found: " + skillName);
        }
        String skillPath = skillOpt.get().getSkillPath();
        if (skillPath == null || skillPath.isBlank()) {
            throw new IllegalStateException("Skill path not available for: " + skillName);
        }
        Path base = Path.of(skillPath).normalize().toAbsolutePath();
        Path resolved = base.resolve(normalizedRelative).normalize().toAbsolutePath();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Relative path escapes skill directory: " + relativePath);
        }
        if (!Files.isRegularFile(resolved)) {
            throw new IllegalStateException("Skill resource not found: " + relativePath);
        }
        return Files.readString(resolved, StandardCharsets.UTF_8);
    }

    @Override
    public String readSkillContent(String name) throws IOException {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Skill name cannot be null or empty");
        }
        Optional<SkillMetadata> skillOpt = get(name);
        if (skillOpt.isEmpty()) {
            throw new IllegalStateException("Skill not found: " + name);
        }
        String cachedContent = jarSkillContentCache.get(name);
        if (cachedContent != null) {
            return cachedContent;
        }
        return skillOpt.get().loadFullContent();
    }

    @Override
    public String getSkillLoadInstructions() {
        StringBuilder instructions = new StringBuilder("**Skill Location:**\n")
                .append(String.format("- **Agent Internal Skills**: `classpath:%s`\n", internalSkillsClasspath));
        if (externalSkillsRoot != null && (loadAllExternalSkills || !externalSkillNames.isEmpty()
                || Files.isDirectory(externalSkillsRoot))) {
            instructions.append(String.format("- **Platform External Skills**: `%s`\n", externalSkillsRoot));
        }
        return instructions
                + "\n"
                + "**Skill Path Format:**\n"
                + "Each skill has a unique id shown in the skill list above. "
                + "Use the exact id shown in the `skill_name` argument when calling `read_skill`. "
                + "Omit `relative_path` to load SKILL.md; set `relative_path` to a `.md` path under the skill directory "
                + "(e.g. 设备拓扑与资产/资源关系.md).\n"
                + "Skill ids are not tool names; do not call a skill id directly as a tool.\n";
    }

    @Override
    public String getRegistryType() {
        return "AgentClasspath";
    }

    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
        return systemPromptTemplate;
    }

    public static class Builder {
        private Object agent;
        private PluginProperties pluginProperties;
        private String pluginPathOverride;
        private ClassLoader classLoader;
        private String internalSkillsClasspath;
        private Path externalSkillsRoot;
        private Set<String> externalSkillNames;
        private boolean loadAllExternalSkills = false;
        private String basePath;
        private boolean autoLoad = true;
        private SystemPromptTemplate systemPromptTemplate;

        public Builder agent(Object agent) {
            this.agent = agent;
            return this;
        }

        public Builder pluginProperties(PluginProperties pluginProperties) {
            this.pluginProperties = pluginProperties;
            return this;
        }

        public Builder pluginPathOverride(String pluginPathOverride) {
            this.pluginPathOverride = pluginPathOverride;
            return this;
        }

        public Builder classLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        public Builder internalSkillsClasspath(String internalSkillsClasspath) {
            this.internalSkillsClasspath = internalSkillsClasspath;
            return this;
        }

        public Builder externalSkillsRoot(Path externalSkillsRoot) {
            this.externalSkillsRoot = externalSkillsRoot;
            return this;
        }

        public Builder externalSkillNames(Set<String> externalSkillNames) {
            this.externalSkillNames = externalSkillNames;
            return this;
        }

        public Builder loadAllExternalSkills(boolean loadAllExternalSkills) {
            this.loadAllExternalSkills = loadAllExternalSkills;
            return this;
        }

        public Builder basePath(String basePath) {
            this.basePath = basePath;
            return this;
        }

        public Builder autoLoad(boolean autoLoad) {
            this.autoLoad = autoLoad;
            return this;
        }

        public Builder systemPromptTemplate(SystemPromptTemplate systemPromptTemplate) {
            this.systemPromptTemplate = systemPromptTemplate;
            return this;
        }

        public AgentClassLoaderSkillRegistry build() {
            applyAgentFeatures();
            return new AgentClassLoaderSkillRegistry(this);
        }

        /**
         * 根据 Agent 实例已实现的 feature 接口补全 builder 配置；新增 feature 时只改此处。
         */
        private void applyAgentFeatures() {
            if (classLoader == null && agent != null) {
                classLoader = agent.getClass().getClassLoader();
            }
            externalSkillsRoot = resolveExternalSkillsRoot(pluginProperties, pluginPathOverride);
            if (agent instanceof ExternalSkills externalSkills && !externalSkills.useAllExternalSkills()) {
                externalSkillNames = externalSkills.useExternalSkills();
                loadAllExternalSkills = false;
                return;
            }
            loadAllExternalSkills = true;
            externalSkillNames = Set.of();
        }

        private static Path resolveExternalSkillsRoot(PluginProperties pluginProperties, String pluginPathOverride) {
            String pluginPath = resolvePluginPath(pluginProperties, pluginPathOverride);
            if (!StringUtils.hasText(pluginPath)) {
                return null;
            }
            Path root = Path.of(pluginPath.trim());
            Path direct = root.resolve(EXTERNAL_SKILLS_ROOT_DIR_NAME);
            if (Files.isDirectory(direct)) {
                return direct;
            }
            if (PluginLayout.AGENTS_DIR_NAME.equals(root.getFileName().toString())) {
                Path parent = root.getParent();
                if (parent != null) {
                    Path sibling = parent.resolve(EXTERNAL_SKILLS_ROOT_DIR_NAME);
                    if (Files.isDirectory(sibling)) {
                        return sibling;
                    }
                }
            }
            return direct;
        }

        private static String resolvePluginPath(PluginProperties pluginProperties, String pluginPathOverride) {
            if (pluginProperties != null && StringUtils.hasText(pluginProperties.getPath())) {
                return pluginProperties.getPath();
            }
            return pluginPathOverride;
        }
    }
}
