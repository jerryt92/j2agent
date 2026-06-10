package io.github.jerryt92.j2agent.config.llm;

import java.time.Duration;

/**
 * 同步 LLM（{@link io.github.jerryt92.j2agent.service.llm.LlmSyncService}）及
 * {@link LlmReactiveHttpClientFactory} 出站调用的统一超时配置。
 */
public final class LlmSyncTimeouts {

    public static final int CONNECT_TIMEOUT_MILLIS = 10_000;

    /** 含多模态/VLM 在内的读/响应超时（秒）；过短会导致纯图 RAG 改写 ReadTimeout。 */
    public static final int RESPONSE_READ_TIMEOUT_SECONDS = 120;

    private LlmSyncTimeouts() {
    }

    public static Duration responseReadTimeout() {
        return Duration.ofSeconds(RESPONSE_READ_TIMEOUT_SECONDS);
    }
}
