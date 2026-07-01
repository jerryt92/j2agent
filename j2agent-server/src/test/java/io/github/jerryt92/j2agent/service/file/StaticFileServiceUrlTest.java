package io.github.jerryt92.j2agent.service.file;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticFileServiceUrlTest {

    @Test
    void shouldEncodeRepoPathBySegmentWithoutEncodedSlashes() {
        String url = StaticFileService.toRepoFileUrl("j2agent-docs/前端/md解析器/架构与流程.md");

        assertFalse(url.contains("%2F"), "must not encode path separators");
        assertEquals(
                "/v1/rest/j2agent/file/repo/j2agent-docs/%E5%89%8D%E7%AB%AF/md%E8%A7%A3%E6%9E%90%E5%99%A8/%E6%9E%B6%E6%9E%84%E4%B8%8E%E6%B5%81%E7%A8%8B.md",
                url);
    }

    @Test
    void shouldNormalizeLegacyEncodedSlashRepoUrls() {
        String legacy = "https://xxx/file/repo/"
                + "knowledge_base%2Fcommon%2Fassets%2F%E9%85%8D%E7%BD%AEiNC%E7%9A%84SNMP%E5%86%85%E7%BD%91%E7%A9%BF%E9%80%8F.png";
        String normalized = StaticFileService.normalizeRepoFileUrl(legacy);

        assertFalse(normalized.contains("%2F"));
        assertEquals(
                "https://xxx/file/repo/"
                        + "knowledge_base/common/assets/%E9%85%8D%E7%BD%AEiNC%E7%9A%84SNMP%E5%86%85%E7%BD%91%E7%A9%BF%E9%80%8F.png",
                normalized);
        assertEquals(
                "knowledge_base/common/assets/配置iNC的SNMP内网穿透.png",
                StaticFileService.extractRepoRelativePath(normalized));
    }

    @Test
    void shouldSetUtf8ContentDispositionFilenameOnFileResponse() {
        ResponseEntity<org.springframework.core.io.Resource> response = StaticFileService.asFileResponse(
                new ByteArrayResource("hello".getBytes(StandardCharsets.UTF_8)),
                "架构与流程.md");

        HttpHeaders headers = response.getHeaders();
        ContentDisposition disposition = headers.getContentDisposition();
        assertNotNull(disposition);
        assertEquals("架构与流程.md", disposition.getFilename());
        assertTrue(headers.getContentType().toString().contains("markdown"));
    }
}
