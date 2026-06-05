package io.github.jerryt92.j2agent.service.llm.agent.inf.feature;

import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;

import java.util.Set;

/**
 * Agent 可选特性：从平台 MCP 配置加载共享 MCP 工具。
 *
 * <p>需要 MCP 能力的 Agent 应显式 {@code implements McpFeature}（{@link AiAgent} 本身不实现）。
 * 未实现本接口的 Agent 不会合并任何 MCP 工具。
 *
 * <p>{@link #useAllMcpServers()} 默认为 {@code true}，此时合并 DB 配置
 * {@code mcp-config-json} 中当前已连接的全部 MCP Server 工具，{@link #useMcpServers()} 不生效。
 * 若返回 {@code false}，则仅合并 {@link #useMcpServers()} 指定的 server 名称。
 */
public interface McpFeature {

    /**
     * @return 是否合并全部已连接 MCP Server 的工具；默认 {@code true}
     */
    default boolean useAllMcpServers() {
        return true;
    }

    /**
     * 仅当 {@link #useAllMcpServers()} 为 {@code false} 时生效。
     *
     * @return MCP server 名称集合（对应 {@code mcp-config-json} → {@code mcpServers} 的键名）
     */
    default Set<String> useMcpServers() {
        return Set.of();
    }
}
