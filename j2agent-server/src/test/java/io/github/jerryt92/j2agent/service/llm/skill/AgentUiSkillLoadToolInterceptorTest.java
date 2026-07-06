package io.github.jerryt92.j2agent.service.llm.skill;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallExecutionContext;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 技能加载拦截器单测：委派子智能体调用时跳过审计落库。
 */
class AgentUiSkillLoadToolInterceptorTest {

    @Test
    void subAgentCallRunSkipsSkillAuditPersist() throws JsonProcessingException {
        ChatMemory chatMemory = mock(ChatMemory.class);
        ChatMemoryMessageCodec codec = mock(ChatMemoryMessageCodec.class);
        when(codec.buildSkillLoadAuditMessage(anyString(), any(Boolean.class), any(Integer.class),
                any(Boolean.class), any())).thenReturn(new AssistantMessage("audit"));

        AgentUiSkillLoadToolInterceptor interceptor = new AgentUiSkillLoadToolInterceptor(chatMemory, codec);
        ToolCallRequest request = mock(ToolCallRequest.class);
        ToolCallHandler handler = mock(ToolCallHandler.class);
        RunnableConfig config = mock(RunnableConfig.class);
        ToolCallExecutionContext executionContext = mock(ToolCallExecutionContext.class);

        Map<String, Object> context = new HashMap<>();
        context.put(AgentRunnableContextKeys.CONTEXT_KEY_SUB_AGENT_CALL_RUN, Boolean.TRUE);
        context.put(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, "user:ctx:rc_wiki_assistant");

        when(request.getToolName()).thenReturn(ReadSkillTool.READ_SKILL);
        when(request.getToolCallId()).thenReturn("call-1");
        when(request.getArguments()).thenReturn("{\"skillName\":\"mysql8-sql\"}");
        when(request.getExecutionContext()).thenReturn(Optional.of(executionContext));
        when(executionContext.config()).thenReturn(config);
        when(config.context()).thenReturn(context);
        when(handler.call(request)).thenReturn(ToolCallResponse.of("call-1", ReadSkillTool.READ_SKILL, "ok"));

        interceptor.interceptToolCall(request, handler);

        verify(chatMemory, never()).add(anyString(), any(AssistantMessage.class));
        verify(chatMemory, never()).add(anyString(), any(List.class));
    }
}
