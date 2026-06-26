package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.universal.UniversalAssistantConstants;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 平台内置通用助手：ReAct + 意图查询 / 调用子智能体工具。
 */
@Component
public class UniversalAssistantAgent extends AiAgent {

    private final UniversalIntentQueryTool intentQueryTool;
    private final UniversalSubAgentCallTool subAgentCallTool;

    public UniversalAssistantAgent(UniversalIntentQueryTool intentQueryTool,
                                   UniversalSubAgentCallTool subAgentCallTool) {
        this.intentQueryTool = intentQueryTool;
        this.subAgentCallTool = subAgentCallTool;
    }

    @Override
    public String getAgentId() {
        return UniversalAssistantConstants.AGENT_ID;
    }

    @Override
    public String getAgentName() {
        return UniversalAssistantConstants.DISPLAY_NAME;
    }

    @Override
    public String getAgentDescription() {
        return "平台通用助手，可识别意图并调用子智能体处理复杂问题。";
    }

    @Override
    public int getSort() {
        return 0;
    }

    @Override
    public boolean isQaTemplateEnabled() {
        return true;
    }

    @Override
    protected String getQaTemplateResourcePath() {
        return "prompts/universal-assistant-qa-template.json";
    }

    @Override
    protected Object[] buildTools() {
        return new Object[] { intentQueryTool, subAgentCallTool };
    }

    @Override
    public String loadSystemPrompt() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("prompts/universal-assistant-system.md")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // fallback below
        }
        return """
                你是 AI 通用助手。复杂或专业问题先调用 query_intent_agents，再根据结果调用 call_sub_agent 或向用户澄清。
                简单问题可直接回答。""";
    }
}
