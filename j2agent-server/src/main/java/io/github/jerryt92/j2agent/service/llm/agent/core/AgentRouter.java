package io.github.jerryt92.j2agent.service.llm.agent.core;

import io.github.jerryt92.j2agent.model.AgentInfoDto;
import io.github.jerryt92.j2agent.model.AgentInfoList;
import io.github.jerryt92.j2agent.service.llm.agent.inf.AiAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Agent 路由器，根据 WebSocket 传入的 agentId 选择具体 Agent。
 */
@Slf4j
@Component
public class AgentRouter {

    private final ApplicationContext applicationContext;
    private volatile Map<String, AiAgent> agents = Map.of();

    public AgentRouter(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        refresh();
    }

    /**
     * 从 Spring 容器重建 agentId → Agent 映射；存在重复 agentId 时抛出异常。
     */
    public synchronized void refresh() {
        Map<String, AiAgent> next = new LinkedHashMap<>();
        String[] beanNames = applicationContext.getBeanNamesForType(AiAgent.class, true, false);
        for (String beanName : beanNames) {
            AiAgent agent;
            String agentId;
            try {
                agent = applicationContext.getBean(beanName, AiAgent.class);
                agentId = agent.getAgentId();
            } catch (Exception ex) {
                log.error("Skip failed AiAgent bean while refreshing router: beanName={}", beanName, ex);
                continue;
            }
            if (next.containsKey(agentId)) {
                throw new IllegalStateException("Duplicate agentId in container: " + agentId);
            }
            next.put(agentId, agent);
        }
        this.agents = Map.copyOf(next);
    }

    /**
     * 解析 agentId；兼容历史客户端传入的 assistant。
     */
    public AiAgent route(String agentId) {
        String resolvedId = agentId;
        if ("assistant".equals(agentId)) {
            resolvedId = "chat_assistant";
        }
        Map<String, AiAgent> snapshot = agents;
        AiAgent agent = snapshot.get(resolvedId);
        if (agent == null) {
            throw new IllegalArgumentException("Unsupported agentId: " + agentId);
        }
        return agent;
    }

    /**
     * 列出所有已注册智能体（按 sort、agentId 排序），供前端展示卡片。
     */
    public AgentInfoList listRegisteredAgents() {
        List<AgentInfoDto> items = agents.values().stream()
                .sorted(Comparator.comparingInt(AiAgent::getSort)
                        .thenComparing(AiAgent::getAgentId))
                .map(a -> new AgentInfoDto()
                        .agentId(a.getAgentId())
                        .name(a.getAgentName())
                        .description(a.getAgentDescription())
                        .showHotQuestions(a.isQaTemplateEnabled())
                        .sort(a.getSort())
                        .logo(a.getLogo()))
                .collect(Collectors.toList());
        return new AgentInfoList().agents(items);
    }
}
