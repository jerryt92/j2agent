package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniversalDispatchDecisionServiceTest {

    @Test
    void parseInvokeDecisionWithoutQuery() {
        String raw = """
                {"action":"invoke","agentId":"wiki","reason":"需要知识库"}
                """;
        UniversalDispatchDecisionService.DispatchDecision decision =
                UniversalDispatchDecisionService.parseDecision(raw);
        assertTrue(decision.isInvoke());
        assertEquals("wiki", decision.agentId());
        assertEquals(null, decision.query());
    }

    @Test
    void parseInvokeDecision() {
        String raw = """
                {"action":"invoke","agentId":"wiki","query":"查文档","reason":"需要知识库"}
                """;
        UniversalDispatchDecisionService.DispatchDecision decision =
                UniversalDispatchDecisionService.parseDecision(raw);
        assertTrue(decision.isInvoke());
        assertEquals("wiki", decision.agentId());
        assertEquals("查文档", decision.query());
    }

    @Test
    void parseCompleteDecision() {
        String raw = """
                {"action":"complete","reason":"无需子智能体"}
                """;
        UniversalDispatchDecisionService.DispatchDecision decision =
                UniversalDispatchDecisionService.parseDecision(raw);
        assertTrue(decision.isComplete());
    }

    @Test
    void parseInvalidFallsBackToComplete() {
        UniversalDispatchDecisionService.DispatchDecision decision =
                UniversalDispatchDecisionService.parseDecision("not json");
        assertTrue(decision.isComplete());
    }

    @Test
    void isCandidatesEmpty() {
        assertTrue(UniversalIntentQueryService.isCandidatesEmpty("[]"));
        assertTrue(UniversalIntentQueryService.isCandidatesEmpty("  "));
        assertFalse(UniversalIntentQueryService.isCandidatesEmpty("[{\"agentId\":\"a1\"}]"));
    }
}
