package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAttachmentUrlResolverTest {

    private ObjectFileManagementService fileService;
    private ChatAttachmentUrlResolver resolver;

    @BeforeEach
    void setUp() throws MalformedURLException {
        fileService = mock(ObjectFileManagementService.class);
        resolver = new ChatAttachmentUrlResolver(fileService);
        when(fileService.previewUrl(
                eq("chat/user/ctx-1/uuid_image.png"),
                eq(ChatAttachmentUrlResolver.DISPLAY_URL_TTL)))
                .thenReturn(new URL("https://oss.example.com/chat/user/ctx-1/uuid_image.png"));
    }

    @Test
    void withDisplayUrlsShouldReturnOssPresignedLink() {
        ChatAttachmentDto attachment = new ChatAttachmentDto()
                .objectKey("chat/user/ctx-1/uuid_image.png")
                .name("image.png")
                .contentType("image/png")
                .size(128L);

        List<ChatAttachmentDto> resolved = resolver.withDisplayUrls(List.of(attachment));

        assertEquals(1, resolved.size());
        assertEquals(
                "https://oss.example.com/chat/user/ctx-1/uuid_image.png",
                resolved.getFirst().getUrl());
        assertEquals("chat/user/ctx-1/uuid_image.png", resolved.getFirst().getObjectKey());
    }
}
