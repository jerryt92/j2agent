package io.github.jerryt92.j2agent.service.llm.agent.builtin.knowledgeqa;

import io.github.jerryt92.j2agent.config.rag.KnowledgeRepoProperties;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeMarkdownImageRewriter;
import io.github.jerryt92.j2agent.service.rag.knowledge.repo.KnowledgeRepoMetadataService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库问答助手工具装配测试。
 */
class KnowledgeQaAssistantAgentTest {

    @Test
    void buildTools_containsDedicatedKnowledgeQaGrepTool() {
        KnowledgeQaAssistantAgent agent = new KnowledgeQaAssistantAgent(
                null,
                new KnowledgeRepoMetadataService(new KnowledgeRepoProperties()),
                new KnowledgeMarkdownImageRewriter());

        Object[] tools = agent.buildTools();

        assertEquals(1, tools.length);
        assertInstanceOf(KnowledgeQaGrepTool.class, tools[0]);
        assertTrue(java.util.Arrays.stream(ToolCallbacks.from(tools))
                .anyMatch(callback -> "grep_knowledge_repo".equals(callback.getToolDefinition().name())));
    }
}
