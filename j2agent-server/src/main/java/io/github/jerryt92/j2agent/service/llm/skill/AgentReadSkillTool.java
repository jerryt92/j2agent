package io.github.jerryt92.j2agent.service.llm.skill;

import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.function.BiFunction;

/**
 * 统一的 {@link ReadSkillTool#READ_SKILL} 实现：默认读 SKILL.md；可选 {@code relative_path} 读技能目录内其它 .md。
 */
public final class AgentReadSkillTool implements BiFunction<AgentReadSkillTool.ReadSkillRequest, ToolContext, String> {

    static final String DESCRIPTION = """
            Reads skill markdown from the SkillRegistry.
            - Omit relative_path (or leave blank) to load the skill root SKILL.md (without YAML frontmatter).
            - Set relative_path to load a supporting .md under the skill directory (e.g. 设备拓扑与资产/资源关系.md).
            skill_name must match the registered skill id. Only .md paths are allowed; no .. segments.
            """;

    private final SkillRegistry skillRegistry;

    private AgentReadSkillTool(SkillRegistry skillRegistry) {
        if (skillRegistry == null) {
            throw new IllegalArgumentException("SkillRegistry cannot be null");
        }
        this.skillRegistry = skillRegistry;
    }

    public static org.springframework.ai.tool.ToolCallback createToolCallback(SkillRegistry skillRegistry) {
        return FunctionToolCallback.builder(ReadSkillTool.READ_SKILL, new AgentReadSkillTool(skillRegistry))
                .description(DESCRIPTION)
                .inputType(ReadSkillRequest.class)
                .build();
    }

    @Override
    public String apply(ReadSkillRequest request, ToolContext toolContext) {
        if (request == null) {
            return "Error: skill_name is required";
        }
        String skillName = StringUtils.isNotBlank(request.skillName)
                ? request.skillName.trim()
                : (request.skill_name != null ? request.skill_name.trim() : "");
        if (skillName.isEmpty()) {
            return "Error: skill_name is required";
        }
        String relativePath = StringUtils.isNotBlank(request.relativePath)
                ? request.relativePath
                : request.relative_path;
        try {
            if (!(skillRegistry instanceof AgentClassLoaderSkillRegistry registry)) {
                if (StringUtils.isNotBlank(relativePath)) {
                    return "Error: relative_path requires AgentClassLoaderSkillRegistry";
                }
                return skillRegistry.readSkillContent(skillName);
            }
            if (isRootSkillPath(relativePath)) {
                return registry.readSkillContent(skillName);
            }
            return registry.readSkillResourceContent(skillName, relativePath.trim());
        } catch (Exception e) {
            return "Error reading skill: " + e.getMessage();
        }
    }

    private static boolean isRootSkillPath(String relativePath) {
        if (StringUtils.isBlank(relativePath)) {
            return true;
        }
        return "SKILL.md".equalsIgnoreCase(relativePath.trim());
    }

    /**
     * 与 Spring AI FunctionToolCallback 反序列化兼容（支持 skillName / skill_name、relativePath / relative_path）。
     */
    public static class ReadSkillRequest {
        public String skillName;
        public String skill_name;
        public String relativePath;
        public String relative_path;

        public ReadSkillRequest() {
        }

        public ReadSkillRequest(String skillName) {
            this.skillName = skillName;
        }
    }
}
