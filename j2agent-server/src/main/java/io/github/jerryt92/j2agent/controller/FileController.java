package io.github.jerryt92.j2agent.controller;

import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.service.file.StaticFileService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 文件读取控制器，提供静态文件与知识库源文件直链访问。
 */
@Slf4j
@RestController
public class FileController {
    private final StaticFileService staticFileService;

    public FileController(StaticFileService staticFileService) {
        this.staticFileService = staticFileService;
    }

    /**
     * 读取静态目录文件。
     */
    @GetMapping(CommonConstants.FILE_URL + "static/**")
    public ResponseEntity<Resource> getStaticFile(HttpServletRequest request) {
        String relativePath = extractSubPath(request, CommonConstants.STATIC_FILE_URL);
        if (relativePath == null || relativePath.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = staticFileService.getStaticFile(relativePath);
        if (resource == null || !resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(StaticFileService.parseMediaType(resource.getFilename()))
                .body(resource);
    }

    /**
     * 从知识库仓库直读源文件（图片等资源）。
     */
    @GetMapping(CommonConstants.FILE_URL + "repo/**")
    public ResponseEntity<Resource> getKnowledgeRepoFile(HttpServletRequest request) {
        String relativePath = extractSubPath(request, CommonConstants.REPO_FILE_URL);
        if (relativePath == null || relativePath.isBlank()) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = staticFileService.getKnowledgeRepoFile(relativePath);
        if (resource == null || !resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(StaticFileService.parseMediaType(resource.getFilename()))
                .body(resource);
    }

    /**
     * 从请求 URI 中提取 prefix 之后的相对路径并 URL 解码。
     */
    private String extractSubPath(HttpServletRequest request, String prefix) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        if (!uri.startsWith(prefix)) {
            return null;
        }
        String subPath = uri.substring(prefix.length());
        return URLDecoder.decode(subPath, StandardCharsets.UTF_8);
    }
}
