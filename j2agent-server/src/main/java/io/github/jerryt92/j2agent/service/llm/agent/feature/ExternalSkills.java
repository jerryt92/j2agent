package io.github.jerryt92.j2agent.service.llm.agent.feature;

import java.util.Set;

/**
 * Agent 可选特性：控制平台外部 Skill 的加载范围。
 *
 * <p>Agent 内部 {@code resources/skills/} 下的技能始终默认全部加载并暴露。
 * 平台外部 Skill 从 {@code <plugin.path>/skills/} 加载（{@code plugin.path} 为 {@code .../plugins} 根目录），并与内部 skills 合并；
 * 实现本接口仅用于把外部加载范围收窄为指定目录。
 *
 * <p>外部目录示例：{@code /opt/ai-center/volume/plugins/skills}（见
 * {@link io.github.jerryt92.j2agent.config.PluginLayout#SKILLS_DIR_NAME}）。
 */
public interface ExternalSkills {

    /**
     * @return 是否加载平台外部技能目录下的全部技能；默认 {@code true}
     */
    default boolean useAllExternalSkills() {
        return true;
    }

    /**
     * 仅当 {@link #useAllExternalSkills()} 为 {@code false} 时生效。
     *
     * @return 外部技能目录名集合（对应平台 {@code plugins/skills/} 下的一级子目录名）
     */
    default Set<String> useExternalSkills() {
        return Set.of();
    }
}
