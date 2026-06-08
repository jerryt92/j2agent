package io.github.jerryt92.j2agent.service.llm;

import org.apache.commons.lang3.StringUtils;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * 从 Spring WebClient / Reactor 包装链中提取 LLM 提供商（DashScope、vLLM 等）的 HTTP 详细错误，供日志与前端展示。
 */
public final class LlmProviderErrorFormatter {

    private static final int MAX_RESPONSE_BODY_CHARS = 4096;

    private LlmProviderErrorFormatter() {
    }

    /**
     * 是否为模型 HTTP 调用类失败（含 4xx/5xx 与网络错误）。
     */
    public static boolean isProviderCallFailure(Throwable throwable) {
        return findInChain(throwable, WebClientResponseException.class) != null
                || findInChain(throwable, WebClientRequestException.class) != null
                || isEmptyStreamFailure(throwable);
    }

    /**
     * 是否为 LLM 流式响应无有效 token 的空流失败（含 ReloadableRoutingChatModel 与 Graph 嵌入流两种签名）。
     */
    public static boolean isEmptyStreamFailure(Throwable throwable) {
        return isEmptyLlmStreamFailure(throwable) || isLlmEmptyStreamMessage(throwable);
    }

    /**
     * Alibaba Graph 在嵌入流无任何有效 {@code ChatResponse} 块时抛出的空流异常。
     */
    private static boolean isLlmEmptyStreamMessage(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 16) {
            String message = current.getMessage();
            if (message != null && message.contains("LLM 流式响应为空")) {
                return true;
            }
            if (current.getCause() == null || current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    public static boolean isEmptyLlmStreamFailure(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 16) {
            String message = current.getMessage();
            if (message != null && message.contains("Empty flux detected for key 'messages'")) {
                return true;
            }
            if (current.getCause() == null || current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    /**
     * 供 ERROR 日志使用的多行详情（含 status、URL、响应体）。
     */
    public static String formatForLog(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        StringBuilder sb = new StringBuilder();
        WebClientResponseException responseEx = findInChain(throwable, WebClientResponseException.class);
        if (responseEx != null) {
            appendResponseException(sb, responseEx);
        } else {
            WebClientRequestException requestEx = findInChain(throwable, WebClientRequestException.class);
            if (requestEx != null) {
                sb.append("LLM HTTP request failed: ").append(requestEx.getMessage());
                if (requestEx.getUri() != null) {
                    sb.append(" uri=").append(requestEx.getUri());
                }
            } else {
                sb.append(throwable.getClass().getSimpleName()).append(": ").append(nullToEmpty(throwable.getMessage()));
            }
        }
        Throwable root = rootCause(throwable);
        if (root != throwable && root != responseEx) {
            sb.append(System.lineSeparator()).append("rootCause=")
                    .append(root.getClass().getSimpleName()).append(": ")
                    .append(nullToEmpty(root.getMessage()));
        }
        String chain = formatCauseChain(throwable);
        if (StringUtils.isNotBlank(chain)) {
            sb.append(System.lineSeparator()).append("causeChain=").append(chain);
        }
        return sb.toString();
    }

    /**
     * 供前端 FAILED 事件展示的简短说明（优先提供商响应体中的 message/error 字段）。
     */
    public static String formatForDisplay(Throwable throwable) {
        if (throwable == null) {
            return "模型服务调用失败";
        }
        if (isEmptyStreamFailure(throwable)) {
            Throwable root = rootCause(throwable);
            String detail = root != null ? nullToEmpty(root.getMessage()) : "";
            if (StringUtils.isNotBlank(detail)) {
                return detail;
            }
            return "模型未返回任何流式内容，请检查 LLM 接口配置（baseUrl、模型名、API Key）及上游服务是否正常";
        }
        WebClientResponseException responseEx = findInChain(throwable, WebClientResponseException.class);
        if (responseEx != null) {
            String bodySummary = summarizeResponseBody(responseEx.getResponseBodyAsString());
            if (StringUtils.isNotBlank(bodySummary)) {
                return responseEx.getStatusCode().value() + " " + responseEx.getStatusText()
                        + ": " + bodySummary;
            }
            return responseEx.getStatusCode().value() + " " + responseEx.getStatusText()
                    + " from " + shortenUri(responseEx);
        }
        WebClientRequestException requestEx = findInChain(throwable, WebClientRequestException.class);
        if (requestEx != null) {
            return "模型服务网络错误: " + nullToEmpty(requestEx.getMessage());
        }
        String msg = throwable.getMessage();
        if (StringUtils.isNotBlank(msg)) {
            return msg;
        }
        return throwable.getClass().getSimpleName();
    }

    /**
     * 解析业务 errorCode：提供商 HTTP 失败为 providerError，其余为 internalError。
     */
    public static String resolveErrorCode(Throwable throwable) {
        if (isProviderCallFailure(throwable)) {
            return "providerError";
        }
        return "internalError";
    }

    public static Throwable rootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static void appendResponseException(StringBuilder sb, WebClientResponseException ex) {
        sb.append("LLM provider HTTP ").append(ex.getStatusCode().value())
                .append(' ').append(ex.getStatusText());
        if (ex.getRequest() != null && ex.getRequest().getURI() != null) {
            sb.append(' ').append(ex.getRequest().getMethod())
                    .append(' ').append(ex.getRequest().getURI());
        }
        String body = ex.getResponseBodyAsString();
        if (StringUtils.isNotBlank(body)) {
            sb.append(System.lineSeparator()).append("responseBody=")
                    .append(truncate(body.trim(), MAX_RESPONSE_BODY_CHARS));
        }
    }

    private static String summarizeResponseBody(String body) {
        if (StringUtils.isBlank(body)) {
            return "";
        }
        String trimmed = truncate(body.trim().replaceAll("\\s+", " "), 512);
        return trimmed;
    }

    private static String shortenUri(WebClientResponseException ex) {
        if (ex.getRequest() == null || ex.getRequest().getURI() == null) {
            return "LLM endpoint";
        }
        return ex.getRequest().getURI().toString();
    }

    private static String formatCauseChain(Throwable throwable) {
        StringBuilder chain = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 8) {
            if (depth > 0) {
                chain.append(" -> ");
            }
            chain.append(current.getClass().getSimpleName());
            String msg = current.getMessage();
            if (StringUtils.isNotBlank(msg)) {
                chain.append('(').append(truncate(msg.replaceAll("\\s+", " "), 120)).append(')');
            }
            if (current.getCause() == null || current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return chain.toString();
    }

    private static <T extends Throwable> T findInChain(Throwable throwable, Class<T> type) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 16) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            if (current.getCause() == null || current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...(truncated)";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
