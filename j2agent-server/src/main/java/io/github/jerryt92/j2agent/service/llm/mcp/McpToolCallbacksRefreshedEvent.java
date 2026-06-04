package io.github.jerryt92.j2agent.service.llm.mcp;

/**
 * MCP 工具回调提供器已实际更新后发布，供 AiAgent 等组件按需重建运行时图。
 * <p>reason 与 {@link McpToolRegistry#refreshToolCallbacks(String)} 入参一致，便于日志排查。</p>
 */
public record McpToolCallbacksRefreshedEvent(String reason) {
}
