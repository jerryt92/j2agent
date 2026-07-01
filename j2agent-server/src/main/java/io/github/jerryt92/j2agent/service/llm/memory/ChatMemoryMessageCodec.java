package io.github.jerryt92.j2agent.service.llm.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jerryt92.j2agent.model.AgentState;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentUrlResolver;
import io.github.jerryt92.j2agent.service.llm.TurnStepItem;
import io.github.jerryt92.j2agent.service.llm.reasoning.SpringAiReasoningMetadataAdapter;
import io.github.jerryt92.j2agent.service.llm.tool.ToolEventEmitter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Spring AI {@link Message} 与库表行（content + meta_json + chat_role）互转，支持工具链消息持久化。
 */
@Slf4j
@Component
public class ChatMemoryMessageCodec {

    /**
     * 与 {@link io.github.jerryt92.j2agent.model.MessageDto.RoleEnum#TOOL} 对应的库内角色值。
     */
    public static final int CHAT_ROLE_TOOL = 3;

    /**
     * TEXT 列安全上限（约 65535 字节），用于落库前诊断日志。
     */
    public static final int TEXT_CHAR_SAFE_LIMIT = 65_535;

    /**
     * 扩列 LONGTEXT 后 content 的应用层兜底上限。
     */
    static final int MAX_PERSISTED_CONTENT_LENGTH = 1_048_576;

    static final int MAX_PERSISTED_META_JSON_LENGTH = 1_048_576;

    static final String TRUNCATED_SUFFIX = "...(truncated)";

    static final String JSON_KEY_VERSION = "v";
    static final String JSON_KEY_KIND = "kind";
    static final String KIND_ASSISTANT_TOOL = "assistant_tool";
    static final String KIND_TOOL_RESPONSE = "tool_response";
    /**
     * 技能加载审计行 content JSON 中的 kind 值。
     */
    public static final String KIND_SKILL_LOAD_AUDIT = "skill_load_audit";
    /**
     * 回合状态轨迹审计行 content JSON 中的 kind 值。
     */
    public static final String KIND_TURN_TRACE = "turn_trace";

    static final String META_DISPLAY_IN_CHAT = "displayInChat";
    static final String META_KIND = "kind";
    static final String META_KIND_ASSISTANT_TOOL = "assistant_tool_calls";
    static final String META_KIND_TOOL_RESULT = "tool_result";
    static final String META_KIND_SKILL_LOAD_AUDIT = "skill_load_audit";
    static final String META_KIND_TURN_TRACE = "turn_trace";
    static final String META_REASONING_CONTENT = "reasoningContent";
    static final String META_ATTACHMENTS = "attachments";
    public static final String META_RAG_INFOS = "ragInfos";

    private final ObjectMapper objectMapper;

