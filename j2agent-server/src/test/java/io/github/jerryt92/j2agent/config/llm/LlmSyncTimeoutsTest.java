package io.github.jerryt92.j2agent.config.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmSyncTimeoutsTest {

    @Test
    void responseReadTimeoutShouldMatchSecondsConstant() {
        assertEquals(LlmSyncTimeouts.RESPONSE_READ_TIMEOUT_SECONDS,
                LlmSyncTimeouts.responseReadTimeout().toSeconds());
    }
}
