package io.github.jerryt92.j2agent.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.model.AskQuestionDto;
import io.github.jerryt92.j2agent.service.llm.agent.builtin.AgentToolContextSupport;
import io.github.jerryt92.j2agent.service.question.TurnAskQuestionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 平台级用户澄清工具：向前端发布问题卡片，并让模型自然停止等待用户回答。
 */
@Slf4j
@Component
public class AskQuestionTool implements ToolCallback {

    static final int MAX_OPTIONS = 6;
    static final int MAX_OPTION_LENGTH = 200;
    private static final String TOOL_NAME = "ask_question";
    private static final String TOOL_DESCRIPTION = "澄清提问工具。仅在本轮已经完成必要判断、确认缺少用户选择/补充信息且即将结束回复时调用；不要在仍可继续分析或执行时过早调用，也不能用普通文本反问替代工具调用。question 为简短问题；optionsJson 为 JSON 字符串数组，只包含真实业务候选答案，不要包含“自定义/其他/以上都不是”等兜底项，前端会固定提供自定义回答入口。调用成功后必须立即结束本轮对话，不要继续输出内容，等待用户回答。";
    private static final String SUCCESS_RESULT = "ask_question 已发送给用户。现在必须立即结束本轮对话，不要补充说明、不要继续猜测，等待用户选择候选答案或填写自定义回答后会发起新的对话。";
    private static final String INPUT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "question": {
                  "type": "string",
                  "description": "面向用户展示的简短问题"
                },
                "optionsJson": {
                  "type": "string",
                  "description": "候选回答选项 JSON 字符串数组，建议 2-4 个互斥且可执行的真实业务答案；不要生成“自定义”“其他”“以上都不是”“手动输入”等兜底选项"
                }
              },
              "required": ["question", "optionsJson"],
              "additionalProperties": false
            }""";
    private static final Set<String> RESERVED_FALLBACK_OPTIONS = Set.of(
            "自定义",
            "自定义回答",
            "其他",
            "其它",
            "以上都不是",
            "都不是",
            "让我填写",
            "手动输入",
            "其他选项",
            "自行填写",
            "other",
            "custom",
            "customanswer",
            "noneoftheabove");

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder().returnDirect(false).build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, new ToolContext(Map.of()));
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            JSONObject arguments = JSON.parseObject(toolInput);
            return askQuestion(
                    arguments == null ? null : arguments.getString("question"),
                    arguments == null ? null : arguments.getString("optionsJson"),
                    toolContext);
        } catch (Exception e) {
            log.error("ask_question callback failed", e);
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 解析参数、过滤兜底选项，并向当前轮次发布 pendingQuestion。
     */
    public String askQuestion(
            String question,
            String optionsJson,
            ToolContext toolContext) {
        try {
            String normalizedQuestion = StringUtils.trimToNull(question);
            if (normalizedQuestion == null) {
                return "Error: question 不能为空";
            }
            List<String> options = parseOptions(optionsJson);
            if (options.isEmpty()) {
                return "Error: optionsJson 必须是非空 JSON 字符串数组";
            }
            AskQuestionDto payload = new AskQuestionDto()
                    .type(AskQuestionDto.TypeEnum.ASK_QUESTION)
                    .version(1)
                    .question(normalizedQuestion)
                    .options(options);
            String conversationId = AgentToolContextSupport.parentConversationId(toolContext);
            if (StringUtils.isBlank(conversationId)) {
                log.warn("ask_question: conversationId missing, question not pushed to frontend");
                return "Error: conversationId missing";
            }
            TurnAskQuestionRegistry.publishQuestion(conversationId, payload);
            return SUCCESS_RESULT;
        } catch (Exception e) {
            log.error("ask_question failed", e);
            return "Error: " + e.getMessage();
        }
    }

    private static List<String> parseOptions(String optionsJson) {
        if (StringUtils.isBlank(optionsJson)) {
            return List.of();
        }
        JSONArray array = JSON.parseArray(optionsJson);
        Map<String, String> dedup = new LinkedHashMap<>();
        for (Object raw : array) {
            String option = raw == null ? null : StringUtils.trimToNull(String.valueOf(raw));
            if (option == null) {
                continue;
            }
            if (option.length() > MAX_OPTION_LENGTH) {
                option = option.substring(0, MAX_OPTION_LENGTH);
            }
            if (isReservedFallbackOption(option)) {
                continue;
            }
            dedup.putIfAbsent(option, option);
            if (dedup.size() >= MAX_OPTIONS) {
                break;
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private static boolean isReservedFallbackOption(String option) {
        String normalized = option.replaceAll("\\s+", "").toLowerCase();
        if (RESERVED_FALLBACK_OPTIONS.contains(normalized)) {
            return true;
        }
        return (normalized.contains("其他") || normalized.contains("其它"))
                && (normalized.contains("填写")
                || normalized.contains("输入")
                || normalized.contains("说明"));
    }
}
