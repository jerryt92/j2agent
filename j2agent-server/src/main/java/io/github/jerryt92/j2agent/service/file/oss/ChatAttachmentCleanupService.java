package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.mapper.ObjectFileMapper;
import io.github.jerryt92.j2agent.mapper.ObjectFileReferenceMapper;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@ConditionalOnBean(ObjectFileManagementService.class)
public class ChatAttachmentCleanupService {

    private final ObjectStorageService storageService;
    private final ObjectFileManagementService fileManagementService;
    private final ObjectFileMapper fileMapper;
    private final ObjectFileReferenceMapper referenceMapper;

    public ChatAttachmentCleanupService(ObjectStorageService storageService,
                                        ObjectFileManagementService fileManagementService,
                                        ObjectFileMapper fileMapper,
                                        ObjectFileReferenceMapper referenceMapper) {
        this.storageService = storageService;
        this.fileManagementService = fileManagementService;
        this.fileMapper = fileMapper;
        this.referenceMapper = referenceMapper;
    }

    public void cleanupOrphanFiles(List<String> fileIds) {
        if (CollectionUtils.isEmpty(fileIds)) {
            return;
        }
        Set<String> unique = new LinkedHashSet<>(fileIds);
        for (String fileId : unique) {
            if (!String.valueOf(fileId).isBlank() && referenceMapper.countByFileId(fileId) == 0) {
                deleteByFileId(fileId);
            }
        }
    }

    public void deleteByChatContextPrefix(String userId, String contextId) {
        String prefix = ChatFileKeys.chatObjectPrefix(userId, contextId);
        String bucket = storageService.getDefaultBucket();
        List<ObjectFilePo> files = fileMapper.selectByObjectKeyPrefix(bucket, prefix);
        if (files.isEmpty()) {
            return;
        }
        List<String> failed = new ArrayList<>();
        for (ObjectFilePo file : files) {
            try {
                fileManagementService.delete(file.getObjectKey());
            } catch (RuntimeException e) {
                failed.add(file.getObjectKey());
                log.warn("Failed to delete chat attachment {} during context cleanup: {}", file.getObjectKey(), e.toString());
            }
        }
        if (!failed.isEmpty()) {
            log.warn("Context chat attachment cleanup incomplete for prefix {}: {}", prefix, failed);
        }
    }

    private void deleteByFileId(String fileId) {
        ObjectFilePo file = fileMapper.selectById(fileId);
        if (file == null) {
            return;
        }
        try {
            fileManagementService.delete(file.getObjectKey());
        } catch (RuntimeException e) {
            log.warn("Failed to delete orphan chat attachment {}: {}", file.getObjectKey(), e.toString());
        }
    }
}
