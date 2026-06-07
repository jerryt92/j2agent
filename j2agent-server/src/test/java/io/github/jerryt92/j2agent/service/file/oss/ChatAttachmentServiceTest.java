package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.mapper.ObjectFileMapper;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAttachmentServiceTest {

    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAD0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private ObjectStorageService storageService;
    private ObjectFileManagementService fileService;
    private ObjectFileMapper fileMapper;
    private ObjectFileReferenceService referenceService;
    private ChatAttachmentUrlResolver urlResolver;
    private ChatAttachmentService attachmentService;

    @BeforeEach
    void setUp() {
        storageService = mock(ObjectStorageService.class);
        fileService = mock(ObjectFileManagementService.class);
        fileMapper = mock(ObjectFileMapper.class);
        referenceService = mock(ObjectFileReferenceService.class);
        urlResolver = mock(ChatAttachmentUrlResolver.class);
        attachmentService = new ChatAttachmentService(
                storageService, fileService, fileMapper, referenceService, urlResolver);
        when(storageService.getDefaultBucket()).thenReturn("bucket");
    }

    @Test
    void validateAndReferenceShouldUploadInlineBase64Image() {
        ChatAttachmentDto inbound = new ChatAttachmentDto()
                .name("photo.png")
                .contentType("image/png")
                .size((long) PNG_BYTES.length)
                .data(Base64.getEncoder().encodeToString(PNG_BYTES));

        ObjectFilePo stored = new ObjectFilePo();
        stored.setObjectKey("chat/user-1/ctx-1/uuid_photo.png");
        stored.setContentType("image/png");
        stored.setSizeBytes((long) PNG_BYTES.length);
        stored.setOperationStatus("READY");

        when(fileService.uploadBytes(
                eq("chat/user-1/ctx-1/"),
                anyString(),
                eq(PNG_BYTES),
                eq("image/png"),
                eq((long) PNG_BYTES.length)))
                .thenReturn(stored);
        when(urlResolver.displayUrl("chat/user-1/ctx-1/uuid_photo.png"))
                .thenReturn("https://oss.example.com/chat/user-1/ctx-1/uuid_photo.png");

        List<ChatAttachmentDto> validated = attachmentService.validateAndReference(
                List.of(inbound), "ctx-1", "agent-1", 0, "user-1");

        assertEquals(1, validated.size());
        ChatAttachmentDto normalized = validated.get(0);
        assertNotNull(normalized.getObjectKey());
        assertEquals("photo.png", normalized.getName());
        assertEquals("image/png", normalized.getContentType());
        assertEquals((long) PNG_BYTES.length, normalized.getSize());
        assertEquals(
                "https://oss.example.com/chat/user-1/ctx-1/uuid_photo.png",
                normalized.getUrl());

        verify(fileService).uploadBytes(
                eq("chat/user-1/ctx-1/"),
                anyString(),
                eq(PNG_BYTES),
                eq("image/png"),
                eq((long) PNG_BYTES.length));
        verify(referenceService).addChatReference(
                eq(stored), eq("ctx-1"), eq("agent-1"), eq(0), eq("user-1"));
    }
}
