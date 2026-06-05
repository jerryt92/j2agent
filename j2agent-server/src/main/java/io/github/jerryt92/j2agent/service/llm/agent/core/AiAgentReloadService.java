package io.github.jerryt92.j2agent.service.llm.agent.core;

import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 重建容器中全部 {@link AiAgent} 运行时图（与 MCP 工具刷新后的逻辑一致）。
 */
@Slf4j
@Service
public class AiAgentReloadService {

    private final ApplicationContext applicationContext;

    public AiAgentReloadService(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 逐个调用 {@link AiAgent#rebuildAgent()}，单 Agent 失败不影响其余实例。
     *
     * @param reason 触发原因，用于日志
     * @return 重载结果摘要
     */
    public AgentReloadSummary reloadAll(String reason) {
        String[] beanNames = applicationContext.getBeanNamesForType(AiAgent.class, true, false);
        if (beanNames.length == 0) {
            log.debug("Skip AiAgent reload, no beans. reason={}", reason);
            return new AgentReloadSummary(0, 0, List.of());
        }
        log.info("Reloading AiAgent beans. reason={}, count={}", reason, beanNames.length);
        int successCount = 0;
        List<AgentReloadFailure> failures = new ArrayList<>();
        for (String beanName : beanNames) {
            try {
                AiAgent aiAgent = applicationContext.getBean(beanName, AiAgent.class);
                aiAgent.rebuildAgent();
                successCount++;
                log.info("AiAgent reloaded: agentId={}", aiAgent.getAgentId());
            } catch (Exception ex) {
                log.error("AiAgent reload failed: beanName={}", beanName, ex);
                failures.add(new AgentReloadFailure(beanName, ex.getMessage()));
            }
        }
        return new AgentReloadSummary(successCount, failures.size(), failures);
    }

    /**
     * Agent 重载结果摘要。
     */
    public record AgentReloadSummary(int successCount, int failureCount, List<AgentReloadFailure> failures) {
    }

    /**
     * 单个 Agent 重载失败信息。
     */
    public record AgentReloadFailure(String agentId, String message) {
    }
}
