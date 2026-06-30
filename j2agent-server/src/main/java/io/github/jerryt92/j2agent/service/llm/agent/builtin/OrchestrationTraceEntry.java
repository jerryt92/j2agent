package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import org.apache.commons.lang3.StringUtils;

/**
 * 单轮子智能体调用记录，供调度 LLM 与路由查询构造使用。
 */
public record OrchestrationTraceEntry(String agentId, String query, String result) {

    public String toTraceBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("- agentId: ").append(StringUtils.defaultString(agentId)).append('\n');
        sb.append("  query: ").append(StringUtils.defaultString(query)).append('\n');
        sb.append("  result: ").append(truncate(StringUtils.defaultString(result)));
        return sb.toString();
    }

    private static String truncate(String text) {
        if (text.length() <= 2000) {
            return text;
        }
        return text.substring(0, 2000) + "...(truncated)";
    }
}
