package io.github.jerryt92.j2agent.service.rag.knowledge.repository;

import io.github.jerryt92.j2agent.model.po.KnowledgeRepositoryPo;
import io.github.jerryt92.j2agent.model.repository.KnowledgeRepositoryDtos;

import java.nio.file.Path;

/**
 * 知识库仓库同步器接口。
 */
public interface KnowledgeRepositorySyncer {
    String protocol();

    KnowledgeRepositorySyncResult sync(KnowledgeRepositoryPo repository,
                                       KnowledgeRepositoryDtos.CredentialConfig credentialConfig,
                                       Path localPath);
}
