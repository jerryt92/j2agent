package io.github.jerryt92.j2agent.service.llm.advisor;

import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class EmptyQuerySkippingRetrievalAugmentationAdvisorTest {

    @Test
    void shouldSkipRagWhenUserInputCompletelyEmpty() {
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
    void shouldNotSkipRagWhenImagePresentWithoutText() {
        Media media = Media.builder()
                .mimeType(MediaType.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1}))
                .build();
        UserMessage userMessage = UserMessage.builder().text("").media(media).build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        assertFalse(EmptyQuerySkippingRetrievalAugmentationAdvisor.shouldSkipRag(request));
    }

    @Test
    void shouldNotThrowForImageOnlyRequest() {
        DocumentRetriever retriever = mock(DocumentRetriever.class);
        RetrievalAugmentationAdvisor ragAdvisor = RetrievalAugmentationAdvisor.builder()
                .documentRetriever(retriever)
                .build();
        EmptyQuerySkippingRetrievalAugmentationAdvisor advisor =
                EmptyQuerySkippingRetrievalAugmentationAdvisor.wrap(ragAdvisor);
        AdvisorChain chain = mock(AdvisorChain.class);
        Media media = Media.builder()
                .mimeType(MediaType.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1}))
                .build();
        UserMessage userMessage = UserMessage.builder().text("").media(media).build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        ChatClientRequest processed = advisor.before(request, chain);

        assertFalse(Boolean.TRUE.equals(processed.context().get(EmptyQuerySkippingRetrievalAugmentationAdvisor.SKIP_RAG_CONTEXT_KEY)));
    }

    @Test
    void shouldNotSkipRagWhenAttachmentsMetadataPresent() {
        ChatAttachmentDto attachment = new ChatAttachmentDto();
        attachment.setObjectKey("chat/u/c/x.png");
        UserMessage userMessage = UserMessage.builder()
                .text("")
                .metadata(Map.of("attachments", List.of(attachment)))
                .build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        assertFalse(EmptyQuerySkippingRetrievalAugmentationAdvisor.shouldSkipRag(request));
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
