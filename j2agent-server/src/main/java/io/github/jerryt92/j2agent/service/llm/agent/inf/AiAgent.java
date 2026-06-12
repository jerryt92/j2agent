package io.github.jerryt92.j2agent.service.llm.agent.inf;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Agent;
import com.alibaba.cloud.ai.graph.agent.Builder;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.fastjson2.JSONObject;
import io.github.jerryt92.j2agent.config.plugin.PluginProperties;
import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.service.rag.RagSourceFileService;
import io.github.jerryt92.j2agent.service.rag.query.DefaultQueryTransformers;
import io.github.jerryt92.j2agent.service.llm.advisor.EmptyQuerySkippingRetrievalAugmentationAdvisor;
import io.github.jerryt92.j2agent.service.llm.advisor.ReactCompatibleMessageChatMemoryAdvisor;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunContext;
import io.github.jerryt92.j2agent.service.llm.agent.core.AgentRunnableContextKeys;
import io.github.jerryt92.j2agent.service.llm.agent.inf.constant.AgentThinkingOverride;
import io.github.jerryt92.j2agent.service.llm.agent.inf.feature.McpFeature;
import io.github.jerryt92.j2agent.service.llm.mcp.McpService;
import io.github.jerryt92.j2agent.service.llm.skill.AgentClassLoaderSkillRegistry;
import io.github.jerryt92.j2agent.service.llm.skill.AgentSkillsAgentHook;
import io.github.jerryt92.j2agent.service.llm.skill.AgentUiSkillLoadToolInterceptor;
import io.github.jerryt92.j2agent.service.llm.tool.AgentToolErrorReturnInterceptor;
import io.github.jerryt92.j2agent.service.llm.tool.AgentUiToolEventInterceptor;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.rag.generation.augmentation.QueryAugmenter;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.join.DocumentJoiner;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AI Agent 统一入口，屏蔽不同 Agent 的构建和执行细节。
 */
public abstract class AiAgent {
    /**
     * Agent 内部 {@code resources/skills/} 在 classpath 上的路径段。
     */
    private static final String INTERNAL_SKILLS_CLASSPATH = "skills";
    private static final String QA_TEMPLATE_RESOURCE = "qa-template.json";

    public abstract String getAgentId();

    public abstract String getAgentName();

    public abstract String getAgentDescription();

    public abstract String loadSystemPrompt();

    public int getSort() {
        return 100;
    }

    public String getLogo() {
        return "🤖";
    }

    /**
     * Agent 级深度思考默认策略；单轮可被 {@link io.github.jerryt92.j2agent.model.ChatRequestDto#getThinkingMode()} 覆盖。
     *
     * <p>子类示例：{@code return AgentThinkingOverride.OFF;}
     */
    public AgentThinkingOverride getThinkingOverride() {
        return AgentThinkingOverride.USE_PROVIDER_DEFAULT;
    }

    /**
     * 是否展示热门问题模板；默认不展示，子类显式开启。<br/>
     * 开启后需在 resource 中添加 qa-template.json。<br/>
     * qa-template.json 示例：
     * <pre>{@code
     * {
     *   "zh_CN": [
     *     "AI助手可以提供哪些帮助和技术支持"
     *   ],
     *   "en_US": [
     *     "What can AI Assistant provide help and technical support"
     *   ]
     * }
     * }</pre>
     */
    public boolean isQaTemplateEnabled() {
        return false;
    }

