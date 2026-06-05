package io.github.jerryt92.j2agent.config;

/**
 * 插件目录布局约定。{@link PluginProperties#getPath()} 指向 {@code .../plugins} 根目录。
 */
public final class PluginLayout {

    /**
     * 插件根目录名，对应 {@code j2agent.plugin.path}（如 {@code /opt/ai-center/volume/plugins}）。
     */
    public static final String PLUGINS_DIR_NAME = "plugins";

    /**
     * Agent 插件子目录名，对应 {@code <plugin.path>/agents/<agentDir>/}。
     */
    public static final String AGENTS_DIR_NAME = "agents";

    /**
     * 平台共享 Skill 子目录名，对应 {@code <plugin.path>/skills/<skillDir>/}。
     */
    public static final String SKILLS_DIR_NAME = "skills";

    private PluginLayout() {
    }
}
