package io.github.jerryt92.j2agent.model;

import com.alibaba.fastjson2.JSON;
import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.service.file.StaticFileService;
import io.github.jerryt92.j2agent.service.llm.ChatContextBo;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TranslatorRagSourceHistoryTest {

    @Test
    void shouldRestoreSrcFileWhenLoadingHistoryFromDecodedAssistant() {
        FileDto fileDto = new FileDto()
                .id(1)
                .fullFileName("架构与流程.md")
                .url(CommonConstants.REPO_FILE_URL + "j2agent-docs/%E5%89%8D%E7%AB%AF/md%E8%A7%A3%E6%9E%90%E5%99%A8/%E6%9E%B6%E6%9E%84%E4%B8%8E%E6%B5%81%E7%A8%8B.md");
        RagInfoDto ragInfo = new RagInfoDto().srcFile(fileDto);
        String ragInfosJson = JSON.toJSONString(List.of(ragInfo));

        ChatMemoryMessageCodec codec = new ChatMemoryMessageCodec(new com.fasterxml.jackson.databind.ObjectMapper());
        Message decoded = codec.decode(2, "answer text", null, ragInfosJson);
        assertNotNull(decoded);

        ChatContextBo contextBo = new ChatContextBo(
                "ctx-1",
                "user-1",
                "chat_assistant",
                "title",
                1,
                1,
                System.currentTimeMillis(),
                List.of(decoded)
        );

        ChatContextDto dto = Translator.translateToChatContextDto(contextBo);

        assertEquals(1, dto.getMessages().size());
        MessageDto messageDto = dto.getMessages().getFirst();
        assertNotNull(messageDto.getSrcFile());
        assertEquals(1, messageDto.getSrcFile().size());
        assertEquals("架构与流程.md", messageDto.getSrcFile().getFirst().getFullFileName());
        assertEquals("j2agent-docs/前端/md解析器/架构与流程.md",
                messageDto.getSrcFile().getFirst().getRelativePath());
        assertFalse(messageDto.getSrcFile().getFirst().getUrl().contains("%2F"));
    }

    @Test
    void shouldOmitSrcFileOnHistoryLoadWhenDisplayDisabled() {
        FileDto fileDto = new FileDto()
                .id(9)
                .fullFileName("doc.md")
                .url(CommonConstants.REPO_FILE_URL + "docs/doc.md");
        String ragInfosJson = JSON.toJSONString(List.of(new RagInfoDto().srcFile(fileDto)));

        ChatMemoryMessageCodec codec = new ChatMemoryMessageCodec(new com.fasterxml.jackson.databind.ObjectMapper());
        Message decoded = codec.decode(2, "answer", null, ragInfosJson);

        ChatContextBo contextBo = new ChatContextBo(
                "ctx-1", "user-1", "chat_assistant", "title", 1, 1,
                System.currentTimeMillis(), List.of(decoded));

        ChatContextDto dto = Translator.translateToChatContextDto(contextBo, false);

        assertEquals(1, dto.getMessages().size());
        assertNull(dto.getMessages().getFirst().getSrcFile());
    }

    @Test
    void shouldNormalizeLegacyEncodedSlashRepoUrlsOnHistoryLoad() {
        String legacyUrl = CommonConstants.REPO_FILE_URL
                + "j2agent-docs%2F%E5%89%8D%E7%AB%AF%2Fdoc.md";
        FileDto fileDto = new FileDto().id(2).fullFileName("doc.md").url(legacyUrl);
        List<FileDto> files = Translator.parseSrcFilesFromRagInfosJson(
                JSON.toJSONString(List.of(new RagInfoDto().srcFile(fileDto))));

        assertEquals(1, files.size());
        assertFalse(files.getFirst().getUrl().contains("%2F"));
        assertEquals(
                CommonConstants.REPO_FILE_URL + "j2agent-docs/%E5%89%8D%E7%AB%AF/doc.md",
                files.getFirst().getUrl());
        assertEquals("j2agent-docs/前端/doc.md", files.getFirst().getRelativePath());
    }

    @Test
    void shouldNormalizeAbsoluteAiCenterLegacyRepoUrlsOnHistoryLoad() {
        String legacyUrl = "https://inc.raisecom.com.cn/v1/rest/ai-center/file/repo/"
                + "knowledge_base%2Fcommon%2Fassets%2Fdoc.png";
        FileDto fileDto = new FileDto().id(4).fullFileName("doc.png").url(legacyUrl);
        List<FileDto> files = Translator.parseSrcFilesFromRagInfosJson(
                JSON.toJSONString(List.of(new RagInfoDto().srcFile(fileDto))));

        assertEquals(1, files.size());
        assertFalse(files.getFirst().getUrl().contains("%2F"));
        assertTrue(files.getFirst().getUrl().contains("/file/repo/knowledge_base/common/assets/"));
        assertEquals("knowledge_base/common/assets/doc.png", files.getFirst().getRelativePath());
    }

    @Test
    void shouldBackfillRelativePathFromRepoUrlWhenMissing() {
        String path = "j2agent-docs/平台/RAG机制/检索/README.md";
        FileDto fileDto = new FileDto()
                .id(3)
                .fullFileName("README.md")
                .url(StaticFileService.toRepoFileUrl(path));
        List<FileDto> files = Translator.parseSrcFilesFromRagInfosJson(
                JSON.toJSONString(List.of(new RagInfoDto().srcFile(fileDto))));

        assertEquals(1, files.size());
        assertEquals(path, files.getFirst().getRelativePath());
    }
}
