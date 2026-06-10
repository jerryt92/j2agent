package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.config.storage.ObjectStorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.MalformedURLException;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObjectFilePreviewUrlResolverTest {

    private ObjectFileManagementService fileService;
    private ObjectStorageProperties storageProperties;
    private ObjectFilePreviewUrlResolver resolver;

    @BeforeEach
    void setUp() throws MalformedURLException {
        fileService = mock(ObjectFileManagementService.class);
        storageProperties = new ObjectStorageProperties();
        when(fileService.previewUrl(eq("docs/readme.pdf")))
                .thenReturn(new URL("https://oss.example.com/docs/readme.pdf"));
    }

    @Test
    void directModeShouldReturnOssPresignedLink() {
        storageProperties.setChatAttachmentDisplay(
                ObjectStorageProperties.ChatAttachmentDisplayMode.DIRECT);
        resolver = new ObjectFilePreviewUrlResolver(fileService, storageProperties);

        assertEquals("https://oss.example.com/docs/readme.pdf", resolver.displayUrl("docs/readme.pdf"));
    }

    @Test
    void proxyModeShouldReturnStableContentUrl() {
        storageProperties.setChatAttachmentDisplay(
                ObjectStorageProperties.ChatAttachmentDisplayMode.PROXY);
        resolver = new ObjectFilePreviewUrlResolver(fileService, storageProperties);

        String url = resolver.displayUrl("docs/readme.pdf");

        assertTrue(url.contains("/files/content?object-key="));
        assertTrue(url.contains("docs%2Freadme.pdf"));
    }
}
