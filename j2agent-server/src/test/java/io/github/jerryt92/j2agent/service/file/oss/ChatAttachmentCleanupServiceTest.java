package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.mapper.ObjectFileMapper;
import io.github.jerryt92.j2agent.mapper.ObjectFileReferenceMapper;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatAttachmentCleanupServiceTest {

    private ObjectStorageService storageService;
    private ObjectFileManagementService fileManagementService;
    private ObjectFileMapper fileMapper;
    private ObjectFileReferenceMapper referenceMapper;
    private ChatAttachmentCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        storageService = mock(ObjectStorageService.class);
        fileManagementService = mock(ObjectFileManagementService.class);
        fileMapper = mock(ObjectFileMapper.class);
        referenceMapper = mock(ObjectFileReferenceMapper.class);
        cleanupService = new ChatAttachmentCleanupService(
                storageService, fileManagementService, fileMapper, referenceMapper);
        when(storageService.getDefaultBucket()).thenReturn("bucket");
    }

    @Test
    void cleanupOrphanFilesShouldDeleteWhenUnreferenced() {
        ObjectFilePo file = new ObjectFilePo();
        file.setId("file-1");
        file.setObjectKey("chat/u/c/file.png");
        when(referenceMapper.countByFileId("file-1")).thenReturn(0);
        when(fileMapper.selectById("file-1")).thenReturn(file);

        cleanupService.cleanupOrphanFiles(List.of("file-1"));

        verify(fileManagementService).delete("chat/u/c/file.png");
    }

    @Test
    void cleanupOrphanFilesShouldSkipWhenStillReferenced() {
        when(referenceMapper.countByFileId("file-1")).thenReturn(1);

        cleanupService.cleanupOrphanFiles(List.of("file-1"));

        verify(fileMapper, never()).selectById(anyString());
        verify(fileManagementService, never()).delete(anyString());
    }

    @Test
    void deleteByChatContextPrefixShouldDeleteAllMatchingFiles() {
        ObjectFilePo file = new ObjectFilePo();
        file.setObjectKey("chat/u/ctx-1/uuid_a.png");
        when(fileMapper.selectByObjectKeyPrefix("bucket", "chat/u/ctx-1/")).thenReturn(List.of(file));

        cleanupService.deleteByChatContextPrefix("u", "ctx-1");

        verify(fileManagementService).delete("chat/u/ctx-1/uuid_a.png");
    }
}
