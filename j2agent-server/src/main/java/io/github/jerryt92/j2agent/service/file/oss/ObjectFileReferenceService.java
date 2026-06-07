package io.github.jerryt92.j2agent.service.file.oss;

import io.github.jerryt92.j2agent.mapper.ObjectFileReferenceMapper;
import io.github.jerryt92.j2agent.model.po.ObjectFilePo;
import io.github.jerryt92.j2agent.model.po.ObjectFileReferencePo;
import io.github.jerryt92.j2agent.utils.UUIDv7Utils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ObjectFileReferenceService {
    public static final String CHAT_MESSAGE = "CHAT_MESSAGE";

    private final ObjectFileReferenceMapper mapper;

    public ObjectFileReferenceService(ObjectFileReferenceMapper mapper) {
        this.mapper = mapper;
    }

    public void addChatReference(ObjectFilePo file, String contextId, String agentId,
                                 int messageIndex, String ownerId) {
        ObjectFileReferencePo po = new ObjectFileReferencePo();
        po.setId(UUIDv7Utils.randomUUIDv7().replace("-", ""));
        po.setFileId(file.getId());
        po.setBusinessType(CHAT_MESSAGE);
        po.setBusinessId(chatBusinessId(contextId, agentId, messageIndex));
        po.setOwnerId(ownerId);
        po.setCreatedAt(System.currentTimeMillis());
        mapper.insertIgnore(po);
    }

    public boolean isReferenced(String fileId) {
        return mapper.countByFileId(fileId) > 0;
    }

    public List<String> findChatFileIds(String contextId, String agentId) {
        return mapper.selectFileIdsByBusinessPrefix(CHAT_MESSAGE, contextId + ":" + normalize(agentId) + ":");
    }

    public void removeChatReferences(String contextId, String agentId) {
        mapper.deleteByBusinessPrefix(CHAT_MESSAGE, contextId + ":" + normalize(agentId) + ":");
    }

    private static String chatBusinessId(String contextId, String agentId, int messageIndex) {
        return contextId + ":" + normalize(agentId) + ":" + messageIndex;
    }

    private static String normalize(String agentId) {
        return agentId == null ? "" : agentId;
    }
}
