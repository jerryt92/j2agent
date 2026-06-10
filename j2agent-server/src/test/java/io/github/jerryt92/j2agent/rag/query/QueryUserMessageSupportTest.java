package io.github.jerryt92.j2agent.service.rag.query;

import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.rag.Query;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryUserMessageSupportTest {

    @Test
    void hasRetrievalInputWhenTextPresent() {
        Query query = Query.builder()
                .text("如何登录")
                .history(List.of(UserMessage.builder().text("如何登录").build()))
                .build();
        assertTrue(QueryUserMessageSupport.hasRetrievalInput(query));
    }

    @Test
    void hasRetrievalInputWhenMediaPresentWithoutText() {
        Media media = Media.builder()
                .mimeType(MediaType.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1, 2, 3}))
                .build();
        UserMessage userMessage = UserMessage.builder()
                .text("")
                .media(media)
                .build();
        assertTrue(QueryUserMessageSupport.hasRetrievalInput(userMessage));
    }

    @Test
    void hasRetrievalInputWhenAttachmentsMetadataPresent() {
        ChatAttachmentDto attachment = new ChatAttachmentDto();
        attachment.setObjectKey("chat/u1/c1/a.png");
        UserMessage userMessage = UserMessage.builder()
                .text("")
                .metadata(Map.of("attachments", List.of(attachment)))
                .build();
        assertTrue(QueryUserMessageSupport.hasRetrievalInput(userMessage));
    }

    @Test
    void hasNoRetrievalInputWhenCompletelyEmpty() {
        UserMessage userMessage = UserMessage.builder().text("").build();
        assertFalse(QueryUserMessageSupport.hasRetrievalInput(userMessage));
    }

    @Test
    void patchRequestForImageOnlyRagInjectsInvisiblePlaceholder() {
        Media media = Media.builder()
                .mimeType(MediaType.IMAGE_PNG)
                .data(new ByteArrayResource(new byte[]{1}))
                .build();
        UserMessage userMessage = UserMessage.builder().text("").media(media).build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt(List.of(userMessage)))
                .build();

        ChatClientRequest patched = QueryUserMessageSupport.patchRequestForImageOnlyRag(request);

        assertEquals(QueryUserMessageSupport.IMAGE_ONLY_QUERY_PLACEHOLDER,
                patched.prompt().getUserMessage().getText());
        assertEquals("", QueryUserMessageSupport.resolveUserText(
                Query.builder().text(patched.prompt().getUserMessage().getText()).build(),
                patched.prompt().getUserMessage()));
        assertFalse(patched.prompt().getUserMessage().getMedia().isEmpty());
    }

    @Test
    void resolveUserTextTreatsPlaceholderAsEmpty() {
        Query query = Query.builder()
                .text(QueryUserMessageSupport.IMAGE_ONLY_QUERY_PLACEHOLDER)
                .build();
        assertEquals("", QueryUserMessageSupport.resolveUserText(query, null));
    }

    @Test
    void queryAcceptsPlaceholderText() {
        Query query = Query.builder()
                .text(QueryUserMessageSupport.IMAGE_ONLY_QUERY_PLACEHOLDER)
                .build();
        assertEquals(QueryUserMessageSupport.IMAGE_ONLY_QUERY_PLACEHOLDER, query.text());
    }
}
