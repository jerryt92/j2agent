package io.github.jerryt92.j2agent.service.llm.agent;

import io.github.jerryt92.j2agent.service.llm.mcp.McpToolCallbacksRefreshedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * MCP 工具回调刷新后，重建容器中全部 {@link AiAgent}，使助手等拿到最新的 {@code ToolCallback} 快照。
 */
@Slf4j
@Component
public class McpToolCallbacksRefreshedListener {

    private final AiAgentReloadService aiAgentReloadService;

    public McpToolCallbacksRefreshedListener(AiAgentReloadService aiAgentReloadService) {
        this.aiAgentReloadService = aiAgentReloadService;
    }

    /**
     * 收到 MCP 回调实际更新事件后逐个重建 Agent，单 Agent 失败不影响其余实例。
     */
    @EventListener
    public void onMcpToolCallbacksRefreshed(McpToolCallbacksRefreshedEvent event) {
        aiAgentReloadService.reloadAll("mcp-tool-callbacks:" + event.reason());
    }
}
