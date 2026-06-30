package io.github.jerryt92.j2agent.service.llm.agent.builtin;

import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class UniversalIntentQueryServiceTest {

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

        String json = UniversalIntentQueryService.sanitizeCandidateJson(raw, List.of(reportAgent));
        assertTrue(json.contains("chat_assistant"));
        assertTrue(json.contains("high"));
        assertTrue(!json.contains("unknown_agent"));
    }

    @Test
    void sanitizeCandidateJsonReturnsEmptyArrayOnInvalidInput() {
        AiAgent agent = Mockito.mock(AiAgent.class);
        Mockito.when(agent.getAgentId()).thenReturn("a1");

        assertEquals("[]", UniversalIntentQueryService.sanitizeCandidateJson("", List.of(agent)));
        assertEquals("[]", UniversalIntentQueryService.sanitizeCandidateJson("not json", List.of(agent)));
    }

    @Test
    void formatCandidateBlockUsesDispatchPromptWhenPresent() {
        AiAgent agent = new StubAgent("a1", "Name", "Public desc", "Internal dispatch hint");
        String block = UniversalIntentQueryService.formatCandidateBlock(List.of(agent));
        assertTrue(block.contains("【a1】"));
        assertTrue(block.contains("dispatchPrompt: Internal dispatch hint"));
        assertTrue(!block.contains("Public desc"));
    }

    @Test
    void formatCandidateBlockFallsBackToDescriptionWhenDispatchBlank() {
        AiAgent agent = new StubAgent("a1", "Name", "Public desc", null);
        String block = UniversalIntentQueryService.formatCandidateBlock(List.of(agent));
        assertTrue(block.contains("dispatchPrompt: Public desc"));
    }

    @Test
    void sanitizeCandidateJsonIncludesDispatchPromptInResult() {
        AiAgent agent = new StubAgent("chat_assistant", "智能报表", "desc", "SQL 报表调度提示");
        String raw = """
                [{"agentId":"chat_assistant","name":"智能报表","relevance":"high","reason":"占比统计"}]
                """;
        String json = UniversalIntentQueryService.sanitizeCandidateJson(raw, List.of(agent));
        assertTrue(json.contains("dispatchPrompt"));
        assertTrue(json.contains("SQL 报表调度提示"));
    }

    @Test
    void buildRoutingQueryWithoutHistoryUsesLatestMessageOnly() {
        UniversalIntentQueryService service = new UniversalIntentQueryService(null, null);
        String query = service.buildRoutingQuery(null, "conv-1", "  hello  ");
        assertEquals("【本轮问题】\nhello", query);
    }

    @Test
    void buildRoutingQueryIncludesRecentDialogueFromMemory() {
        ChatMemory memory = Mockito.mock(ChatMemory.class);
        when(memory.get("conv-1")).thenReturn(List.of(
                new UserMessage("上一轮问题"),
                new AssistantMessage("上一轮回答"),
                new UserMessage("更早问题"),
                AssistantMessage.builder().toolCalls(List.of()).content("").build()));
        UniversalIntentQueryService service = new UniversalIntentQueryService(null, null);
        String query = service.buildRoutingQuery(memory, "conv-1", "本轮问题");
        assertTrue(query.contains("【对话上下文】"));
        assertTrue(query.contains("用户: 上一轮问题"));
        assertTrue(query.contains("助手: 上一轮回答"));
        assertTrue(query.contains("【本轮问题】\n本轮问题"));
    }

    @Test
    void buildRoutingQueryFromMessagesIncludesTraceAndLatestUser() {
        UniversalIntentQueryService service = new UniversalIntentQueryService(null, null);
        String query = service.buildRoutingQueryFromMessages(
                List.of(new UserMessage("上一轮"), new AssistantMessage("回答"), new UserMessage("本轮")),
                "【本回合已执行子智能体调用】\n- agentId: wiki");
        assertTrue(query.contains("用户: 上一轮"));
        assertTrue(query.contains("【本回合已执行子智能体调用】"));
        assertTrue(query.contains("【本轮问题】\n本轮"));
    }

    @Test
    void extractRecentDialogueLinesSkipsToolCallAssistantMessages() {
        List<String> lines = UniversalIntentQueryService.extractRecentDialogueLines(List.of(
                new UserMessage("u1"),
                AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall("id", "function", "call_sub_agent", "{}")))
                        .content("")
                        .build(),
                new AssistantMessage("a1")), 3);
        assertEquals(2, lines.size());
        assertEquals("用户: u1", lines.get(0));
        assertEquals("助手: a1", lines.get(1));
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
