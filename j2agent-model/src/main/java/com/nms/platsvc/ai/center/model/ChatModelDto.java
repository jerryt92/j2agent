package io.github.jerryt92.j2agent.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

public class ChatModelDto {
    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChatRequest {
        @JsonProperty("contextId")
        String contextId;
        @JsonProperty("messages")
        List<Message> messages;
        @JsonProperty("format")
        String format;
        @JsonProperty("tools")
        List<FunctionCallingModel.Tool> tools;
        @JsonProperty("options")
        Map<String, Object> options;
        @JsonProperty("clientLocale")
        Locale clientLocale = Locale.ZH_CN;
        // 检索知识库
        @JsonProperty("retrievalKb")
        Boolean retrievalKb = true;
        @JsonProperty("customerSystemPrompt")
        String customerSystemPrompt;
        @JsonProperty("systemPrompt")
        SystemPrompt systemPrompt = SystemPrompt.GENERAL_ASSISTANT;
    }

    public enum Locale {
        @JsonProperty("zh_CN")
        ZH_CN,
        @JsonProperty("en_US")
        EN_US
    }

    /**
     * 系统提示词
     */
    public enum SystemPrompt {
        /**
         * 通用助手
         */
        @JsonProperty("general_assistant")
        GENERAL_ASSISTANT,
        /**
         * 工具使用
         */
        @JsonProperty("tool_use")
        TOOL_USE,
    }


    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ChatResponse {
        @JsonProperty("message")
        Message message;
        @JsonProperty("done_reason")
        String doneReason;
        @JsonProperty("done")
        Boolean done = false;
        @JsonProperty("error")
        Boolean error = false;
        @JsonProperty("error_code")
        String errorCode;
        @JsonProperty("error_message")
        String errorMessage;
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        @JsonProperty("role")
        Role role;
        @JsonProperty("content")
        String content = "";
        @JsonProperty("feedback")
        Feedback feedback = Feedback.NONE;
        @JsonProperty("tool_calls")
        List<ToolCall> toolCalls;
        @JsonProperty("tool_call_id")
        String toolCallId;
        // 引用文件
        @JsonProperty("rag_infos")
        List<RagInfoDto> ragInfos;
    }

    /**
     * 反馈
     */
    public enum Feedback {
        NONE(0),

        GOOD(1),

        BAD(2);

        private final Integer value;

        Feedback(Integer value) {
            this.value = value;
        }

        @JsonValue
        public Integer getValue() {
            return value;
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }

        @JsonCreator
        public static Feedback fromValue(Integer value) {
            for (Feedback b : Feedback.values()) {
                if (b.value.equals(value)) {
                    return b;
                }
            }
            throw new IllegalArgumentException("Unexpected value '" + value + "'");
        }
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Function {
        @JsonProperty("name")
        String name;
        @JsonProperty("description")
        String description;
        @JsonProperty("parameters")
        Map<String, Object> parameters;
    }

    /**
     * Create a tool of type 'function' and the given function definition.
     */
    public enum Type {
        /**
         * Function tool type.
         */
        @JsonProperty("function")
        FUNCTION
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCall {
        @JsonProperty("function")
        ToolCallFunction function;
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCallFunction {
        @JsonProperty("id")
        String id;
        @JsonProperty("index")
        Integer index;
        @JsonProperty("type")
        Type type = Type.FUNCTION;
        @JsonProperty("description")
        String description;
        @JsonProperty("name")
        String name;
        @JsonProperty("argument")
        Map<String, Object> argument;
        @JsonProperty("argumentsStream")
        StringBuilder argumentsStream;
    }

    public enum Role {
        /**
         * System message type used as instructions to the model.
         */
        @JsonProperty("system")
        SYSTEM,
        /**
         * User message type.
         */
        @JsonProperty("user")
        USER,
        /**
         * Assistant message type. Usually the response from the model.
         */
        @JsonProperty("assistant")
        ASSISTANT,
        /**
         * Tool message.
         */
        @JsonProperty("tool")
        TOOL
    }
}
