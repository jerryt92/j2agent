package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.config.storage.ObjectStorageProperties;
import io.github.jerryt92.j2agent.controller.FileManagementController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

/**
 * 按 {@link ObjectStorageProperties#getChatAttachmentDisplay()} 为文件管理预览生成展示 URL。
 */
@Service
@ConditionalOnBean(ObjectFileManagementService.class)
public class ObjectFilePreviewUrlResolver {

    private final ObjectFileManagementService fileService;
    private final ObjectStorageProperties storageProperties;

    public ObjectFilePreviewUrlResolver(ObjectFileManagementService fileService,
                                          ObjectStorageProperties storageProperties) {
        this.fileService = fileService;
        this.storageProperties = storageProperties;
    }

    public String displayUrl(String objectKey) {
        if (storageProperties.getChatAttachmentDisplay()
                != ObjectStorageProperties.ChatAttachmentDisplayMode.DIRECT) {
            return FileManagementController.stableContentUrl(objectKey);
        }
        return fileService.previewUrl(objectKey).toString();
    }
}
