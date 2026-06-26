package io.github.jerryt92.j2agent.service.llm.universal;

/**
 * 平台通用助手（AI 助手入口）常量。
 */
public final class UniversalAssistantConstants {

    public static final String AGENT_ID = "universal_assistant";

    public static final String DISPLAY_NAME = "AI助手";

    private UniversalAssistantConstants() {
    }

    public static boolean isUniversalAssistant(String agentId) {
        return AGENT_ID.equals(agentId);
    }
}
