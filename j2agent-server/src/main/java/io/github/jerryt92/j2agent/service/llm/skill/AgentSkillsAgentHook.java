package io.github.jerryt92.j2agent.service.llm.skill;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.AgentHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.skills.SkillsInterceptor;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 与官方 {@link com.alibaba.cloud.ai.graph.agent.hook.skills.SkillsAgentHook} 等价，
 * 但使用 {@link AgentReadSkillTool} 作为唯一 {@code read_skill} 实现（支持可选 relative_path）。
 */
public class AgentSkillsAgentHook extends AgentHook {

    private static final Logger log = LoggerFactory.getLogger(AgentSkillsAgentHook.class);

    private final SkillRegistry skillRegistry;
    private final boolean autoReload;
    private final Map<String, List<ToolCallback>> groupedTools;
    private final ToolCallback readSkillTool;

    private AgentSkillsAgentHook(Builder builder) {
        if (builder.skillRegistry == null) {
            throw new IllegalArgumentException("skillRegistry is required");
        }
        this.skillRegistry = builder.skillRegistry;
        this.autoReload = builder.autoReload;
        this.groupedTools = builder.groupedTools == null ? Map.of() : builder.groupedTools;
        this.readSkillTool = AgentReadSkillTool.createToolCallback(skillRegistry);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public CompletableFuture<Map<String, Object>> beforeAgent(OverAllState state, RunnableConfig config) {
        if (autoReload) {
            try {
                skillRegistry.reload();
            } catch (UnsupportedOperationException e) {
                log.debug("Reload not supported for registry type: {}", skillRegistry.getClass().getName());
            }
        }
        return CompletableFuture.completedFuture(Map.of());
    }

    public SkillRegistry getSkillRegistry() {
        return skillRegistry;
    }

    @Override
    public List<ModelInterceptor> getModelInterceptors() {
        SkillsInterceptor.Builder interceptorBuilder = SkillsInterceptor.builder().skillRegistry(skillRegistry);
        if (!groupedTools.isEmpty()) {
            interceptorBuilder.groupedTools(groupedTools);
        }
        return List.of(interceptorBuilder.build());
    }

    @Override
    public List<ToolCallback> getTools() {
        return List.of(readSkillTool);
    }

    public int getSkillCount() {
        return skillRegistry.size();
    }

    public boolean hasSkill(String name) {
        return skillRegistry.contains(name);
    }

    public List<SkillMetadata> listSkills() {
        return skillRegistry.listAll();
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    public static class Builder {
        private SkillRegistry skillRegistry;
        private boolean autoReload;
        private Map<String, List<ToolCallback>> groupedTools;

        public Builder skillRegistry(SkillRegistry skillRegistry) {
            this.skillRegistry = skillRegistry;
            return this;
        }

        public Builder autoReload(boolean autoReload) {
            this.autoReload = autoReload;
            return this;
        }

        public Builder groupedTools(Map<String, List<ToolCallback>> groupedTools) {
            this.groupedTools = groupedTools;
            return this;
        }

        public AgentSkillsAgentHook build() {
            return new AgentSkillsAgentHook(this);
        }
    }
}
