package io.github.jerryt92.j2agent.service.llm.skill;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.AbstractSkillRegistry;
import com.alibaba.cloud.ai.graph.skills.registry.filesystem.SkillScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;

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
import java.util.stream.Stream;

import static com.alibaba.cloud.ai.graph.skills.registry.filesystem.FileSystemSkillRegistry.DEFAULT_SYSTEM_PROMPT_TEMPLATE;

/**
 * Skill registry that resolves classpath resources with the owning Agent classloader.
 */
public class AgentClassLoaderSkillRegistry extends AbstractSkillRegistry {
    private static final Logger log = LoggerFactory.getLogger(AgentClassLoaderSkillRegistry.class);

    private final ClassLoader classLoader;
    private final String classpathPath;
    private final Path basePath;
    private final SkillScanner scanner = new SkillScanner();
    private final SystemPromptTemplate systemPromptTemplate;
    private final Map<String, String> jarSkillContentCache = new HashMap<>();

    private AgentClassLoaderSkillRegistry(Builder builder) {
        this.classLoader = builder.classLoader != null
                ? builder.classLoader
                : Thread.currentThread().getContextClassLoader();
        this.classpathPath = builder.classpathPath == null || builder.classpathPath.isBlank()
                ? "skills"
                : builder.classpathPath;
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

        try {
            Enumeration<URL> resources = classLoader.getResources(classpathPath);
            if (!resources.hasMoreElements()) {
                log.debug("No '{}' resource found in agent classloader {}", classpathPath, classLoader);
                this.skills = loadedSkills;
                return;
            }

            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                loadSkillsFromResource(resource, loadedSkills);
            }
        } catch (IOException e) {
            log.debug("Failed to load skills from agent classloader: {}", e.getMessage());
        }

        this.skills = loadedSkills;
        log.info("Loaded {} skills from agent classloader path '{}'", loadedSkills.size(), classpathPath);
    }

    private void loadSkillsFromResource(URL resource, Map<String, SkillMetadata> loadedSkills) {
        try {
            URI uri = resource.toURI();
            if ("file".equals(uri.getScheme())) {
                scanSkillDirectory(Path.of(uri), false, loadedSkills);
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
            scanSkillDirectory(jarFileSystem.getPath(pathInJar), true, loadedSkills);
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
        return "/" + classpathPath;
    }

    private void scanSkillDirectory(Path skillsPath, boolean jarPath, Map<String, SkillMetadata> loadedSkills) {
        if (!Files.isDirectory(skillsPath)) {
            log.debug("Skill classpath path is not a directory: {}", skillsPath);
            return;
        }

        try (Stream<Path> stream = Files.list(skillsPath)) {
            stream.filter(Files::isDirectory).forEach(skillDir -> loadSkill(skillDir, jarPath, loadedSkills));
        } catch (IOException e) {
            log.warn("Failed to scan skill classpath directory {}: {}", skillsPath, e.getMessage());
        }
    }

    private void loadSkill(Path skillDir, boolean jarPath, Map<String, SkillMetadata> loadedSkills) {
        try {
            SkillMetadata metadata = scanner.loadSkill(skillDir, "classpath");
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
            Path targetSkillPath = basePath.resolve(classpathPath).resolve(skillDirName);
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
        return "**Skill Location:**\n"
                + String.format("- **Agent Classpath Skills**: `classpath:%s`\n", classpathPath)
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
        private ClassLoader classLoader;
        private String classpathPath;
        private String basePath;
        private boolean autoLoad = true;
        private SystemPromptTemplate systemPromptTemplate;

        public Builder classLoader(ClassLoader classLoader) {
            this.classLoader = classLoader;
            return this;
        }

        public Builder classpathPath(String classpathPath) {
            this.classpathPath = classpathPath;
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
            return new AgentClassLoaderSkillRegistry(this);
        }
    }
}
