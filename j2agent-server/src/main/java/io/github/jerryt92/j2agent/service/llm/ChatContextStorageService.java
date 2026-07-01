package io.github.jerryt92.j2agent.service.llm;


import io.github.jerryt92.j2agent.mapper.mgb.ChatContextItemMapper;
import io.github.jerryt92.j2agent.model.Translator;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextItem;
import io.github.jerryt92.j2agent.model.po.mgb.ChatContextItemExample;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话上下文存储服务
 */
@Slf4j
@Service
public class ChatContextStorageService {
    private final ChatContextItemMapper chatContextItemMapper;
    private final SqlSessionTemplate sqlSessionTemplate;

    public ChatContextStorageService(ChatContextItemMapper chatContextItemMapper, SqlSessionTemplate sqlSessionTemplate) {
        this.chatContextItemMapper = chatContextItemMapper;
        this.sqlSessionTemplate = sqlSessionTemplate;
    }

    @Transactional(rollbackFor = Throwable.class)
    public void storageChatContextToDb(ChatContextBo chatContextBo, ConcurrentHashMap<String, ChatContextBo> chatContextMap) {
        List<ChatContextItem> insertChatContextItemList = new ArrayList<>();
        List<ChatContextItem> chatContextItem = Translator.translateToChatContextItem(chatContextBo);
        ChatContextItemExample chatContextItemExample = new ChatContextItemExample();
        chatContextItemExample.createCriteria().andContextIdEqualTo(chatContextBo.getContextId());
        // 查询数据库中已有的对话上下文
        HashSet<String> existMessageIndexAndRoleSet = new HashSet<>();
        for (ChatContextItem item : chatContextItemMapper.selectByExample(chatContextItemExample)) {
            existMessageIndexAndRoleSet.add("" + item.getMessageIndex() + item.getChatRole());
        }
        for (ChatContextItem insertChatContextItem : chatContextItem) {
            if (!existMessageIndexAndRoleSet.contains("" + insertChatContextItem.getMessageIndex() + insertChatContextItem.getChatRole())) {
                insertChatContextItemList.add(insertChatContextItem);
            }
        }
        if (!CollectionUtils.isEmpty(insertChatContextItemList)) {
            try (SqlSession session = sqlSessionTemplate.getSqlSessionFactory().openSession(ExecutorType.BATCH)) {
                ChatContextItemMapper batchChatContextItemMapper = session.getMapper(ChatContextItemMapper.class);
                for (ChatContextItem insertChatContextItem : insertChatContextItemList) {
                    batchChatContextItemMapper.insert(insertChatContextItem);
                }
            }
        }
        if (chatContextMap != null) {
            chatContextMap.remove(chatContextBo.getContextId());
        }
    }

    @Transactional(rollbackFor = Throwable.class)
    public void storageChatContextToDb(ChatContextBo chatContextBo) {
        storageChatContextToDb(chatContextBo, null);
    }
}
