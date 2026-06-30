package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import io.github.jerryt92.j2agent.service.llm.universal.UniversalAssistantConstants;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 平台内置通用助手：编排 Hook 自动调度子智能体，主模型仅处理无子调用的通用对话。
 */
@Component
public class UniversalAssistantAgent extends AiAgent {

    private final UniversalAssistantOrchestratorHook universalAssistantOrchestratorHook;

    public UniversalAssistantAgent(UniversalAssistantOrchestratorHook universalAssistantOrchestratorHook) {
        this.universalAssistantOrchestratorHook = universalAssistantOrchestratorHook;
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
    protected Hook[] buildHooks() {
        return new Hook[] { universalAssistantOrchestratorHook };
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
                你是 AI 通用助手。专业任务由平台自动委派子智能体；你仅在无子调用时直接回答用户。""";
    }
}
