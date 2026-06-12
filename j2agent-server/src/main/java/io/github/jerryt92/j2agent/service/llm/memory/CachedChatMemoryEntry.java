package io.github.jerryt92.j2agent.service.llm.memory;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Redis 缓存中的单条对话，字段与 {@link ChatMemoryMessageCodec.PersistedRow} 对齐（chat_role 含 3=tool）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CachedChatMemoryEntry(int chatRole, String content, String metaJson, String ragInfos) {
}
