package io.github.jerryt92.j2agent.config.web;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 请求级 traceId，供日志 MDC 与 API 错误响应关联。
 */
public final class TraceIdContext {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER_NAME = "X-Trace-Id";

    private TraceIdContext() {
    }

    public static String currentOrNew() {
        String traceId = MDC.get(MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
            MDC.put(MDC_KEY, traceId);
        }
        return traceId;
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static void set(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(MDC_KEY, traceId);
        }
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
