package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.controller.ChatFileController;
import io.github.jerryt92.j2agent.mapper.ObjectFileMapper;
import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import io.github.jerryt92.j2agent.service.file.oss.util.ObjectKeyUtils;
import org.springframework.ai.content.Media;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

@Service
@ConditionalOnBean(ObjectFileManagementService.class)
public class ChatAttachmentService {
    private static final int MAX_IMAGES = 4;
    private static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final ObjectStorageService storageService;
    private final ObjectFileManagementService fileService;
    private final ObjectFileMapper fileMapper;
    private final ObjectFileReferenceService referenceService;
    private final ChatAttachmentUrlResolver urlResolver;

    public ChatAttachmentService(ObjectStorageService storageService,
                                 ObjectFileManagementService fileService,
                                 ObjectFileMapper fileMapper,
                                 ObjectFileReferenceService referenceService,
                                 ChatAttachmentUrlResolver urlResolver) {
        this.storageService = storageService;
        this.fileService = fileService;
        this.fileMapper = fileMapper;
        this.referenceService = referenceService;
        this.urlResolver = urlResolver;
    }

    public List<ChatAttachmentDto> validateAndReference(List<ChatAttachmentDto> attachments,
                                                        String contextId, String agentId,
                                                        int messageIndex, String userId) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        if (attachments.size() > MAX_IMAGES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at most 4 images are allowed");
        }
        List<ChatAttachmentDto> validated = new ArrayList<>();
        for (ChatAttachmentDto attachment : attachments) {
            ObjectFilePo file = resolveObjectFile(attachment, contextId, userId);
            ChatAttachmentDto normalized = toDto(file);
            validated.add(normalized);
            referenceService.addChatReference(file, contextId, agentId, messageIndex, userId);
        }
        return List.copyOf(validated);
    }

    public List<Media> toMedia(List<ChatAttachmentDto> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        String bucket = storageService.getDefaultBucket();
        return attachments.stream()
                .map(item -> Media.builder()
                        .mimeType(MimeTypeUtils.parseMimeType(item.getContentType()))
                        .data(new ByteArrayResource(readObjectBytes(bucket, item.getObjectKey())))
                        .name(item.getName())
                        .build())
                .toList();
    }

    private ObjectFilePo resolveObjectFile(ChatAttachmentDto attachment, String contextId, String userId) {
        if (StringUtils.hasText(attachment.getData())) {
            return uploadInlineImage(attachment, contextId, userId);
        }
        if (StringUtils.hasText(attachment.getObjectKey())) {
            return resolveExistingObject(attachment, contextId, userId);
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "attachment must include data or objectKey");
    }

    private ObjectFilePo uploadInlineImage(ChatAttachmentDto attachment, String contextId, String userId) {
        String contentType = normalizeContentType(attachment.getContentType());
        byte[] bytes = decodeBase64(attachment.getData());
        validateImageBytes(bytes, contentType, attachment.getSize());
        String prefix = ChatFileKeys.chatObjectPrefix(userId, contextId);
        String storedFileName = ChatFileKeys.chatStoredFileName(
                StringUtils.hasText(attachment.getName()) ? attachment.getName() : "image.png");
        ObjectFilePo stored = fileService.uploadBytes(prefix, storedFileName, bytes, contentType, bytes.length);
        if (!"READY".equals(stored.getOperationStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image is not ready");
        }
        return stored;
    }

    private ObjectFilePo resolveExistingObject(ChatAttachmentDto attachment, String contextId, String userId) {
        ChatFileController.requireOwnedKey(attachment.getObjectKey(), userId, contextId);
        ObjectFilePo file = fileMapper.selectByKey(
                storageService.getDefaultBucket(),
                ObjectKeyUtils.hash(attachment.getObjectKey()));
        if (file == null || !"READY".equals(file.getOperationStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image is not ready");
        }
        if (!IMAGE_TYPES.contains(file.getContentType())
                || file.getSizeBytes() == null || file.getSizeBytes() > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid chat image");
        }
        return file;
    }

    private ChatAttachmentDto toDto(ObjectFilePo file) {
        ChatAttachmentDto normalized = new ChatAttachmentDto();
        normalized.setObjectKey(file.getObjectKey());
        normalized.setName(ChatFileKeys.displayName(file.getObjectKey()));
        normalized.setContentType(file.getContentType());
        normalized.setSize(file.getSizeBytes());
        normalized.setUrl(urlResolver.displayUrl(file.getObjectKey()));
        return normalized;
    }

    private static byte[] decodeBase64(String data) {
        try {
            return Base64.getDecoder().decode(data.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid chat image data", e);
        }
    }

    private static void validateImageBytes(byte[] bytes, String contentType, Long declaredSize) {
        if (bytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image must not be empty");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds 10 MB");
        }
        if (declaredSize != null && declaredSize > 0 && declaredSize != bytes.length) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid chat image size");
        }
        if (!IMAGE_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "only JPEG, PNG and WebP images are supported");
        }
    }

    private static String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contentType is required");
        }
        return contentType.trim().toLowerCase();
    }

    private byte[] readObjectBytes(String bucket, String objectKey) {
        try (InputStream input = storageService.getObject(bucket, objectKey)) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "failed to read chat image", e);
        }
    }
}
