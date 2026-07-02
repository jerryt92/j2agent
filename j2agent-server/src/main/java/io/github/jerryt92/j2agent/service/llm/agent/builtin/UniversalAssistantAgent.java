package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.universal.UniversalAssistantConstants;
import org.springframework.stereotype.Component;

/**
 * 平台内置通用助手：子智能体调度由 {@link UniversalAssistantOrchestratorService} 在 {@link io.github.jerryt92.j2agent.service.llm.ChatService} 前置处理；
 * 无委派时本 Agent 以标准 ReAct + {@link #loadSystemPrompt()} 直接回答用户。
 */
@Component
public class UniversalAssistantAgent extends AiAgent {

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
        return "平台通用助手，可自动委派子智能体处理专业问题。";
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
        return new Object[0];
    }

    @Override
    public String loadSystemPrompt() {
        return """
                你是 J2Agent AI 平台的 AI 通用助手。专业任务由平台自动委派子智能体；你仅在无子调用时直接回答用户。""";
    }
}
