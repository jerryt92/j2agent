package io.github.jerryt92.j2agent.service.llm.agent.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单插件卸载时按 jar 标签匹配安装目录。
 */
class AgentPluginRegistryMatchesAgentDirTest {

    @Test
    void matchesAgentsPrefixedLabel() {
        assertTrue(AgentPluginRegistry.matchesAgentDir(
                "agents/demo-agent/demo.jar", "demo-agent"));
    }

    @Test
    void matchesPlainDirPrefixedLabel() {
        assertTrue(AgentPluginRegistry.matchesAgentDir(
                "demo-agent/demo.jar", "demo-agent"));
    }

    @Test
    void doesNotMatchOtherDirectory() {
        assertFalse(AgentPluginRegistry.matchesAgentDir(
                "agents/other-agent/demo.jar", "demo-agent"));
        assertFalse(AgentPluginRegistry.matchesAgentDir(
                "agents/demo-agent-extra/demo.jar", "demo-agent"));
    }

    @Test
    void doesNotMatchBlank() {
        assertFalse(AgentPluginRegistry.matchesAgentDir(null, "demo-agent"));
        assertFalse(AgentPluginRegistry.matchesAgentDir("agents/demo-agent/demo.jar", null));
        assertFalse(AgentPluginRegistry.matchesAgentDir("", "demo-agent"));
    }
}
