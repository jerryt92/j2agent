package io.github.jerryt92.j2agent.service.llm.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmptyQuerySkippingRetrievalAugmentationAdvisorTest {

    @Test
    void shouldSkipRagWhenUserTextIsBlank() {
        DocumentRetriever retriever = mock(DocumentRetriever.class);
        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
        EmptyQuerySkippingRetrievalAugmentationAdvisor advisor =
                EmptyQuerySkippingRetrievalAugmentationAdvisor.wrap(ragAdvisor);
        AdvisorChain chain = mock(AdvisorChain.class);

        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(UserMessage.builder().text("").build())))
                .build();

        ChatClientRequest processed = advisor.before(request, chain);

        assertTrue(Boolean.TRUE.equals(processed.context().get(EmptyQuerySkippingRetrievalAugmentationAdvisor.SKIP_RAG_CONTEXT_KEY)));
        verify(retriever, never()).retrieve(any());
    }

    @Test
    void shouldSkipRagAfterWhenMarkedInContext() {
        DocumentRetriever retriever = mock(DocumentRetriever.class);
        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
        EmptyQuerySkippingRetrievalAugmentationAdvisor advisor =
                EmptyQuerySkippingRetrievalAugmentationAdvisor.wrap(ragAdvisor);
        AdvisorChain chain = mock(AdvisorChain.class);
        ChatClientResponse response = ChatClientResponse.builder()
                .context(Map.of(EmptyQuerySkippingRetrievalAugmentationAdvisor.SKIP_RAG_CONTEXT_KEY, true))
                .build();

        ChatClientResponse processed = advisor.after(response, chain);

        assertSame(response, processed);
    }
}