    /**
     * 从 Agent 所在 ClassLoader 读取 qa-template.json，按 locale 返回问题文案列表。
     */
    protected List<String> loadQaTemplateQuestions(String locale) {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(QA_TEMPLATE_RESOURCE)) {
            if (inputStream == null) {
                return Collections.emptyList();
            }
            JSONObject qaTemplateJson = JSONObject.parse(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
            String localeKey = CommonConstants.ZH_CN.equals(locale) ? CommonConstants.ZH_CN : CommonConstants.EN_US;
            if (qaTemplateJson.getJSONArray(localeKey) == null) {
                return Collections.emptyList();
            }
            return qaTemplateJson.getJSONArray(localeKey).toList(String.class);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * 随机抽取指定数量的热门问题；未开启模板或无资源时返回空列表。
     */
    public List<String> pickQaTemplateQuestions(String locale, int limit) {
        if (!isQaTemplateEnabled()) {
            return Collections.emptyList();
        }
        List<String> qaTemplates = new ArrayList<>(loadQaTemplateQuestions(locale));
        int total = qaTemplates.size();
        if (total == 0) {
            return Collections.emptyList();
        }
        if (limit > total) {
            limit = total;
        }
        Collections.shuffle(qaTemplates);
        return qaTemplates.subList(0, limit);
    }

    /**
     * 在 {@link #initAgent()} / {@link #rebuildAgent()} 中装配；volatile 保证 MCP 刷新替换实例后对读线程可见。
     */
    protected volatile Agent agent;

    @Autowired
    protected ChatModel chatModel;

    /**
     * 如需自定义记忆策略，请覆盖此变量。
     */
    @Getter
    @Autowired
    @Qualifier("defaultChatMemory")
    protected ChatMemory chatMemory;

    /**
     * Agent 工具事件 UI 事件发射器
     */
    @Autowired
    protected AgentUiToolEventInterceptor agentUiToolEventInterceptor;

    /**
     * Agent 技能加载事件 UI 事件发射器
     */
    @Autowired
    protected AgentUiSkillLoadToolInterceptor agentUiSkillLoadToolInterceptor;

    /**
     * 工具链最外层兜底：畸形 tool_call 分片与执行异常转为 ToolResponse 回注 LLM。
     */
    @Autowired
    protected AgentToolErrorReturnInterceptor agentToolErrorReturnInterceptor;

    @Autowired
    protected RagSourceFileService ragSourceFileService;

    @Autowired
    protected PluginProperties pluginProperties;

    @Autowired
    protected McpService mcpService;

    @Autowired
    private ObjectProvider<PluginProperties> pluginPropertiesProvider;

    @Autowired
    private Environment environment;

    @Autowired
    private org.springframework.beans.factory.ObjectProvider<
            io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentService> chatAttachmentServiceProvider;

    @Autowired(required = false)
    private DefaultQueryTransformers defaultQueryTransformers;

    /**
     * Spring 完成字段注入后再构建底层 {@link Agent}，保证 {@link #chatModel}、{@link #chatMemory} 等已就绪。
     */
    @PostConstruct
    protected void initAgent() {
        rebuildAgent();
    }

    /**
     * MCP 等外部依赖变更后重建底层 Agent，与首次 {@link #initAgent()} 使用同一套 {@link #buildAgent()} 逻辑。
     */
    public void rebuildAgent() {
        this.agent = buildAgent().build();
    }

    /**
     * 以流式方式执行单轮 Agent 调用；会话历史由 ChatClient 上的记忆 Advisor 维护，
     * {@link ChatMemory#CONVERSATION_ID} 放在首条 {@link UserMessage} 的 metadata 中以便跨线程解析。
     */
    public Flux<NodeOutput> stream(AgentRunContext context) throws GraphRunnerException {
        RunnableConfig runnableConfig = RunnableConfig.builder()
                .threadId(context.conversationId())
                .build();
        runnableConfig.context().put(AgentUiToolEventInterceptor.CONTEXT_KEY_TOOL_EVENT_EMITTER, context.toolEventEmitter());
        runnableConfig.context().put(AgentRunnableContextKeys.CONTEXT_KEY_CHAT_CONVERSATION_ID, context.conversationId());
        var userMessageBuilder = UserMessage.builder()
                .text(context.text())
                .metadata(Map.of(
                        ChatMemory.CONVERSATION_ID, context.conversationId(),
                        "attachments", context.attachments()));
        if (!context.attachments().isEmpty()) {
            var attachmentService = chatAttachmentServiceProvider.getIfAvailable();
            if (attachmentService == null) {
                throw new IllegalStateException("Object storage is required for image input.");
            }
            userMessageBuilder.media(attachmentService.toMedia(context.attachments()));
        }
        UserMessage userMessage = userMessageBuilder.build();
        ReactCompatibleMessageChatMemoryAdvisor.setConversationId(context.conversationId());
        return agent.stream(List.of(userMessage), runnableConfig)
                .doFinally(signalType -> ReactCompatibleMessageChatMemoryAdvisor.clear());
    }

    /**
     * 构建保序文档合并器，避免默认按分值重排导致上下文顺序变化。
     * 如需自定义，请覆盖此方法。
     *
     * @return 保序文档合并器
     */
    protected DocumentJoiner buildOrderPreservingDocumentJoiner() {
        return documentsForQuery -> {
            Map<String, Document> documentsById = new LinkedHashMap<>();
            documentsForQuery.values().forEach(documentGroups ->
                    documentGroups.forEach(documents ->
                            documents.forEach(document -> documentsById.putIfAbsent(document.getId(), document))));
            return new ArrayList<>(documentsById.values());
        };
    }

    /**
     * RAG 查询增强策略：无命中时保留原问题；有命中时在原问题后追加知识库片段，不阻断工具与技能调用。
     * 子类可按 Agent 场景覆盖。
     * <p>若使用 {@link ContextualQueryAugmenter} 且 {@code allowEmptyContext(false)}，
     * {@code emptyContextPromptTemplate} 不得含 {@code {query}} 等占位符——框架在无文档时调用
     * {@code render()} 不传变量。需在空命中消息中保留用户问题时，应设 {@code allowEmptyContext(true)}
     * 或实现自定义 {@link QueryAugmenter}。</p>
     */
    protected QueryAugmenter buildQueryAugmenter() {
        PromptTemplate promptTemplate = new PromptTemplate("""
                用户问题：{query}
                
                以下为知识库检索参考（可能不完整，可结合工具与技能继续推理）：
                ---------------------
                {context}
                ---------------------
                """);
        return ContextualQueryAugmenter.builder()
                .promptTemplate(promptTemplate)
                .allowEmptyContext(true)
                .build();
    }

    /**
     * 如需赋予 Agent RAG 能力，请覆盖此方法。
     *
     * @return
     */
    protected DocumentRetriever buildDocumentRetriever() {
        return null;
    }

    /**
     * 是否向前端展示 RAG 命中的知识库文件来源（实时 WebSocket PATCH + 历史回放 srcFile）。
     * 默认关闭；{@code rag_infos} 仍正常落库，仅 UI 层按本开关过滤。
     */
    public boolean isRagSourceDisplayEnabled() {
        return false;
    }

    /**
     * RAG 检索前 Query 预处理链（Multimodal → Compression → Rewrite）。
     * 子类返回空数组可关闭；默认走 {@link DefaultQueryTransformers}。
     */
    protected QueryTransformer[] buildQueryTransformers() {
        if (defaultQueryTransformers == null) {
            return new QueryTransformer[0];
        }
        return defaultQueryTransformers.build(
                chatAttachmentServiceProvider.getIfAvailable());
    }

    /**
     * 如需赋予 Agent 工具能力，请覆盖此方法。
     *
     * @return
     */
    protected Object[] buildTools() {
        return new Object[0];
    }

    /**
     * 将 {@link #buildTools()} 转为 {@link ToolCallback}；子类可覆盖以自定义本地工具合并。
     * MCP 由 {@link #buildEffectiveToolCallbacks()} 在 {@link #buildAgent()} 中统一追加，不受本方法覆盖影响。
     *
     * @return 工具回调数组
     */
    protected ToolCallback[] buildToolCallbacks() {
        Object[] tools = buildTools();
        if (tools == null || tools.length == 0) {
            return new ToolCallback[0];
        }
        List<ToolCallback> toolCallbackList = new ArrayList<>(Arrays.asList(ToolCallbacks.from(tools)));
        return toolCallbackList.stream().filter(Objects::nonNull).toArray(ToolCallback[]::new);
    }

    /**
     * 最终注入 ReactAgent 的工具链：子类 {@link #buildToolCallbacks()} 结果 + {@link McpFeature} MCP 工具。
     * 即使子类覆盖 {@code buildToolCallbacks()}，只要 {@link #buildAgent()} 走基类逻辑（含 {@code super.buildAgent()}），
     * 已实现 {@link McpFeature} 的 Agent 仍会合并 MCP。
     */
    private ToolCallback[] buildEffectiveToolCallbacks() {
        List<ToolCallback> effectiveToolCallbacks = new ArrayList<>(Arrays.asList(buildToolCallbacks()));
        appendMcpToolCallbacksIfNeeded(effectiveToolCallbacks);
        return effectiveToolCallbacks.stream().filter(Objects::nonNull).toArray(ToolCallback[]::new);
    }

    private void appendMcpToolCallbacksIfNeeded(List<ToolCallback> toolCallbackList) {
        if (!(this instanceof McpFeature mcpFeature) || mcpService == null) {
            return;
        }
        ToolCallback[] mcpCallbacks = mcpService.getToolCallbacksForAgent(mcpFeature);
        if (mcpCallbacks == null) {
            return;
        }
        Collections.addAll(toolCallbackList, mcpCallbacks);
    }

    /**
     * {@link ReactAgent.Builder#tools} 不允许数组中含 null，对子类合并结果做兜底过滤。
     */
    private static ToolCallback[] withoutNullToolCallbacks(ToolCallback[] callbacks) {
        if (callbacks == null || callbacks.length == 0) {
            return new ToolCallback[0];
        }
        return Arrays.stream(callbacks).filter(Objects::nonNull).toArray(ToolCallback[]::new);
    }

    private AgentSkillsAgentHook buildSkillsAgentHook() {
        PluginProperties effectivePluginProperties = resolvePluginProperties();
        AgentClassLoaderSkillRegistry agentSkillRegistry = AgentClassLoaderSkillRegistry.builder()
                .agent(this)
                .pluginProperties(effectivePluginProperties)
                .pluginPathOverride(resolvePluginPath())
                .internalSkillsClasspath(INTERNAL_SKILLS_CLASSPATH)
                .build();
        List<SkillMetadata> loadedSkillMetadata = agentSkillRegistry.listAll();
        if (loadedSkillMetadata == null || loadedSkillMetadata.isEmpty()) {
            return null;
        }
        Map<String, List<ToolCallback>> groupedTools = new LinkedHashMap<>();
        loadedSkillMetadata.forEach(metadata -> groupedTools.put(metadata.getName(), List.of()));
        return AgentSkillsAgentHook.builder()
                .skillRegistry(agentSkillRegistry)
                .groupedTools(groupedTools)
                .build();
    }

    private PluginProperties resolvePluginProperties() {
        if (pluginProperties != null) {
            return pluginProperties;
        }
        return pluginPropertiesProvider.getIfAvailable();
    }

    private String resolvePluginPath() {
        PluginProperties effective = resolvePluginProperties();
        if (effective != null && StringUtils.hasText(effective.resolvePath())) {
            return effective.resolvePath();
        }
        return environment != null ? environment.getProperty("j2agent.plugin.path") : null;
    }

    /**
     * React 拦截器链；最外层为工具异常兜底，其内为 UI 事件与技能加载。
     *
     * @return 拦截器实例数组
     */
    protected Interceptor[] buildInterceptors() {
        return new Interceptor[]{
                agentToolErrorReturnInterceptor,
                agentUiToolEventInterceptor,
                agentUiSkillLoadToolInterceptor
        };
    }

    /**
     * 最终生效的拦截器链。即使子类覆盖 {@link #buildInterceptors()}，也保留工具异常兜底，
     * 避免模型偶发误调用未知工具名时直接中断整轮流式响应。
     */
    private Interceptor[] buildEffectiveInterceptors() {
        Interceptor[] customInterceptors = buildInterceptors();
        List<Interceptor> effectiveInterceptors = new ArrayList<>();
        if (agentToolErrorReturnInterceptor != null) {
            effectiveInterceptors.add(agentToolErrorReturnInterceptor);
        }
        if (customInterceptors != null) {
            Arrays.stream(customInterceptors)
                    .filter(Objects::nonNull)
                    .filter(interceptor -> interceptor != agentToolErrorReturnInterceptor)
                    .forEach(effectiveInterceptors::add);
        }
        return effectiveInterceptors.toArray(new Interceptor[0]);
    }

    /**
     * 如需使用其他策略的 Agent，请覆盖此方法。
     *
     * @return
     */
    protected Builder buildAgent() {
        ReactCompatibleMessageChatMemoryAdvisor memoryAdvisor = ReactCompatibleMessageChatMemoryAdvisor.builder(chatMemory).build();
        DocumentRetriever documentRetriever = buildDocumentRetriever();
        ChatClient chatClient;
        if (documentRetriever == null) {
            chatClient = ChatClient.builder(chatModel)
                    .defaultAdvisors(memoryAdvisor)
                    .build();
        } else {
            var ragAdvisorBuilder = RetrievalAugmentationAdvisor.builder()
                    .documentRetriever(documentRetriever)
                    .documentJoiner(buildOrderPreservingDocumentJoiner())
                    .queryAugmenter(buildQueryAugmenter());
            QueryTransformer[] queryTransformers = buildQueryTransformers();
            if (queryTransformers.length > 0) {
                ragAdvisorBuilder.queryTransformers(queryTransformers);
            }
            RetrievalAugmentationAdvisor ragAdvisor = ragAdvisorBuilder.build();
            chatClient = ChatClient.builder(chatModel)
                    .defaultAdvisors(memoryAdvisor,
                            EmptyQuerySkippingRetrievalAugmentationAdvisor.wrap(
                                    ragAdvisor, ragSourceFileService, isRagSourceDisplayEnabled()))
                    .build();
        }
        Builder builder = ReactAgent.builder()
                .name(getAgentId())
                .chatClient(chatClient)
                .tools(withoutNullToolCallbacks(buildEffectiveToolCallbacks()))
                .systemPrompt(loadSystemPrompt())
                .interceptors(buildEffectiveInterceptors());
        AgentSkillsAgentHook skillsHook = buildSkillsAgentHook();
        if (skillsHook != null) {
            builder = builder.hooks(skillsHook);
        }
        return builder;
    }
}