    public ChatMemoryMessageCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 编码为可写入 chat_context_item 的字段。
     */
    public PersistedRow encode(Message message) throws JsonProcessingException {
        if (message instanceof UserMessage um) {
            String t = capContentField(um.getText() != null ? um.getText() : "", "user");
            Object attachments = um.getMetadata().get(META_ATTACHMENTS);
            List<ChatAttachmentDto> persistedAttachments = sanitizeAttachmentsForPersist(attachments);
            String meta = persistedAttachments == null ? null
                    : truncateMetaJson(objectMapper.writeValueAsString(Map.of(META_ATTACHMENTS, persistedAttachments)));
            return new PersistedRow(1, t, meta, null);
        }
        if (message instanceof AssistantMessage am) {
            if (am.hasToolCalls()) {
                ObjectNode root = objectMapper.createObjectNode();
                root.put(JSON_KEY_VERSION, 1);
                root.put(JSON_KEY_KIND, KIND_ASSISTANT_TOOL);
                root.put("text", capContentField(am.getText() != null ? am.getText() : "", "assistant_tool_text"));
                ArrayNode arr = root.putArray("toolCalls");
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    ObjectNode n = arr.addObject();
                    n.put("id", tc.id() != null ? tc.id() : "");
                    n.put("type", tc.type() != null ? tc.type() : "");
                    n.put("name", tc.name() != null ? tc.name() : "");
                    putTruncatedToolPayload(n, "arguments", tc.arguments(), "assistant_tool_arguments");
                }
                String meta = truncateMetaJson(objectMapper.writeValueAsString(Map.of(
                        META_DISPLAY_IN_CHAT, false,
                        META_KIND, META_KIND_ASSISTANT_TOOL)));
                return new PersistedRow(2, capContentField(objectMapper.writeValueAsString(root), "assistant_tool"), meta, null);
            }
            String t = capContentField(am.getText() != null ? am.getText() : "", "assistant");
            String ragInfos = extractRagInfosFromMetadata(am);
            if (isSkillLoadAuditContent(t, null)) {
                String meta = truncateMetaJson(objectMapper.writeValueAsString(Map.of(
                        META_DISPLAY_IN_CHAT, false,
                        META_KIND, META_KIND_SKILL_LOAD_AUDIT)));
                return new PersistedRow(2, t, meta, ragInfos);
            }
            if (isTurnTraceContent(t, null)) {
                String meta = truncateMetaJson(objectMapper.writeValueAsString(Map.of(
                        META_DISPLAY_IN_CHAT, false,
                        META_KIND, META_KIND_TURN_TRACE)));
                return new PersistedRow(2, t, meta, ragInfos);
            }
            String reasoning = extractReasoningFromMetadata(am);
            if (StringUtils.hasText(reasoning)) {
                String meta = truncateMetaJson(objectMapper.writeValueAsString(
                        Map.of(META_REASONING_CONTENT, capContentField(reasoning, "reasoningContent"))));
                return new PersistedRow(2, t, meta, ragInfos);
            }
            return new PersistedRow(2, t, null, ragInfos);
        }
        if (message instanceof ToolResponseMessage tr) {
            ObjectNode root = objectMapper.createObjectNode();
            root.put(JSON_KEY_VERSION, 1);
            root.put(JSON_KEY_KIND, KIND_TOOL_RESPONSE);
            ArrayNode arr = root.putArray("responses");
            for (ToolResponseMessage.ToolResponse r : tr.getResponses()) {
                ObjectNode n = arr.addObject();
                n.put("id", r.id() != null ? r.id() : "");
                n.put("name", r.name() != null ? r.name() : "");
                putTruncatedToolPayload(n, "responseData", r.responseData(), "tool_response");
            }
            String meta = truncateMetaJson(objectMapper.writeValueAsString(Map.of(
                    META_DISPLAY_IN_CHAT, false,
                    META_KIND, META_KIND_TOOL_RESULT)));
            return new PersistedRow(CHAT_ROLE_TOOL, capContentField(objectMapper.writeValueAsString(root), "tool_response"), meta, null);
        }
        return null;
    }

    private static String extractRagInfosFromMetadata(AssistantMessage am) {
        if (am == null || am.getMetadata() == null) {
            return null;
        }
        Object raw = am.getMetadata().get(META_RAG_INFOS);
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        return value.isEmpty() ? null : value;
    }

    /**
     * 从库表行还原为 Spring AI 消息。
     */
    public Message decode(int chatRole, String content, String metaJson) {
        return decode(chatRole, content, metaJson, null);
    }

    /**
     * 从库表行还原为 Spring AI 消息（含 {@code rag_infos} 列）。
     */
    public Message decode(int chatRole, String content, String metaJson, String ragInfos) {
        String c = content != null ? content : "";
        if (chatRole == 1) {
            List<ChatAttachmentDto> attachments = parseAttachments(metaJson);
            UserMessage.Builder builder = UserMessage.builder().text(c);
            if (!attachments.isEmpty()) {
                builder.metadata(Map.of(META_ATTACHMENTS, attachments));
            }
            return builder.build();
        }
        if (chatRole == CHAT_ROLE_TOOL) {
            return parseToolResponseMessage(c);
        }
        if (chatRole == 2) {
            if (isSkillLoadAuditContent(c, metaJson)) {
                return null;
            }
            if (isTurnTraceContent(c, metaJson)) {
                return AssistantMessage.builder().content(c).build();
            }
            if (isAssistantToolJson(c, metaJson)) {
                return parseAssistantToolMessage(c);
            }
            return buildDecodedAssistantMessage(c, metaJson, ragInfos);
        }
        return null;
    }

    private AssistantMessage buildDecodedAssistantMessage(String content, String metaJson, String ragInfos) {
        Map<String, Object> props = new LinkedHashMap<>();
        String reasoning = parseReasoningFromMetaJson(metaJson);
        if (StringUtils.hasText(reasoning)) {
            props.put(META_REASONING_CONTENT, reasoning);
        }
        if (StringUtils.hasText(ragInfos)) {
            props.put(META_RAG_INFOS, ragInfos);
        }
        if (!props.isEmpty()) {
            return AssistantMessage.builder().content(content).properties(props).build();
        }
        if (!StringUtils.hasText(content)) {
            return null;
        }
        return new AssistantMessage(content);
    }

    public List<ChatAttachmentDto> parseAttachments(String metaJson) {
        if (!StringUtils.hasText(metaJson)) {
            return List.of();
        }
        try {
            JsonNode attachments = objectMapper.readTree(metaJson).get(META_ATTACHMENTS);
            if (attachments == null || !attachments.isArray()) {
                return List.of();
            }
            return normalizeAttachmentUrls(
                    objectMapper.readerForListOf(ChatAttachmentDto.class).readValue(attachments));
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    public static List<ChatAttachmentDto> attachmentsFromUserMessage(UserMessage userMessage) {
        if (userMessage == null || userMessage.getMetadata() == null) {
            return List.of();
        }
        return normalizeAttachments(userMessage.getMetadata().get(META_ATTACHMENTS));
    }

    public static List<ChatAttachmentDto> normalizeAttachments(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return List.of();
        }
        List<ChatAttachmentDto> attachments = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof ChatAttachmentDto dto) {
                attachments.add(dto);
            }
        }
        return normalizeAttachmentUrls(attachments);
    }

    public static List<ChatAttachmentDto> normalizeAttachmentUrls(List<ChatAttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<ChatAttachmentDto> normalized = new ArrayList<>(attachments.size());
        for (ChatAttachmentDto attachment : attachments) {
            if (attachment == null || !StringUtils.hasText(attachment.getObjectKey())) {
                continue;
            }
            normalized.add(ChatAttachmentUrlResolver.copyMetadata(attachment));
        }
        return List.copyOf(normalized);
    }

    private static List<ChatAttachmentDto> sanitizeAttachmentsForPersist(Object attachments) {
        if (!(attachments instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<ChatAttachmentDto> sanitized = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof ChatAttachmentDto dto && StringUtils.hasText(dto.getObjectKey())) {
                sanitized.add(ChatAttachmentUrlResolver.copyMetadata(dto));
            }
        }
        return sanitized.isEmpty() ? null : sanitized;
    }

    /**
     * 构建写入 chat_context_item 的技能加载审计消息（不参与 LLM 上下文回放，见 {@link #decode}）。
     */
    public AssistantMessage buildSkillLoadAuditMessage(String skillName,
                                                       boolean success,
                                                       int contentLength,
                                                       boolean truncated,
                                                       String errorMessage) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put(JSON_KEY_VERSION, 1);
        root.put(JSON_KEY_KIND, KIND_SKILL_LOAD_AUDIT);
        root.put("skillName", skillName != null ? skillName : "");
        root.put("loadedAt", System.currentTimeMillis());
        root.put("success", success);
        root.put("contentLength", contentLength);
        root.put("truncated", truncated);
        if (errorMessage != null) {
            root.put("errorMessage", errorMessage);
        }
        return AssistantMessage.builder().content(objectMapper.writeValueAsString(root)).build();
    }

    /**
     * 构建写入 chat_context_item 的回合状态轨迹审计消息（不参与 LLM 上下文回放，见 {@link #decode}）。
     */
    public AssistantMessage buildTurnTraceAuditMessage(String turnId,
                                                       List<TurnStepItem> steps) throws JsonProcessingException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put(JSON_KEY_VERSION, 1);
        root.put(JSON_KEY_KIND, KIND_TURN_TRACE);
        root.put("turnId", turnId != null ? turnId : "");
        ArrayNode arr = root.putArray("steps");
        for (TurnStepItem step : steps) {
            ObjectNode n = arr.addObject();
            n.put("state", step.state() != null ? step.state().name() : "");
            if (step.toolName() != null) {
                n.put("toolName", step.toolName());
            }
            if (step.ts() != null) {
                n.put("ts", step.ts());
            }
        }
        return AssistantMessage.builder().content(objectMapper.writeValueAsString(root)).build();
    }

    /**
     * 判断是否为回合状态轨迹审计持久化行。
     */
    public boolean isTurnTraceContent(String content, String metaJson) {
        if (StringUtils.hasText(metaJson)) {
            try {
                JsonNode m = objectMapper.readTree(metaJson);
                if (m.has(META_KIND) && META_KIND_TURN_TRACE.equals(m.get(META_KIND).asText())) {
                    return true;
                }
            } catch (JsonProcessingException ignored) {
            }
        }
        if (!StringUtils.hasText(content) || !content.trim().startsWith("{")) {
            return false;
        }
        try {
            JsonNode n = objectMapper.readTree(content);
            return KIND_TURN_TRACE.equals(n.path(JSON_KEY_KIND).asText());
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * 从 turn_trace 持久化 JSON 解析步骤列表。
     */
    public List<TurnStepItem> parseTurnTraceSteps(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            JsonNode arr = root.get("steps");
            if (arr == null || !arr.isArray()) {
                return List.of();
            }
            List<TurnStepItem> steps = new ArrayList<>();
            for (JsonNode n : arr) {
                String stateStr = n.path("state").asText("");
                AgentState state;
                try {
                    state = AgentState.valueOf(stateStr);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String toolName = n.hasNonNull("toolName") ? n.get("toolName").asText() : null;
                Long ts = n.has("ts") ? n.get("ts").asLong() : null;
                steps.add(new TurnStepItem(state, toolName, ts));
            }
            return steps;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * 判断是否为技能加载审计持久化行（content 或 meta_json 可识别）。
     */
    public boolean isSkillLoadAuditContent(String content, String metaJson) {
        if (StringUtils.hasText(metaJson)) {
            try {
                JsonNode m = objectMapper.readTree(metaJson);
                if (m.has(META_KIND) && META_KIND_SKILL_LOAD_AUDIT.equals(m.get(META_KIND).asText())) {
                    return true;
                }
            } catch (JsonProcessingException ignored) {
            }
        }
        if (!StringUtils.hasText(content) || !content.trim().startsWith("{")) {
            return false;
        }
        try {
            JsonNode n = objectMapper.readTree(content);
            return KIND_SKILL_LOAD_AUDIT.equals(n.path(JSON_KEY_KIND).asText());
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private boolean isAssistantToolJson(String content, String metaJson) {
        if (!StringUtils.hasText(content) || !content.trim().startsWith("{")) {
            return false;
        }
        if (StringUtils.hasText(metaJson)) {
            try {
                JsonNode m = objectMapper.readTree(metaJson);
                if (m.has(META_KIND) && META_KIND_ASSISTANT_TOOL.equals(m.get(META_KIND).asText())) {
                    return true;
                }
            } catch (JsonProcessingException ignored) {
            }
        }
        try {
            JsonNode n = objectMapper.readTree(content);
            return KIND_ASSISTANT_TOOL.equals(n.path(JSON_KEY_KIND).asText());
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    private AssistantMessage parseAssistantToolMessage(String json) {
        try {
            JsonNode n = objectMapper.readTree(json);
            String text = n.path("text").asText("");
            List<AssistantMessage.ToolCall> calls = new ArrayList<>();
            JsonNode arr = n.get("toolCalls");
            if (arr != null && arr.isArray()) {
                for (JsonNode t : arr) {
                    calls.add(new AssistantMessage.ToolCall(
                            t.path("id").asText(""),
                            t.path("type").asText(""),
                            t.path("name").asText(""),
                            t.path("arguments").asText("")));
                }
            }
            return AssistantMessage.builder().content(text).toolCalls(calls).build();
        } catch (JsonProcessingException e) {
            return new AssistantMessage(json);
        }
    }

    private ToolResponseMessage parseToolResponseMessage(String json) {
        try {
            JsonNode n = objectMapper.readTree(json);
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            JsonNode arr = n.get("responses");
            if (arr != null && arr.isArray()) {
                for (JsonNode t : arr) {
                    responses.add(new ToolResponseMessage.ToolResponse(
                            t.path("id").asText(""),
                            t.path("name").asText(""),
                            t.path("responseData").asText("")));
                }
            }
            return ToolResponseMessage.builder().responses(responses).build();
        } catch (JsonProcessingException e) {
            return ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("", "error", json)))
                    .build();
        }
    }

    private static String extractReasoningFromMetadata(AssistantMessage am) {
        return SpringAiReasoningMetadataAdapter.adaptFullReasoning(am, null);
    }

    private String parseReasoningFromMetaJson(String metaJson) {
        if (!StringUtils.hasText(metaJson)) {
            return null;
        }
        try {
            JsonNode m = objectMapper.readTree(metaJson);
            JsonNode rc = m.get(META_REASONING_CONTENT);
            if (rc == null || rc.isNull()) {
                return null;
            }
            return rc.asText();
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private void putTruncatedToolPayload(ObjectNode node, String fieldName, String rawValue, String kind) {
        String value = rawValue != null ? rawValue : "";
        int maxLen = ToolEventEmitter.MAX_TOOL_RESULT_LENGTH;
        if (value.length() <= maxLen) {
            node.put(fieldName, value);
            return;
        }
        log.warn("Chat memory {} truncated: {} -> {}", kind, value.length(), maxLen);
        node.put(fieldName, value.substring(0, maxLen));
        node.put(fieldName + "Truncated", true);
        node.put(fieldName + "Length", value.length());
    }

    private String capContentField(String content, String kind) {
        if (content == null) {
            return "";
        }
        if (content.length() <= MAX_PERSISTED_CONTENT_LENGTH) {
            return content;
        }
        log.warn("Chat memory {} content capped: {} -> {}", kind, content.length(), MAX_PERSISTED_CONTENT_LENGTH);
        return content.substring(0, MAX_PERSISTED_CONTENT_LENGTH) + TRUNCATED_SUFFIX;
    }

    private String truncateMetaJson(String metaJson) {
        if (!StringUtils.hasText(metaJson) || metaJson.length() <= MAX_PERSISTED_META_JSON_LENGTH) {
            return metaJson;
        }
        log.warn("Chat memory meta_json truncated: {} -> {}", metaJson.length(), MAX_PERSISTED_META_JSON_LENGTH);
        return metaJson.substring(0, MAX_PERSISTED_META_JSON_LENGTH);
    }

    /**
     * 单行持久化载荷。
     */
    public record PersistedRow(int chatRole, String content, String metaJson, String ragInfos) {
    }
}
