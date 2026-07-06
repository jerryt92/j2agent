package io.github.jerryt92.j2agent.service.llm.advisor;

import io.github.jerryt92.j2agent.logging.llm.AgentRunEventType;
import io.github.jerryt92.j2agent.logging.llm.AgentRunLogger;
import io.github.jerryt92.j2agent.service.llm.PromptConversationIdExtractor;
import io.github.jerryt92.j2agent.service.rag.query.MultimodalQueryTransformer;
import io.github.jerryt92.j2agent.service.rag.query.QueryUserMessageSupport;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * 无检索输入（无文字且无图片）时跳过 RAG，避免空 query 进入检索链路。
 * 纯图片消息在交给 {@link RetrievalAugmentationAdvisor} 前注入不可见占位 query 文本（Spring AI {@code Query} 要求 text 非空），
 * 再由 {@link MultimodalQueryTransformer} 产出真实检索句。
 * <p>RAG 来源发布由 {@link io.github.jerryt92.j2agent.service.rag.RagSourcePublicationService} 在检索器内同步完成。</p>
 */
public final class EmptyQuerySkippingRetrievalAugmentationAdvisor implements BaseAdvisor {

    static final String SKIP_RAG_CONTEXT_KEY = "rag.skip";

    private final RetrievalAugmentationAdvisor delegate;

    private EmptyQuerySkippingRetrievalAugmentationAdvisor(RetrievalAugmentationAdvisor delegate) {
        this.delegate = delegate;
    }

    public static EmptyQuerySkippingRetrievalAugmentationAdvisor wrap(RetrievalAugmentationAdvisor delegate) {
        return new EmptyQuerySkippingRetrievalAugmentationAdvisor(delegate);
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (shouldSkipRag(request)) {
            String conversationId = request.prompt() != null
                    ? PromptConversationIdExtractor.extract(request.prompt())
                    : null;
            AgentRunLogger.infoByConversationId(conversationId, AgentRunEventType.RAG_SKIP,
                    AgentRunLogger.kv("rag", "reason=noTextAndNoImage"),
                    "skipped entire retrieval");
            return request.mutate().context(SKIP_RAG_CONTEXT_KEY, true).build();
        }
        ChatClientRequest ragReady = QueryUserMessageSupport.patchRequestForImageOnlyRag(
                request,
                request.prompt() != null ? PromptConversationIdExtractor.extract(request.prompt()) : null);
        return delegate.before(ragReady, chain);
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

    @Override
    public Scheduler getScheduler() {
        return Schedulers.immediate();
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
}
