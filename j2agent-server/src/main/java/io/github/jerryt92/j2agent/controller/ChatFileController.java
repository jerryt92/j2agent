package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.model.ChatAttachmentDto;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import io.github.jerryt92.j2agent.model.security.SessionBo;
import io.github.jerryt92.j2agent.service.file.oss.ChatFileKeys;
import io.github.jerryt92.j2agent.service.file.oss.ChatAttachmentUrlResolver;
import io.github.jerryt92.j2agent.service.file.oss.ObjectFileManagementService;
import io.github.jerryt92.j2agent.service.llm.ChatContextService;
import io.github.jerryt92.j2agent.service.security.LoginService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.core.io.InputStreamResource;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/v1/rest/j2agent/chat/files")
@ConditionalOnBean(ObjectFileManagementService.class)
public class ChatFileController {
    private static final long MAX_IMAGE_BYTES = 10L * 1024L * 1024L;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final ObjectFileManagementService fileService;
    private final LoginService loginService;
    private final ChatContextService chatContextService;

    public ChatFileController(ObjectFileManagementService fileService,
                                LoginService loginService,
                                ChatContextService chatContextService) {
        this.fileService = fileService;
        this.loginService = loginService;
        this.chatContextService = chatContextService;
    }

    @PostMapping
    public ResponseEntity<ChatAttachmentDto> upload(@RequestParam("file") MultipartFile file,
                                                    @RequestParam("context-id") String contextId) {
        SessionBo session = requireSession();
        validate(file);
        if (!StringUtils.hasText(contextId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "context-id is required");
        }
        if (!chatContextService.userOwnsContext(contextId, session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "context does not belong to current user");
        }
        String prefix = ChatFileKeys.chatObjectPrefix(session.getUserId(), contextId);
        String storedFileName = ChatFileKeys.chatStoredFileName(file.getOriginalFilename());
        ObjectFilePo stored = fileService.upload(prefix, storedFileName, file);
        return ResponseEntity.ok(toDto(stored));
    }

    @GetMapping("/preview")
    public ResponseEntity<ChatAttachmentDto> preview(@RequestParam("objectKey") String objectKey) {
        SessionBo session = requireSession();
        ChatFileKeys.requireOwnedKey(objectKey, session.getUserId());
        ChatAttachmentDto dto = new ChatAttachmentDto();
        dto.setObjectKey(objectKey);
        dto.setName(ChatFileKeys.displayName(objectKey));
        dto.setContentType("image/*");
        dto.setUrl(fileService.previewUrl(objectKey).toString());
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/content")
    public ResponseEntity<InputStreamResource> content(@RequestParam("objectKey") String objectKey) {
        SessionBo session = requireSession();
        ChatFileKeys.requireOwnedKey(objectKey, session.getUserId());
        ObjectFilePo file = fileService.requireReadyObjectFile(objectKey);
        InputStream stream = fileService.openObjectStream(objectKey);
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (StringUtils.hasText(file.getContentType())) {
            mediaType = MediaType.parseMediaType(file.getContentType());
        }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .cacheControl(CacheControl.maxAge(15, TimeUnit.MINUTES).cachePrivate())
                .body(new InputStreamResource(stream));
    }

    private ChatAttachmentDto toDto(ObjectFilePo po) {
        ChatAttachmentDto dto = new ChatAttachmentDto();
        dto.setObjectKey(po.getObjectKey());
        dto.setName(ChatFileKeys.displayName(po.getObjectKey()));
        dto.setContentType(po.getContentType());
        dto.setSize(po.getSizeBytes());
        dto.setUrl(fileService.previewUrl(po.getObjectKey(), ChatAttachmentUrlResolver.DISPLAY_URL_TTL).toString());
        return dto;
    }

    private SessionBo requireSession() {
        SessionBo session = loginService.getSession();
        if (session == null || !StringUtils.hasText(session.getUserId())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "login required");
        }
        return session;
    }

    private static void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "image must not be empty");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "image exceeds 10 MB");
        }
        if (!IMAGE_TYPES.contains(file.getContentType())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "only JPEG, PNG and WebP images are supported");
        }
    }

    public static void requireOwnedKey(String objectKey, String userId) {
        ChatFileKeys.requireOwnedKey(objectKey, userId);
    }

    public static void requireOwnedKey(String objectKey, String userId, String contextId) {
        ChatFileKeys.requireOwnedKeyForReference(objectKey, userId, contextId);
    }

    public static String stableContentUrl(String objectKey) {
        return "/v1/rest/j2agent/chat/files/content?objectKey="
                + URLEncoder.encode(objectKey, StandardCharsets.UTF_8);
    }
}
