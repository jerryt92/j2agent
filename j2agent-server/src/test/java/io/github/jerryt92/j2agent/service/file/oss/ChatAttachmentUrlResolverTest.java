package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.config.ObjectStorageProperties;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAttachmentUrlResolverTest {

    private ObjectFileManagementService fileService;
    private ObjectStorageProperties storageProperties;
    private ChatAttachmentUrlResolver resolver;

    @BeforeEach
    void setUp() throws MalformedURLException {
        fileService = mock(ObjectFileManagementService.class);
        storageProperties = new ObjectStorageProperties();
        when(fileService.previewUrl(
                eq("chat/user/ctx-1/uuid_image.png"),
                eq(ChatAttachmentUrlResolver.DISPLAY_URL_TTL)))
                .thenReturn(new URL("https://oss.example.com/chat/user/ctx-1/uuid_image.png"));
    }

    @Test
    void directModeShouldReturnOssPresignedLink() {
        storageProperties.setChatAttachmentDisplay(
                ObjectStorageProperties.ChatAttachmentDisplayMode.DIRECT);
        resolver = new ChatAttachmentUrlResolver(fileService, storageProperties);

        List<ChatAttachmentDto> resolved = resolver.withDisplayUrls(List.of(sampleAttachment()));

        assertEquals("https://oss.example.com/chat/user/ctx-1/uuid_image.png", resolved.getFirst().getUrl());
    }

    @Test
    void proxyModeShouldReturnStableContentUrl() {
        storageProperties.setChatAttachmentDisplay(
                ObjectStorageProperties.ChatAttachmentDisplayMode.PROXY);
        resolver = new ChatAttachmentUrlResolver(fileService, storageProperties);

        List<ChatAttachmentDto> resolved = resolver.withDisplayUrls(List.of(sampleAttachment()));

        assertTrue(resolved.getFirst().getUrl().contains("/chat/files/content?objectKey="));
        assertTrue(resolved.getFirst().getUrl().contains("chat%2Fuser%2Fctx-1"));
    }

    private static ChatAttachmentDto sampleAttachment() {
        return new ChatAttachmentDto()
                .objectKey("chat/user/ctx-1/uuid_image.png")
                .name("image.png")
                .contentType("image/png")
                .size(128L);
    }
}
