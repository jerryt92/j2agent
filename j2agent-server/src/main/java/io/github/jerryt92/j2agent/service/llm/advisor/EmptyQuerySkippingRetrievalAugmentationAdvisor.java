package io.github.jerryt92.j2agent.service.llm.advisor;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.util.StringUtils;

/**
 * 纯图片消息（用户文本为空）时跳过 RAG，避免 {@link org.springframework.ai.rag.Query} 因空 query 抛错。
 */
public final class EmptyQuerySkippingRetrievalAugmentationAdvisor implements BaseAdvisor {

    static final String SKIP_RAG_CONTEXT_KEY = "j2agent.skipRag";

    private final RetrievalAugmentationAdvisor delegate;

    private EmptyQuerySkippingRetrievalAugmentationAdvisor(RetrievalAugmentationAdvisor delegate) {
        this.delegate = delegate;
    }

    public static EmptyQuerySkippingRetrievalAugmentationAdvisor wrap(RetrievalAugmentationAdvisor delegate) {
        return new EmptyQuerySkippingRetrievalAugmentationAdvisor(delegate);
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (!hasUserText(request)) {
            return request.mutate().context(SKIP_RAG_CONTEXT_KEY, true).build();
        }
        return delegate.before(request, chain);
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

    private static boolean hasUserText(ChatClientRequest request) {
        if (request == null || request.prompt() == null) {
            return false;
        }
        UserMessage userMessage = request.prompt().getUserMessage();
        return userMessage != null && StringUtils.hasText(userMessage.getText());
    }
}
