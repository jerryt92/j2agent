package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UniversalIntentQueryToolTest {

    @Test
    void sanitizeCandidateJsonFiltersUnknownAgents() {
        AiAgent reportAgent = Mockito.mock(AiAgent.class);
        Mockito.when(reportAgent.getAgentId()).thenReturn("chat_assistant");
        Mockito.when(reportAgent.getAgentName()).thenReturn("智能报告");

        String raw = """
                [
                  {"agentId":"chat_assistant","name":"智能报告","relevance":"high","reason":"写报告"},
                  {"agentId":"unknown_agent","name":"X","relevance":"high","reason":"bad"}
                ]
                """;

        String json = UniversalIntentQueryTool.sanitizeCandidateJson(raw, List.of(reportAgent));
        assertTrue(json.contains("chat_assistant"));
        assertTrue(json.contains("high"));
        assertTrue(!json.contains("unknown_agent"));
    }

    @Test
    void sanitizeCandidateJsonReturnsEmptyArrayOnInvalidInput() {
        AiAgent agent = Mockito.mock(AiAgent.class);
        Mockito.when(agent.getAgentId()).thenReturn("a1");

        assertEquals("[]", UniversalIntentQueryTool.sanitizeCandidateJson("", List.of(agent)));
        assertEquals("[]", UniversalIntentQueryTool.sanitizeCandidateJson("not json", List.of(agent)));
    }

    @Test
    void formatCandidateBlockUsesDispatchPromptWhenPresent() {
        AiAgent agent = new StubAgent("a1", "Name", "Public desc", "Internal dispatch hint");
        String block = UniversalIntentQueryTool.formatCandidateBlock(List.of(agent));
        assertTrue(block.contains("【a1】"));
        assertTrue(block.contains("dispatchPrompt: Internal dispatch hint"));
        assertTrue(!block.contains("Public desc"));
    }

    @Test
    void formatCandidateBlockFallsBackToDescriptionWhenDispatchBlank() {
        AiAgent agent = new StubAgent("a1", "Name", "Public desc", null);
        String block = UniversalIntentQueryTool.formatCandidateBlock(List.of(agent));
        assertTrue(block.contains("dispatchPrompt: Public desc"));
    }

    @Test
    void sanitizeCandidateJsonIncludesDispatchPromptInToolResult() {
        AiAgent agent = new StubAgent("chat_assistant", "智能报表", "desc", "SQL 报表调度提示");
        String raw = """
                [{"agentId":"chat_assistant","name":"智能报表","relevance":"high","reason":"占比统计"}]
                """;
        String json = UniversalIntentQueryTool.sanitizeCandidateJson(raw, List.of(agent));
        assertTrue(json.contains("dispatchPrompt"));
        assertTrue(json.contains("SQL 报表调度提示"));
    }

    @Test
    void resolveDispatchPromptPrefersDispatchOverDescription() {
        AiAgent agent = new StubAgent("a1", "Name", "Public desc", "  Internal  ");
        assertEquals("Internal", agent.resolveDispatchPrompt());
    }

    @Test
    void resolveDispatchPromptFallsBackToDescription() {
        AiAgent agent = new StubAgent("a1", "Name", "Public desc", "");
        assertEquals("Public desc", agent.resolveDispatchPrompt());
    }

    private static final class StubAgent extends AiAgent {
        private final String id;
        private final String name;
        private final String description;
        private final String dispatch;

        private StubAgent(String id, String name, String description, String dispatch) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.dispatch = dispatch;
        }

        @Override
        public String getAgentId() {
            return id;
        }

        @Override
        public String getAgentName() {
            return name;
        }

        @Override
        public String getAgentDescription() {
            return description;
        }

        @Override
        public String getDispatchPrompt() {
            return dispatch;
        }

        @Override
        public String loadSystemPrompt() {
            return "";
        }
    }
}
