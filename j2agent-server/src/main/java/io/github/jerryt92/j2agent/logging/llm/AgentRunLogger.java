package io.github.jerryt92.j2agent.logging.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 运行时专用日志门面，统一 key=value 前缀并写入 {@code agent-run.log}。
 */
public final class AgentRunLogger {

    public static final String LOGGER_NAME = "io.github.jerryt92.j2agent.agent.run";
    public static final int PREVIEW_MAX_CHARS = 200;

    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);

    private AgentRunLogger() {
    }

    public static void info(AgentRunLogSnapshot snapshot,
                            AgentRunEventType event,
                            Map<String, ?> extra,
                            String message) {
        if (snapshot == null || event == null) {
            return;
        }
        LOG.info("{} - {}", formatPrefix(snapshot, event, extra), nullToEmpty(message));
    }

    public static void warn(AgentRunLogSnapshot snapshot,
                            AgentRunEventType event,
                            Map<String, ?> extra,
                            String message) {
        if (snapshot == null || event == null) {
            return;
        }
        LOG.warn("{} - {}", formatPrefix(snapshot, event, extra), nullToEmpty(message));
    }

    public static void error(AgentRunLogSnapshot snapshot,
                             AgentRunEventType event,
                             Map<String, ?> extra,
                             String message,
                             Throwable throwable) {
        if (snapshot == null || event == null) {
            return;
        }
        if (throwable != null) {
            LOG.error("{} - {}", formatPrefix(snapshot, event, extra), nullToEmpty(message), throwable);
        } else {
            LOG.error("{} - {}", formatPrefix(snapshot, event, extra), nullToEmpty(message));
        }
    }

    public static void infoByConversationId(String conversationId,
                                            AgentRunEventType event,
                                            Map<String, ?> extra,
                                            String message) {
        AgentRunLogSnapshot snapshot = AgentRunLogContext.lookup(conversationId);
        if (snapshot == null) {
            return;
        }
        info(snapshot, event, extra, message);
    }

    public static void warnByConversationId(String conversationId,
                                            AgentRunEventType event,
                                            Map<String, ?> extra,
                                            String message) {
        AgentRunLogSnapshot snapshot = AgentRunLogContext.lookup(conversationId);
        if (snapshot == null) {
            return;
        }
        warn(snapshot, event, extra, message);
    }

    public static void errorByConversationId(String conversationId,
                                             AgentRunEventType event,
                                             Map<String, ?> extra,
                                             String message,
                                             Throwable throwable) {
        AgentRunLogSnapshot snapshot = AgentRunLogContext.lookup(conversationId);
        if (snapshot == null) {
            return;
        }
        error(snapshot, event, extra, message, throwable);
    }

    public static String preview(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= PREVIEW_MAX_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, PREVIEW_MAX_CHARS) + "...";
    }

    public static Map<String, Object> kv(Object... pairs) {
        if (pairs == null || pairs.length == 0) {
            return Map.of();
        }
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("kv pairs must be even-length");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            Object key = pairs[i];
            if (key == null) {
                continue;
            }
            map.put(String.valueOf(key), pairs[i + 1]);
        }
        return map;
    }

    private static String formatPrefix(AgentRunLogSnapshot snapshot,
                                       AgentRunEventType event,
                                       Map<String, ?> extra) {
        StringBuilder sb = new StringBuilder(256);
        appendKv(sb, "contextId", snapshot.contextId());
        appendKv(sb, "turnId", snapshot.turnId());
        appendKv(sb, "conversationId", snapshot.conversationId());
        appendKv(sb, "userId", snapshot.userId());
        appendKv(sb, "agentId", snapshot.agentId());
        appendKv(sb, "event", event.name());
        if (extra != null) {
            for (Map.Entry<String, ?> entry : extra.entrySet()) {
                appendKv(sb, entry.getKey(), entry.getValue());
            }
        }
        return sb.toString();
    }

    private static void appendKv(StringBuilder sb, String key, Object value) {
        if (!StringUtils.hasText(key) || value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append(' ');
        }
        sb.append(key).append('=').append(text);
    }

    private static String nullToEmpty(String message) {
        return message == null ? "" : message;
    }
}
