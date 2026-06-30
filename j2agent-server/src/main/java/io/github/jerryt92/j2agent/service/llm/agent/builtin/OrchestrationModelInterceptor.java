package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;

import java.util.Map;

/**
 * 编排结果对主 ReAct 的影响：子智能体已交付时短路模型；无候选时可注入极简提示。
 */
public class OrchestrationModelInterceptor extends ModelInterceptor {

    private static final String SKIPPED_HINT = """

            ## 编排说明
            本回合无专业子智能体候选，请直接回答用户。""";

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        String turnId = resolveTurnId(request.getContext());
        UniversalOrchestrationRunHolder.Flags flags = UniversalOrchestrationRunHolder.lookup(turnId);
        if (flags != null && flags.delivered()) {
            return ModelResponse.of(new AssistantMessage(""));
        }
        if (flags != null && flags.skipped()) {
            SystemMessage enhanced = enhanceSystemMessage(request.getSystemMessage(), SKIPPED_HINT);
            ModelRequest modified = ModelRequest.builder(request)
                    .systemMessage(enhanced)
                    .build();
            return handler.call(modified);
        }
        return handler.call(request);
    }

    private static String resolveTurnId(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        Object value = context.get(AgentRunnableContextKeys.CONTEXT_KEY_TURN_ID);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static SystemMessage enhanceSystemMessage(SystemMessage existing, String section) {
        if (existing == null) {
            return new SystemMessage(section.trim());
        }
        return new SystemMessage(existing.getText() + section);
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }
}
