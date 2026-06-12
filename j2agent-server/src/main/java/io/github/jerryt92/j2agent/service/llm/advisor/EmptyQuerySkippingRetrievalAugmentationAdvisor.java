package io.github.jerryt92.j2agent.service.llm.advisor;

import io.github.jerryt92.j2agent.service.llm.rag.TurnRagSourceRegistry;
import io.github.jerryt92.j2agent.service.rag.RagSourceFileService;
import io.github.jerryt92.j2agent.service.rag.query.MultimodalQueryTransformer;
import io.github.jerryt92.j2agent.service.rag.query.QueryUserMessageSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 无检索输入（无文字且无图片）时跳过 RAG，避免空 query 进入检索链路。
 * 纯图片消息在交给 {@link RetrievalAugmentationAdvisor} 前注入不可见占位 query 文本（Spring AI {@code Query} 要求 text 非空），
 * 再由 {@link MultimodalQueryTransformer} 产出真实检索句。
 */
@Slf4j
public final class EmptyQuerySkippingRetrievalAugmentationAdvisor implements BaseAdvisor {

    static final String SKIP_RAG_CONTEXT_KEY = "rag.skip";

    private final RetrievalAugmentationAdvisor delegate;
    private final RagSourceFileService ragSourceFileService;
    private final boolean ragSourceDisplayEnabled;

    private EmptyQuerySkippingRetrievalAugmentationAdvisor(RetrievalAugmentationAdvisor delegate,
                                                           RagSourceFileService ragSourceFileService,
                                                           boolean ragSourceDisplayEnabled) {
        this.delegate = delegate;
        this.ragSourceFileService = ragSourceFileService;
        this.ragSourceDisplayEnabled = ragSourceDisplayEnabled;
    }

    public static EmptyQuerySkippingRetrievalAugmentationAdvisor wrap(RetrievalAugmentationAdvisor delegate,
                                                                      RagSourceFileService ragSourceFileService,
                                                                      boolean ragSourceDisplayEnabled) {
        return new EmptyQuerySkippingRetrievalAugmentationAdvisor(
                delegate, ragSourceFileService, ragSourceDisplayEnabled);
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (shouldSkipRag(request)) {
            log.info("query transform: skipped entire retrieval (no text and no image)");
            return request.mutate().context(SKIP_RAG_CONTEXT_KEY, true).build();
        }
        ChatClientRequest ragReady = QueryUserMessageSupport.patchRequestForImageOnlyRag(request);
        ChatClientRequest processed = delegate.before(ragReady, chain);
        publishRagSourcesIfPresent(processed);
        return processed;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        if (Boolean.TRUE.equals(response.context().get(SKIP_RAG_CONTEXT_KEY))) {
            return response;
        }
        return delegate.after(response, chain);
    }

    @Override
    public int getOrder() {
        return delegate.getOrder();
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    static boolean shouldSkipRag(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return true;
        }
        UserMessage userMessage = request.prompt().getUserMessage();
        if (userMessage == null) {
            return true;
        }
        return !QueryUserMessageSupport.hasRetrievalInput(userMessage);
    }

    @SuppressWarnings("unchecked")
    private void publishRagSourcesIfPresent(ChatClientRequest processed) {
        Object raw = processed.context().get(RetrievalAugmentationAdvisor.DOCUMENT_CONTEXT);
        if (!(raw instanceof List<?> list)) {
            return;
        }
        List<Document> documents = (List<Document>) list;
        RagSourceFileService.ResolvedRagSources resolved = ragSourceFileService.resolveUniqueMdSources(documents);
        if (resolved.isEmpty()) {
            return;
        }
        String conversationId = resolveConversationId(processed);
        if (!StringUtils.hasText(conversationId)) {
            log.warn("RAG 来源发布跳过: 无法解析 conversationId");
            return;
        }
        TurnRagSourceRegistry.publishSources(
                conversationId, resolved.srcFiles(), resolved.ragInfos(), ragSourceDisplayEnabled);
    }

    private static String resolveConversationId(ChatClientRequest request) {
        if (request == null) {
            return null;
        }
        Map<String, Object> context = request.context();
        if (context != null && context.containsKey(ChatMemory.CONVERSATION_ID)) {
            Object fromContext = context.get(ChatMemory.CONVERSATION_ID);
            if (fromContext != null && StringUtils.hasText(fromContext.toString())) {
                return fromContext.toString();
            }
        }
        if (request.prompt() == null) {
            return null;
        }
        for (Message message : request.prompt().getInstructions()) {
            if (!(message instanceof UserMessage userMessage) || userMessage.getMetadata() == null) {
                continue;
            }
            Object fromMeta = userMessage.getMetadata().get(ChatMemory.CONVERSATION_ID);
            if (fromMeta != null && StringUtils.hasText(fromMeta.toString())) {
                return fromMeta.toString();
            }
        }
        UserMessage lastUser = request.prompt().getUserMessage();
        if (lastUser != null && lastUser.getMetadata() != null) {
            Object fromMeta = lastUser.getMetadata().get(ChatMemory.CONVERSATION_ID);
            if (fromMeta != null && StringUtils.hasText(fromMeta.toString())) {
                return fromMeta.toString();
            }
        }
        return null;
    }
}
