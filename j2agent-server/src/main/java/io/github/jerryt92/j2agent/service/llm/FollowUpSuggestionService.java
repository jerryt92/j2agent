package io.github.jerryt92.j2agent.service.llm;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import io.github.jerryt92.j2agent.config.chat.FollowUpSuggestionProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于轻量同步对话，在单轮助手回复结束后生成若干「建议追问」文案。
 */
@Slf4j
@Component
public class FollowUpSuggestionService {

    private static final int USER_SNIPPET_MAX = 2000;
    private static final int ASSISTANT_SNIPPET_MAX = 8000;

    private static final String SYSTEM_PROMPT = """
            你是对话助手的产品侧文案。根据「用户上一句」与「助手刚完成的回答」摘要，生成用户可能接着问的 3～5 个简短追问。
            要求：
            1. 只输出一个 JSON 数组，元素为字符串，不要 Markdown、不要解释、不要代码块。
            2. 每个问题一句，口语化，尽量具体；总条数 3～5。
            3. 使用与用户问题相同的语言（中文则全中文）。""";

    private final ChatModel chatModel;
    private final FollowUpSuggestionProperties properties;

    public FollowUpSuggestionService(ChatModel chatModel, FollowUpSuggestionProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    /**
     * 生成建议追问列表；关闭功能、入参为空、超时或解析失败时返回空列表。
     */
    public List<String> suggest(String userMessage, String assistantReply) {
        if (!properties.isEnabled()) {
            return List.of();
        }
        if (StringUtils.isBlank(assistantReply)) {
            return List.of();
        }
        String user = StringUtils.left(StringUtils.defaultString(userMessage), USER_SNIPPET_MAX);
        String assistant = StringUtils.left(assistantReply, ASSISTANT_SNIPPET_MAX);
        try {
            return CompletableFuture.supplyAsync(() -> invokeModel(user, assistant), Executors.newVirtualThreadPerTaskExecutor())
                    .get(Math.max(1, properties.getTimeoutSeconds()), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("建议追问生成超时（{}s），已跳过", properties.getTimeoutSeconds());
            return List.of();
        } catch (Throwable e) {
            log.warn("建议追问生成失败: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 调用 Spring AI {@link ChatModel} 并解析为字符串列表。
     */
    private List<String> invokeModel(String user, String assistant) {
        String userBlock = """
                【用户上一句】
                %s

                【助手回答】
                %s
                """.formatted(user, assistant);
        Prompt prompt = new Prompt(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(userBlock));
        ChatResponse response = chatModel.call(prompt);
        String text = response.getResult() != null && response.getResult().getOutput() != null
                ? response.getResult().getOutput().getText()
                : null;
        if (StringUtils.isBlank(text)) {
            return List.of();
        }
        return parseAndNormalize(text);
    }

    /**
     * 从模型输出中解析 JSON 数组并做条数、长度裁剪。
     */
    private List<String> parseAndNormalize(String raw) {
        String cleaned = stripMarkdownFence(raw.trim());
        JSONArray array;
        try {
            array = JSON.parseArray(cleaned);
        } catch (Exception first) {
            int l = cleaned.indexOf('[');
            int r = cleaned.lastIndexOf(']');
            if (l >= 0 && r > l) {
                try {
                    array = JSON.parseArray(cleaned.substring(l, r + 1));
                } catch (Exception second) {
                    log.debug("建议追问 JSON 解析失败: {}", StringUtils.left(raw, 200));
                    return List.of();
                }
            } else {
                return List.of();
            }
        }
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        int cap = Math.max(1, Math.min(5, properties.getMaxItems()));
        int maxLen = Math.max(20, properties.getMaxItemLength());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.size() && out.size() < cap; i++) {
            Object el = array.get(i);
            if (!(el instanceof String s)) {
                continue;
            }
            s = s.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.length() > maxLen) {
                s = s.substring(0, maxLen) + "…";
            }
            out.add(s);
        }
        return out;
    }

    private static String stripMarkdownFence(String text) {
        String t = text;
        if (t.startsWith("```json")) {
            t = t.substring(7);
        } else if (t.startsWith("```")) {
            t = t.substring(3);
        }
        if (t.endsWith("```")) {
            t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }
}
