package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import org.apache.commons.lang3.StringUtils;

/**
 * 子智能体调用工具名常量（Hook 模拟工具事件与历史轨迹兼容）。
 */
public final class SubAgentCallNames {

    public static final String TOOL_NAME = "call_sub_agent";

    /** 历史轨迹兼容 */
    public static final String LEGACY_TOOL_NAME_DELEGATE = "delegate_to_agent";

    public static final String LEGACY_TOOL_NAME_CALL_AGENT = "call_agent";

    private SubAgentCallNames() {
    }

    public static boolean isSubAgentCallToolName(String toolName) {
        if (StringUtils.isBlank(toolName)) {
            return false;
        }
        String trimmed = toolName.trim();
        return TOOL_NAME.equals(trimmed)
                || LEGACY_TOOL_NAME_DELEGATE.equals(trimmed)
                || LEGACY_TOOL_NAME_CALL_AGENT.equals(trimmed);
    }
}
