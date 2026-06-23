package io.github.jerryt92.j2agent.service.llm.agent;

/**
 * Agent 流式输出片段：回答正文与推理 metadata 可同 chunk 到达。
 */
public record StreamingTextParts(String answerDelta, String reasoningDelta) {
}
