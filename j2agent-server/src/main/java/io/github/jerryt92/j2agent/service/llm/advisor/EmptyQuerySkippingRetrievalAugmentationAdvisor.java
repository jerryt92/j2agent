package io.github.jerryt92.j2agent.service.llm.advisor;

import io.github.jerryt92.j2agent.service.rag.query.MultimodalQueryTransformer;
import io.github.jerryt92.j2agent.service.rag.query.QueryUserMessageSupport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;

/**
 * 无检索输入（无文字且无图片）时跳过 RAG，避免空 query 进入检索链路。
 * 纯图片消息在交给 {@link RetrievalAugmentationAdvisor} 前注入不可见占位 query 文本（Spring AI {@code Query} 要求 text 非空），
 * 再由 {@link MultimodalQueryTransformer} 产出真实检索句。
 */
@Slf4j
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
            log.info("query transform: skipped entire retrieval (no text and no image)");
            return request.mutate().context(SKIP_RAG_CONTEXT_KEY, true).build();
        }
        ChatClientRequest ragReady = QueryUserMessageSupport.patchRequestForImageOnlyRag(request);
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
