package io.github.jerryt92.j2agent.service.llm.agent.feature;

import java.util.Set;

/**
 * Agent 可选特性：从平台外部技能目录加载共享 Skill。
 *
 * <p>需要外部技能的 Agent 应显式 {@code implements ExternalSkills}（{@link io.github.jerryt92.j2agent.service.llm.agent.AiAgent} 本身不实现）。
 * Agent 内部 {@code resources/skills/} 下的技能始终默认全部加载并暴露。
 *
 * <p>{@link #useAllExternalSkills()} 默认为 {@code true}，此时加载 {@code <plugin.path>/skills/}
 *（{@code plugin.path} 为 {@code .../plugins} 根目录）下的全部技能，{@link #useExternalSkills()} 不生效。
 * 若返回 {@code false}，则仅加载 {@link #useExternalSkills()} 指定的目录名。
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
     * @return 外部技能目录名集合（对应 {@code <plugin.path>/skills/} 下的一级子目录名）
     */
    default Set<String> useExternalSkills() {
        return Set.of();
    }
}
