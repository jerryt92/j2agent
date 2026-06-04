package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.model.QaTemplateItem;
import io.github.jerryt92.j2agent.model.QaTemplateList;
import io.github.jerryt92.j2agent.server.api.QaTemplateApi;
import io.github.jerryt92.j2agent.service.llm.agent.AgentRouter;
import io.github.jerryt92.j2agent.service.llm.agent.AiAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 按 Agent 返回热门问题模板。
 */
@Slf4j
@RestController
public class QaTemplateController implements QaTemplateApi {

    private final AgentRouter agentRouter;

    public QaTemplateController(AgentRouter agentRouter) {
        this.agentRouter = agentRouter;
    }

    @Override
    public ResponseEntity<QaTemplateList> getQaTemplate(String agentId, Integer limit) {
        return getQaTemplate(agentId, limit, CommonConstants.ZH_CN);
    }

    /**
     * 按 agentId 与 locale 随机抽取热门问题；未开启模板的 Agent 返回空列表。
     */
    public ResponseEntity<QaTemplateList> getQaTemplate(String agentId, Integer limit, String locale) {
        QaTemplateList qaTemplateList = new QaTemplateList();
        try {
            AiAgent agent = agentRouter.route(agentId);
            List<String> selectedTemplates = agent.pickQaTemplateQuestions(locale, limit);
            List<QaTemplateItem> data = selectedTemplates.stream()
                    .map(template -> new QaTemplateItem().question(template))
                    .collect(Collectors.toList());
            qaTemplateList.data(data);
            return ResponseEntity.ok(qaTemplateList);
        } catch (Throwable t) {
            log.error("Failed to load qa template for agentId={}", agentId, t);
            qaTemplateList.data(Collections.emptyList());
            return ResponseEntity.ok(qaTemplateList);
        }
    }
}
