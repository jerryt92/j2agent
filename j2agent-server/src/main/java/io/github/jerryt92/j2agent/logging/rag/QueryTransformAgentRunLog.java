package io.github.jerryt92.j2agent.logging.rag;

import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import io.github.jerryt92.j2agent.service.llm.PromptConversationIdExtractor;
import org.springframework.ai.rag.Query;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG Query 改写链写入 agent-run.log；无 conversationId 时由调用方保留 j2agent.log 日志。
 */
public final class QueryTransformAgentRunLog {

    private QueryTransformAgentRunLog() {
    }

    public static void info(Query query, String step, String ragSummary, String message) {
        log(query, AgentRunEventType.RAG_TRANSFORM, ragSummary, step, message, false, null);
    }

    public static void warn(Query query, String step, String ragSummary, String message) {
        log(query, AgentRunEventType.RAG_TRANSFORM, ragSummary, step, message, true, null);
    }

    public static void warn(Query query, String step, String ragSummary, String message, Throwable throwable) {
        String detail = message;
        if (throwable != null && throwable.getMessage() != null) {
            detail = message + " | " + throwable.getMessage();
        }
        log(query, AgentRunEventType.RAG_TRANSFORM, ragSummary, step, detail, true, null);
    }

    public static void infoByConversationId(String conversationId, String step, String ragSummary, String message) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        AgentRunLogger.infoByConversationId(conversationId, AgentRunEventType.RAG_TRANSFORM,
                extra(step, ragSummary), message);
    }

    private static void log(Query query,
                            AgentRunEventType event,
                            String ragSummary,
                            String step,
                            String message,
                            boolean warn,
                            Throwable throwable) {
        String conversationId = PromptConversationIdExtractor.extract(query);
        if (conversationId == null) {
            return;
        }
        Map<String, ?> extra = extra(step, ragSummary);
        if (warn) {
            AgentRunLogger.warnByConversationId(conversationId, event, extra, message);
        } else {
            AgentRunLogger.infoByConversationId(conversationId, event, extra, message);
        }
    }

    private static Map<String, Object> extra(String step, String ragSummary) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (step != null && !step.isBlank()) {
            map.put("step", step);
        }
        if (ragSummary != null && !ragSummary.isBlank()) {
            map.put("rag", ragSummary);
        }
        return map;
    }
}
