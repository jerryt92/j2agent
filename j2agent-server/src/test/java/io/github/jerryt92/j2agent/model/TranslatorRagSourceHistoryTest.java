package io.github.jerryt92.j2agent.model;

import com.alibaba.fastjson2.JSON;
import io.github.jerryt92.j2agent.constants.CommonConstants;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextItem;
import io.github.jerryt92.j2agent.service.file.StaticFileService;
import io.github.jerryt92.j2agent.service.llm.ChatContextBo;
import io.github.jerryt92.j2agent.service.llm.TurnStepItem;
import io.github.jerryt92.j2agent.service.llm.memory.ChatMemoryMessageCodec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Translator 历史消息中 RAG 来源回放测试。
 */
class TranslatorRagSourceHistoryTest {

    @Test
    void shouldHideTurnTraceWhenTranslatingSingleChatContextItemWithoutMeta() {
        ChatContextItem item = new ChatContextItem();
        item.setMessageIndex(7);
        item.setChatRole(2);
        item.setContent("{\"v\":1,\"kind\":\"turn_trace\",\"turnId\":\"turn-1\",\"steps\":[{\"state\":\"IDLE\",\"ts\":1}]}");
        item.setFeedback(0);

        MessageDto messageDto = Translator.translateToChatMessageDto(item);

        assertFalse(messageDto.getDisplayInChat());
        assertEquals(MessageDto.MessageKindEnum.TOOL_ROUND, messageDto.getMessageKind());
        assertEquals("", messageDto.getContent());
    }

    @Test
    void shouldBackfillTurnStepsAndSkipTurnTraceMessageOnHistoryLoad() throws Exception {
        ChatMemoryMessageCodec codec = new ChatMemoryMessageCodec(new com.fasterxml.jackson.databind.ObjectMapper());
        Message answer = new AssistantMessage("answer text");
        Message turnTrace = codec.buildTurnTraceAuditMessage("turn-1", List.of(
                new TurnStepItem(AgentState.IDLE, null, 1L),
                new TurnStepItem(AgentState.THINKING, null, 2L),
                new TurnStepItem(AgentState.COMPLETED, null, 3L)));

        ChatContextBo contextBo = new ChatContextBo(
                "ctx-1", "user-1", "chat_assistant", "title", 1, 1,
                System.currentTimeMillis(), List.of(answer, turnTrace));

        ChatContextDto dto = Translator.translateToChatContextDto(contextBo);

        assertEquals(1, dto.getMessages().size());
        MessageDto messageDto = dto.getMessages().getFirst();
        assertEquals("answer text", messageDto.getContent());
        assertNotNull(messageDto.getTurnSteps());
        assertEquals(3, messageDto.getTurnSteps().size());
        assertEquals(TurnStepDto.StateEnum.IDLE, messageDto.getTurnSteps().getFirst().getState());
        assertEquals(TurnStepDto.StateEnum.COMPLETED, messageDto.getTurnSteps().getLast().getState());
    }

    @Test
    void shouldSkipOrphanTurnTraceMessageOnHistoryLoad() throws Exception {
        ChatMemoryMessageCodec codec = new ChatMemoryMessageCodec(new com.fasterxml.jackson.databind.ObjectMapper());
        Message turnTrace = codec.buildTurnTraceAuditMessage("turn-1", List.of(
                new TurnStepItem(AgentState.IDLE, null, 1L),
                new TurnStepItem(AgentState.COMPLETED, null, 2L)));

        ChatContextBo contextBo = new ChatContextBo(
                "ctx-1", "user-1", "chat_assistant", "title", 1, 1,
                System.currentTimeMillis(), List.of(turnTrace));

        ChatContextDto dto = Translator.translateToChatContextDto(contextBo);

        assertEquals(0, dto.getMessages().size());
    }

    @Test
    void shouldRestoreSrcFileWhenLoadingHistoryFromDecodedAssistant() {
        FileDto fileDto = new FileDto()
                .id(1)
                .fullFileName("架构与流程.md")
                .url(CommonConstants.REPO_FILE_URL + "wiki/%E5%89%8D%E7%AB%AF/md%E8%A7%A3%E6%9E%90%E5%99%A8/%E6%9E%B6%E6%9E%84%E4%B8%8E%E6%B5%81%E7%A8%8B.md");
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
        assertEquals("wiki/前端/md解析器/架构与流程.md",
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
        assertTrue(dto.getMessages().getFirst().getSrcFile().isEmpty());
    }

    @Test
    void shouldNormalizeLegacyEncodedSlashRepoUrlsOnHistoryLoad() {
        String legacyUrl = CommonConstants.REPO_FILE_URL
                + "wiki%2F%E5%89%8D%E7%AB%AF%2Fdoc.md";
        FileDto fileDto = new FileDto().id(2).fullFileName("doc.md").url(legacyUrl);
        List<FileDto> files = Translator.parseSrcFilesFromRagInfosJson(
                JSON.toJSONString(List.of(new RagInfoDto().srcFile(fileDto))));

        assertEquals(1, files.size());
        assertFalse(files.getFirst().getUrl().contains("%2F"));
        assertEquals(
                CommonConstants.REPO_FILE_URL + "wiki/%E5%89%8D%E7%AB%AF/doc.md",
                files.getFirst().getUrl());
        assertEquals("wiki/前端/doc.md", files.getFirst().getRelativePath());
    }

    @Test
    void shouldBackfillRelativePathFromRepoUrlWhenMissing() {
        String path = "wiki/RAG机制/检索/README.md";
        FileDto fileDto = new FileDto()
                .id(3)
                .fullFileName("README.md")
                .url(StaticFileService.toRepoFileUrl(path));
        List<FileDto> files = Translator.parseSrcFilesFromRagInfosJson(
                JSON.toJSONString(List.of(new RagInfoDto().srcFile(fileDto))));

        assertEquals(1, files.size());
        assertEquals(path, files.getFirst().getRelativePath());
    }

    @Test
    void shouldDedupeDuplicateRelativePathsInRagInfosJson() {
        FileDto fileA = new FileDto()
                .id(1)
                .fullFileName("a.md")
                .relativePath("docs/a.md")
                .url(StaticFileService.toRepoFileUrl("docs/a.md"));
        FileDto fileADup = new FileDto()
                .id(2)
                .fullFileName("a.md")
                .relativePath("./docs/a.md")
                .url(StaticFileService.toRepoFileUrl("docs/a.md"));
        String ragInfosJson = JSON.toJSONString(List.of(
                new RagInfoDto().srcFile(fileA),
                new RagInfoDto().srcFile(fileADup)));

        List<FileDto> files = Translator.parseSrcFilesFromRagInfosJson(ragInfosJson);

        assertEquals(1, files.size());
        assertEquals("docs/a.md", files.getFirst().getRelativePath());
    }

    @Test
    void shouldDedupeSrcFilesByRelativePathNotHashId() {
        FileDto fileA = new FileDto()
                .id(1)
                .fullFileName("a.md")
                .relativePath("docs/a.md")
                .url(StaticFileService.toRepoFileUrl("docs/a.md"));
        FileDto fileB = new FileDto()
                .id(2)
                .fullFileName("b.md")
                .relativePath("docs/b.md")
                .url(StaticFileService.toRepoFileUrl("docs/b.md"));
        String ragInfosJson = JSON.toJSONString(List.of(
                new RagInfoDto().srcFile(fileA),
                new RagInfoDto().srcFile(fileB)));

        List<FileDto> files = Translator.parseSrcFilesFromRagInfosJson(ragInfosJson);

        assertEquals(2, files.size());
    }
}
